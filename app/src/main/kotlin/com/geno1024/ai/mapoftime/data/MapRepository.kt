package com.geno1024.ai.mapoftime.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import com.geno1024.ai.mapoftime.analysis.PlaceDrafts
import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TrackPoint
import com.geno1024.ai.mapoftime.core.TraversalSample
import com.geno1024.ai.mapoftime.core.TripMode

/**
 * All reads and writes of trip data. Every method is synchronous; callers run
 * them on a background dispatcher so the recorder never blocks the UI thread.
 */
class MapRepository(private val helper: MapDb) {

    private val db: SQLiteDatabase get() = helper.writableDatabase

    // ---------------------------------------------------------------- trips

    fun createTrip(mode: TripMode, startedAt: Long): Long =
        db.insert(
            "trips",
            null,
            ContentValues().apply {
                put("mode", mode.id)
                put("started_at", startedAt)
                put("status", TripStatus.RECORDING.name)
            },
        )

    fun appendPoint(tripId: Long, seq: Int, point: TrackPoint) {
        db.insertWithOnConflict(
            "points",
            null,
            ContentValues().apply {
                put("trip_id", tripId)
                put("seq", seq)
                put("t", point.t)
                put("lat", point.at.lat)
                put("lon", point.at.lon)
                put("acc", point.accuracy)
                point.speed?.let { put("speed", it) }
                point.bearing?.let { put("bearing", it) }
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun updateProgress(tripId: Long, distanceM: Double, pointCount: Int) {
        db.update(
            "trips",
            ContentValues().apply {
                put("distance_m", distanceM)
                put("point_count", pointCount)
            },
            "id = ?",
            arrayOf(tripId.toString()),
        )
    }

    fun finishTrip(tripId: Long, endedAt: Long, status: TripStatus = TripStatus.FINISHED) {
        db.update(
            "trips",
            ContentValues().apply {
                put("ended_at", endedAt)
                put("status", status.name)
            },
            "id = ?",
            arrayOf(tripId.toString()),
        )
    }

    /** A recording trip with no live service means the process died mid-walk. */
    fun markInterrupted(): Int =
        db.update(
            "trips",
            ContentValues().apply { put("status", TripStatus.INTERRUPTED.name) },
            "status = ?",
            arrayOf(TripStatus.RECORDING.name),
        )

    /**
     * Re-label an existing trip. The caller must re-analyse it afterwards: the
     * mode is also part of every segment key, so the old legs belong to the old
     * mode and would otherwise keep feeding the wrong statistics.
     */
    fun setTripMode(id: Long, mode: TripMode) {
        db.update(
            "trips",
            ContentValues().apply { put("mode", mode.id) },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    fun setTripNote(id: Long, note: String?) {
        db.update(
            "trips",
            ContentValues().apply { put("note", note) },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    fun trip(id: Long): Trip? =
        db.rawQuery(TRIP_SELECT + " WHERE t.id = ?", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) cursor.toTrip() else null
        }

    fun trips(limit: Int = 500): List<Trip> =
        db.rawQuery("$TRIP_SELECT ORDER BY t.started_at DESC LIMIT ?", arrayOf(limit.toString()))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTrip()) } }

    fun deleteTrip(id: Long) {
        db.delete("trips", "id = ?", arrayOf(id.toString()))
        deleteOrphanSegments()
    }

    /** Importing twice must not double every sample, so identity is mode + start + end. */
    fun tripExists(mode: TripMode, startedAt: Long, endedAt: Long?): Boolean =
        db.rawQuery(
            "SELECT 1 FROM trips WHERE mode = ? AND started_at = ? AND IFNULL(ended_at, 0) = ?",
            arrayOf(mode.id, startedAt.toString(), (endedAt ?: 0L).toString()),
        ).use { it.moveToFirst() }

    /**
     * Restores trips and legs from an export. Segments are re-created lazily,
     * only for legs that reference them, so a file never leaves a segment
     * behind with zero samples.
     */
    fun importBundle(bundle: ImportBundle): ImportResult = db.transaction {
        val definitions = bundle.segments.associateBy { it.mode.id to it.key }
        val resolved = HashMap<Pair<String, String>, Long>()
        var segmentsAdded = 0

        // Points come first: the segments restored below join on their names,
        // so the other order would show the old labels until the next analysis.
        val knownPoints = pointNames().toMutableMap()
        for (point in bundle.points) {
            if (knownPoints.containsKey(point.ref.key)) continue
            renamePoint(point.ref, point.name)
            knownPoints[point.ref.key] = point.name
        }
        // Roads chosen at points travel as structured lists, never as a
        // composite point name that could leak into a segment's name.
        for (roads in bundle.pointRoads) {
            setPointRoads(listOf(roads.ref), roads.roads)
        }
        for (place in bundle.junctions.ifEmpty { bundle.places }) {
            mergePlace(place.name, place.center, place.radiusM, place.points)
        }
        // v4 first-class nodes/roads
        for (road in bundle.roads) ensureRoad(road.name, road.source)
        for (node in bundle.nodes) {
            val at = if (node.lat != null && node.lon != null) LatLon(node.lat, node.lon) else null
            if (at != null) ensureNode(node.ref.key, at, 0L)
            node.name?.let { if (!knownPoints.containsKey(node.ref.key)) { renamePoint(node.ref, it); knownPoints[node.ref.key] = it } }
            val jName = node.junctionName
            if (jName != null) {
                val j = bundle.junctions.firstOrNull { it.name == jName }
                if (j != null) mergePlace(j.name, j.center, j.radiusM, listOf(node.ref))
                else if (at != null) mergePlace(jName, at, 30.0, listOf(node.ref))
            }
        }

        fun edgeFor(mode: TripMode, key: String?): Long? {
            if (key.isNullOrBlank()) return null
            val cacheKey = mode.id to key
            resolved[cacheKey]?.let { return it }
            val existing = edgeByKey(key, mode)
            val definition = definitions[cacheKey]
            if (existing != null) {
                val values = ContentValues()
                val defRoadName = definition?.roadName ?: definition?.userName
                if (existing.userName == null && definition?.userName != null) {
                    values.put("user_name", definition.userName)
                }
                if (existing.roadId == null && defRoadName != null) {
                    val roadId = ensureRoad(defRoadName, "user")
                    if (roadId > 0) values.put("road_id", roadId)
                }
                if (existing.fromNodeId == null && definition?.fromKey != null) {
                    ensureNode(definition.fromKey, existing.from, System.currentTimeMillis())?.let { values.put("from_node_id", it) }
                }
                if (existing.toNodeId == null && definition?.toKey != null) {
                    ensureNode(definition.toKey, existing.to, System.currentTimeMillis())?.let { values.put("to_node_id", it) }
                }
                if (values.size() > 0) db.update("edges", values, "id = ?", arrayOf(existing.id.toString()))
                resolved[cacheKey] = existing.id
                return existing.id
            }
            val id = definition?.let { segment ->
                segmentsAdded++
                val roadId = (segment.roadName ?: segment.userName)?.let { ensureRoad(it, "user") }?.takeIf { it > 0 }
                insertEdge(
                    key = segment.key,
                    mode = mode,
                    name = segment.name,
                    identity = segment.identity,
                    from = segment.from,
                    to = segment.to,
                    lengthM = segment.lengthM,
                    seenAt = System.currentTimeMillis(),
                    userName = segment.userName,
                    fromKey = segment.fromKey,
                    toKey = segment.toKey,
                    roadId = roadId,
                )
            } ?: return null
            resolved[cacheKey] = id
            return id
        }

        var tripsAdded = 0
        var tripsSkipped = 0
        var legsAdded = 0
        for (trip in bundle.trips) {
            if (tripExists(trip.mode, trip.startedAt, trip.endedAt)) {
                tripsSkipped++
                continue
            }
            val tripId = insert(
                "trips",
                null,
                ContentValues().apply {
                    put("mode", trip.mode.id)
                    put("started_at", trip.startedAt)
                    trip.endedAt?.let { put("ended_at", it) }
                    put("distance_m", trip.distanceM)
                    put("point_count", 0)
                    put("status", trip.status.name)
                    trip.note?.let { put("note", it) }
                },
            )
            trip.legs.forEachIndexed { index, leg ->
                insert(
                    "legs",
                    null,
                    ContentValues().apply {
                        put("trip_id", tripId)
                        put("seq", index)
                        put("start_ms", leg.startMs)
                        put("end_ms", leg.endMs)
                        put("distance_m", leg.distanceM)
                        edgeFor(trip.mode, leg.segmentKey)?.let { put("edge_id", it) }
                        put("direction", leg.direction)
                        leg.label?.let { put("label", it) }
                        put("partial", if (leg.partial) 1 else 0)
                        put("polyline", leg.polyline)
                        put("moving_ms", leg.movingMs)
                        put("stopped_ms", leg.stoppedMs)
                        put("stop_count", leg.stopCount)
                        put("longest_stop_ms", leg.longestStopMs)
                        put("signal_stop_count", leg.signalStopCount)
                        put("signal_wait_ms", leg.signalWaitMs)
                    },
                )
                legsAdded++
            }
            tripsAdded++
        }
        ImportResult(
            tripsAdded = tripsAdded,
            tripsSkipped = tripsSkipped,
            legsAdded = legsAdded,
            segmentsAdded = segmentsAdded,
        )
    }

    fun points(tripId: Long): List<TrackPoint> =
        db.rawQuery(
            "SELECT t, lat, lon, acc, speed, bearing FROM points WHERE trip_id = ? ORDER BY seq",
            arrayOf(tripId.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        TrackPoint(
                            t = cursor.getLong(0),
                            at = LatLon(cursor.getDouble(1), cursor.getDouble(2)),
                            accuracy = cursor.getDouble(3),
                            speed = cursor.getFloatOrNull(4),
                            bearing = cursor.getFloatOrNull(5),
                        ),
                    )
                }
            }
        }

    fun addMark(tripId: Long, t: Long) {
        db.insertWithOnConflict(
            "marks",
            null,
            ContentValues().apply {
                put("trip_id", tripId)
                put("t", t)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun marks(tripId: Long): List<Long> =
        db.rawQuery("SELECT t FROM marks WHERE trip_id = ? ORDER BY t", arrayOf(tripId.toString()))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

    /** Removes one manual split; the next analysis cuts this trip without it. */
    fun deleteMark(tripId: Long, t: Long) {
        db.delete("marks", "trip_id = ? AND t = ?", arrayOf(tripId.toString(), t.toString()))
    }

    fun lastPointTime(tripId: Long): Long? =
        db.rawQuery("SELECT MAX(t) FROM points WHERE trip_id = ?", arrayOf(tripId.toString()))
            .use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }

    /**
     * Drops the tail of a recording: keeps the first [keepCount] fixes by seq
     * and removes the rest, together with any manual marks that fell inside the
     * removed stretch. The analyzer rebuilds legs, distance and the trip's end
     * time from what remains, so callers must run it afterwards.
     */
    fun trimTripTail(tripId: Long, keepCount: Int): Int {
        if (keepCount < 1) return 0
        // First point to remove; none means the cut was past the last fix.
        val cutSeq = db.rawQuery(
            "SELECT seq FROM points WHERE trip_id = ? ORDER BY seq LIMIT 1 OFFSET ?",
            arrayOf(tripId.toString(), keepCount.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else return 0 }
        val keepLastT = db.rawQuery(
            "SELECT t FROM points WHERE trip_id = ? ORDER BY seq LIMIT 1 OFFSET ?",
            arrayOf(tripId.toString(), (keepCount - 1).toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else return 0 }

        var removed = 0
        db.transaction {
            removed = db.delete(
                "points",
                "trip_id = ? AND seq >= ?",
                arrayOf(tripId.toString(), cutSeq.toString()),
            )
            db.delete("marks", "trip_id = ? AND t > ?", arrayOf(tripId.toString(), keepLastT.toString()))
        }
        return removed
    }

    // ----------------------------------------------------------- legs

    fun replaceLegs(tripId: Long, legs: List<PendingLeg>) {
        db.transaction {
            delete("legs", "trip_id = ?", arrayOf(tripId.toString()))
            legs.forEachIndexed { index, leg ->
                insert(
                    "legs",
                    null,
                    ContentValues().apply {
                        put("trip_id", tripId)
                        put("seq", index)
                        put("start_ms", leg.startMs)
                        put("end_ms", leg.endMs)
                        put("distance_m", leg.distanceM)
                        leg.edgeId?.let { put("edge_id", it) }
                        put("direction", leg.direction)
                        leg.label?.let { put("label", it) }
                        put("partial", if (leg.partial) 1 else 0)
                        put("polyline", leg.polyline)
                        put("moving_ms", leg.movingMs)
                        put("stopped_ms", leg.stoppedMs)
                        put("stop_count", leg.stopCount)
                        put("longest_stop_ms", leg.longestStopMs)
                        put("signal_stop_count", leg.signalStopCount)
                        put("signal_wait_ms", leg.signalWaitMs)
                    },
                )
            }
            deleteOrphanEdges()
        }
    }

    fun legs(tripId: Long): List<Leg> =
        db.rawQuery("SELECT * FROM legs WHERE trip_id = ? ORDER BY seq", arrayOf(tripId.toString()))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toLeg()) } }

    fun legsForSegment(segmentId: Long): List<Leg> = legsForEdge(segmentId)

    fun legsForEdge(edgeId: Long): List<Leg> =
        db.rawQuery(
            "SELECT * FROM legs WHERE edge_id = ? ORDER BY start_ms",
            arrayOf(edgeId.toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toLeg()) } }

    /** Identity of many edges at once, for badging a trip's leg list. */
    fun segmentIdentities(ids: List<Long>): Map<Long, MatchKind> = edgeIdentities(ids)

    fun edgeIdentities(ids: List<Long>): Map<Long, MatchKind> {
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        return db.rawQuery(
            "SELECT id, identity FROM edges WHERE id IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray(),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getLong(0), MatchKind.of(cursor.getString(1)))
                }
            }
        }
    }

    // ----------------------------------------------------------- edges

    /**
     * Edges join their endpoint nodes so a single node name or junction name
     * renames every edge through it.
     */
    private val edgeSelect: String
        get() = """
            SELECT e.*,
                   nf.ref_kind AS from_ref_kind, nf.ref_node_id AS from_ref_node_id, nf.name AS from_node_name,
                   nt.ref_kind AS to_ref_kind, nt.ref_node_id AS to_ref_node_id, nt.name AS to_node_name,
                   nl_from.name AS from_name, nl_to.name AS to_name
              FROM edges e
              LEFT JOIN nodes nf ON nf.id = e.from_node_id
              LEFT JOIN nodes nt ON nt.id = e.to_node_id
              LEFT JOIN node_labels nl_from ON nl_from.node_id = e.from_node_id
              LEFT JOIN node_labels nl_to ON nl_to.node_id = e.to_node_id
        """.trimIndent()

    // Compatibility alias while callers migrate.
    private val segmentSelect: String get() = edgeSelect

    fun segment(id: Long): Segment? = edge(id)

    fun edge(id: Long): Edge? =
        db.rawQuery("$edgeSelect WHERE e.id = ?", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) cursor.toEdge() else null
        }

    fun segmentByKey(key: String, mode: TripMode): Segment? = edgeByKey(key, mode)

    fun edgeByKey(key: String, mode: TripMode): Edge? =
        db.rawQuery(
            "$edgeSelect WHERE e.key = ? AND e.mode = ?",
            arrayOf(key, mode.id),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toEdge() else null }

    fun insertSegment(
        key: String,
        mode: TripMode,
        name: String?,
        identity: MatchKind,
        from: LatLon,
        to: LatLon,
        lengthM: Double,
        seenAt: Long,
        userName: String? = null,
        fromKey: String? = null,
        toKey: String? = null,
    ): Long = insertEdge(key, mode, name, identity, from, to, lengthM, seenAt, userName, fromKey, toKey)

    fun insertEdge(
        key: String,
        mode: TripMode,
        name: String?,
        identity: MatchKind,
        from: LatLon,
        to: LatLon,
        lengthM: Double,
        seenAt: Long,
        userName: String? = null,
        fromKey: String? = null,
        toKey: String? = null,
        roadId: Long? = null,
    ): Long {
        val existing = edgeByKey(key, mode)
        val fromNodeId = ensureNode(fromKey, from, seenAt)
        val toNodeId = ensureNode(toKey, to, seenAt)
        if (existing != null) {
            val values = ContentValues().apply {
                put("last_seen", seenAt)
                if (name != null) put("name", name)
                put("length_m", lengthM)
                if (existing.fromNodeId == null && fromNodeId != null) put("from_node_id", fromNodeId)
                if (existing.toNodeId == null && toNodeId != null) put("to_node_id", toNodeId)
                if (existing.roadId == null && roadId != null) put("road_id", roadId)
            }
            db.update("edges", values, "id = ?", arrayOf(existing.id.toString()))
            return existing.id
        }
        return db.insert(
            "edges",
            null,
            ContentValues().apply {
                put("key", key)
                put("mode", mode.id)
                put("name", name)
                put("user_name", userName)
                put("identity", identity.name)
                put("a_lat", from.lat)
                put("a_lon", from.lon)
                put("b_lat", to.lat)
                put("b_lon", to.lon)
                put("length_m", lengthM)
                put("first_seen", seenAt)
                put("last_seen", seenAt)
                if (fromNodeId != null) put("from_node_id", fromNodeId)
                if (toNodeId != null) put("to_node_id", toNodeId)
                if (roadId != null) put("road_id", roadId)
            },
        )
    }

    /**
     * Gives an edge a user road name, or clears it. Legs carry a label copy for
     * display/export, so they are rewritten in the same transaction.
     */
    fun renameSegment(id: Long, name: String?) = renameEdge(id, name)

    fun renameEdge(id: Long, name: String?) {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        db.transaction {
            val roadId = trimmed?.let { ensureRoad(it, "user") }
            db.update(
                "edges",
                ContentValues().apply {
                    put("user_name", trimmed)
                    if (roadId != null) put("road_id", roadId) else putNull("road_id")
                },
                "id = ?",
                arrayOf(id.toString()),
            )
            val edge = edge(id) ?: return@transaction
            db.update(
                "legs",
                ContentValues().apply { put("label", edge.preferredLabel) },
                "edge_id = ?",
                arrayOf(id.toString()),
            )
        }
    }

    // ------------------------------------------------------------- points

    /** Every point the user has named, keyed by `kind:node`. */
    fun pointNames(): Map<String, String> =
        db.rawQuery(
            "SELECT ref_kind, ref_node_id, name FROM nodes WHERE name IS NOT NULL AND name NOT LIKE '%×%'",
            null,
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put("${cursor.getString(0)}:${cursor.getLong(1)}", cursor.getString(2))
                }
            }
        }

    /**
     * Effective point label: explicit node name, else junction name.
     */
    fun pointLabels(): Map<String, String> =
        db.rawQuery("SELECT kind, node_id, name FROM point_labels", null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put("${cursor.getString(0)}:${cursor.getLong(1)}", cursor.getString(2))
                }
            }
        }

    fun namedPoints(): List<NamedPoint> =
        db.rawQuery(
            "SELECT ref_kind, ref_node_id, name FROM nodes WHERE name IS NOT NULL AND name NOT LIKE '%×%' ORDER BY name",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(NamedPoint(PointRef(cursor.getString(0), cursor.getLong(1)), cursor.getString(2)))
                }
            }
        }

    /** Roads explicitly attached to points, keyed by `kind:node`. */
    fun pointRoads(): Map<String, List<String>> {
        val roads = HashMap<String, MutableList<String>>()
        db.rawQuery(
            """
            SELECT n.ref_kind, n.ref_node_id, r.name
              FROM node_roads nr
              JOIN nodes n ON n.id = nr.node_id
              JOIN roads r ON r.id = nr.road_id
             ORDER BY r.name
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val key = "${cursor.getString(0)}:${cursor.getLong(1)}"
                roads.getOrPut(key) { ArrayList() }.add(cursor.getString(2))
            }
        }
        return roads
    }

    /**
     * Replaces the roads chosen for these points. A crossing is a fact about
     * the point, so every member of a junction gets the same list.
     */
    fun setPointRoads(refs: Collection<PointRef>, roads: Collection<String>) {
        val chosen = roads.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val distinct = refs.distinctBy { it.key }
        if (distinct.isEmpty()) return
        db.transaction {
            for (ref in distinct) {
                val nodeId = ensureNode(ref.key, LatLon(0.0, 0.0), 0L) ?: continue
                db.delete("node_roads", "node_id = ?", arrayOf(nodeId.toString()))
                for (road in chosen) {
                    val roadId = ensureRoad(road, "user")
                    if (roadId <= 0) continue
                    db.insertWithOnConflict(
                        "node_roads",
                        null,
                        ContentValues().apply { put("node_id", nodeId); put("road_id", roadId) },
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                }
            }
        }
    }

    /**
     * Names a point, or clears it when [name] is blank. Relabels touching edges
     * in the same transaction.
     */
    fun renamePoint(ref: PointRef, name: String?) {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        db.transaction {
            val nodeId = ensureNode(ref.key, LatLon(0.0, 0.0), 0L)
            if (RoadNames.isComposite(trimmed)) {
                if (nodeId != null) {
                    db.update("nodes", ContentValues().apply { putNull("name") }, "id = ?", arrayOf(nodeId.toString()))
                }
                setPointRoads(listOf(ref), RoadNames.split(trimmed))
            } else if (nodeId != null) {
                db.update(
                    "nodes",
                    ContentValues().apply { if (trimmed == null) putNull("name") else put("name", trimmed) },
                    "id = ?",
                    arrayOf(nodeId.toString()),
                )
            }
            relabelEdgesTouching(listOf(ref.key))
        }
    }

    private fun relabelSegmentsTouching(keys: Collection<String>) = relabelEdgesTouching(keys)

    private fun relabelEdgesTouching(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val nodeIds = keys.mapNotNull { key ->
            val ref = PointRef.parse(key) ?: return@mapNotNull null
            db.rawQuery(
                "SELECT id FROM nodes WHERE ref_kind = ? AND ref_node_id = ?",
                arrayOf(ref.kind, ref.nodeId.toString()),
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }
        if (nodeIds.isEmpty()) return
        val placeholders = nodeIds.joinToString(",") { "?" }
        val args = nodeIds.map { it.toString() }.toTypedArray()
        val ids = ArrayList<Long>()
        db.rawQuery(
            "SELECT id FROM edges WHERE from_node_id IN ($placeholders) OR to_node_id IN ($placeholders)",
            args + args,
        ).use { cursor -> while (cursor.moveToNext()) ids += cursor.getLong(0) }
        for (id in ids) {
            val edge = edge(id) ?: continue
            db.update(
                "legs",
                ContentValues().apply { put("label", edge.preferredLabel) },
                "edge_id = ?",
                arrayOf(id.toString()),
            )
        }
    }

    // ------------------------------------------------------------- junctions/places

    /** Every junction the user has named, with the points under it. */
    fun places(): List<Place> {
        val members = HashMap<Long, MutableList<PointRef>>()
        db.rawQuery(
            """
            SELECT jn.junction_id, n.ref_kind, n.ref_node_id
              FROM junction_nodes jn JOIN nodes n ON n.id = jn.node_id
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                members.getOrPut(cursor.getLong(0)) { ArrayList() } +=
                    PointRef(cursor.getString(1), cursor.getLong(2))
            }
        }
        return db.rawQuery(
            "SELECT id, name, lat, lon, radius_m FROM junctions ORDER BY name",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    add(
                        Place(
                            id = id,
                            name = cursor.getString(1),
                            at = LatLon(cursor.getDouble(2), cursor.getDouble(3)),
                            radiusM = cursor.getDouble(4),
                            points = members[id].orEmpty(),
                        ),
                    )
                }
            }
        }
    }

    fun upsertPlace(
        id: Long?,
        name: String?,
        members: List<PointRef>,
        center: LatLon,
        radiusM: Double,
        clearMemberNames: Boolean = false,
        roads: Collection<String>? = null,
        absorbIds: List<Long> = emptyList(),
    ): Long? {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        var placeId = id
        db.transaction {
            val previous = if (id == null) emptyList() else placePoints(id)
            if (RoadNames.isComposite(trimmed)) {
                if (id != null) db.delete("junctions", "id = ?", arrayOf(id.toString()))
                setPointRoads(previous + members, RoadNames.split(trimmed))
                relabelEdgesTouching((previous + members).map { it.key })
                placeId = null
                return@transaction
            }
            if (trimmed == null) {
                if (id != null) db.delete("junctions", "id = ?", arrayOf(id.toString()))
                relabelEdgesTouching(previous.map { it.key })
                placeId = null
                return@transaction
            }
            val values = ContentValues().apply {
                put("name", trimmed)
                put("lat", center.lat)
                put("lon", center.lon)
                put("radius_m", radiusM)
            }
            placeId = if (id == null) {
                values.put("created_at", System.currentTimeMillis())
                db.insert("junctions", null, values)
            } else {
                db.update("junctions", values, "id = ?", arrayOf(id.toString()))
                id
            }
            val target = placeId
            for (otherId in absorbIds) {
                if (otherId != target) db.delete("junctions", "id = ?", arrayOf(otherId.toString()))
            }
            val memberRefs = members.distinctBy { it.key }
            if (clearMemberNames) {
                for (ref in memberRefs) {
                    val nodeId = ensureNode(ref.key, LatLon(0.0, 0.0), 0L) ?: continue
                    db.update("nodes", ContentValues().apply { putNull("name") }, "id = ?", arrayOf(nodeId.toString()))
                }
            }
            db.delete("junction_nodes", "junction_id = ?", arrayOf(target.toString()))
            db.execSQL("UPDATE nodes SET junction_id = NULL WHERE junction_id = ?", arrayOf(target.toString()))
            for (ref in memberRefs) {
                val nodeId = ensureNode(ref.key, center, 0L) ?: continue
                db.update("nodes", ContentValues().apply { put("junction_id", target) }, "id = ?", arrayOf(nodeId.toString()))
                db.insertWithOnConflict(
                    "junction_nodes",
                    null,
                    ContentValues().apply { put("junction_id", target); put("node_id", nodeId) },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            if (roads != null) setPointRoads(memberRefs, roads)
            relabelEdgesTouching((previous + memberRefs).map { it.key })
        }
        return placeId
    }

    fun mergePlace(name: String, center: LatLon, radiusM: Double, members: List<PointRef>): Long? {
        val trimmed = name.trim().takeIf { it.isNotEmpty() } ?: return null
        if (RoadNames.isComposite(trimmed)) {
            setPointRoads(members, RoadNames.split(trimmed))
            return null
        }
        val (existingId, existingRadius) = db.rawQuery(
            "SELECT id, radius_m FROM junctions WHERE name = ? LIMIT 1",
            arrayOf(trimmed),
        ).use { cursor ->
            if (cursor.moveToNext()) cursor.getLong(0) to cursor.getDouble(1) else null to 0.0
        }
        val merged = if (existingId == null) members else placePoints(existingId) + members
        return upsertPlace(
            id = existingId,
            name = trimmed,
            members = merged,
            center = center,
            radiusM = maxOf(radiusM, existingRadius),
        )
    }

    private fun placePoints(placeId: Long): List<PointRef> =
        db.rawQuery(
            """
            SELECT n.ref_kind, n.ref_node_id
              FROM junction_nodes jn JOIN nodes n ON n.id = jn.node_id
             WHERE jn.junction_id = ?
            """.trimIndent(),
            arrayOf(placeId.toString()),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(PointRef(cursor.getString(0), cursor.getLong(1))) }
        }

    fun adoptNewEndpoints() {
        val places = places()
        if (places.isEmpty()) return
        val owned = HashSet<String>()
        db.rawQuery(
            "SELECT n.ref_kind, n.ref_node_id FROM junction_nodes jn JOIN nodes n ON n.id = jn.node_id",
            null,
        ).use { c -> while (c.moveToNext()) owned += "${c.getString(0)}:${c.getLong(1)}" }
        db.rawQuery(
            "SELECT ref_kind, ref_node_id FROM nodes WHERE name IS NOT NULL",
            null,
        ).use { c -> while (c.moveToNext()) owned += "${c.getString(0)}:${c.getLong(1)}" }

        val positions = HashMap<String, LatLon>()
        val candidates = LinkedHashMap<String, LatLon>()
        db.rawQuery(
            """
            SELECT n.ref_kind, n.ref_node_id, n.lat, n.lon
              FROM nodes n
             WHERE EXISTS (SELECT 1 FROM edges e WHERE e.from_node_id = n.id OR e.to_node_id = n.id)
            """.trimIndent(),
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val key = "${c.getString(0)}:${c.getLong(1)}"
                val at = LatLon(c.getDouble(2), c.getDouble(3))
                positions.putIfAbsent(key, at)
                if (!owned.add(key)) continue
                candidates[key] = at
            }
        }
        if (candidates.isEmpty()) return

        for ((key, at) in candidates) {
            val ref = PointRef.parse(key) ?: continue
            val nearest = places.minByOrNull { Geo.distanceMeters(at, it.at) } ?: continue
            if (!PlaceDrafts.adopts(nearest, at) { member -> positions[member.key] }) continue
            val nodeId = ensureNode(ref.key, at, 0L) ?: continue
            db.update("nodes", ContentValues().apply { put("junction_id", nearest.id) }, "id = ?", arrayOf(nodeId.toString()))
            db.insertWithOnConflict(
                "junction_nodes",
                null,
                ContentValues().apply { put("junction_id", nearest.id); put("node_id", nodeId) },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            relabelEdgesTouching(listOf(ref.key))
        }
    }

    fun segments(mode: TripMode? = null): List<Segment> = edges(mode)

    fun edges(mode: TripMode? = null): List<Edge> {
        val (where, args) = if (mode == null) "" to emptyArray<String>() else "WHERE e.mode = ?" to arrayOf(mode.id)
        return db.rawQuery("$edgeSelect $where ORDER BY e.last_seen DESC", args)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toEdge()) } }
    }

    fun samples(segmentId: Long, includePartial: Boolean = false): List<TraversalSample> =
        db.rawQuery(
            "SELECT end_ms, start_ms, distance_m, direction, moving_ms, stopped_ms, stop_count, " +
                "signal_wait_ms, signal_stop_count FROM legs " +
                "WHERE edge_id = ?" + if (includePartial) "" else " AND partial = 0" +
                " ORDER BY start_ms",
            arrayOf(segmentId.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val end = cursor.getLong(0)
                    val start = cursor.getLong(1)
                    add(
                        TraversalSample(
                            durationMs = end - start,
                            distanceM = cursor.getDouble(2),
                            direction = cursor.getInt(3),
                            startedAt = start,
                            movingMs = cursor.getLong(4),
                            stoppedMs = cursor.getLong(5),
                            stopCount = cursor.getInt(6),
                            signalWaitMs = cursor.getLong(7),
                            signalStopCount = cursor.getInt(8),
                        ),
                    )
                }
            }
        }

    /** Samples of every edge of one mode, one query for the whole list screen. */
    fun samplesByMode(mode: TripMode, includePartial: Boolean = false): Map<Long, List<TraversalSample>> {
        val out = HashMap<Long, MutableList<TraversalSample>>()
        db.rawQuery(
            """
            SELECT l.edge_id, l.end_ms, l.start_ms, l.distance_m, l.direction,
                   l.moving_ms, l.stopped_ms, l.stop_count, l.signal_wait_ms, l.signal_stop_count
            FROM legs l JOIN edges e ON e.id = l.edge_id
            WHERE e.mode = ?${if (includePartial) "" else " AND l.partial = 0"}
            """.trimIndent(),
            arrayOf(mode.id),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val end = cursor.getLong(1)
                val start = cursor.getLong(2)
                out.getOrPut(id) { ArrayList() }
                    .add(
                        TraversalSample(
                            durationMs = end - start,
                            distanceM = cursor.getDouble(3),
                            direction = cursor.getInt(4),
                            startedAt = start,
                            movingMs = cursor.getLong(5),
                            stoppedMs = cursor.getLong(6),
                            stopCount = cursor.getInt(7),
                            signalWaitMs = cursor.getLong(8),
                            signalStopCount = cursor.getInt(9),
                        ),
                    )
            }
        }
        return out
    }

    fun sampleCounts(): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        db.rawQuery(
            "SELECT edge_id, COUNT(*) FROM legs WHERE edge_id IS NOT NULL AND partial = 0 GROUP BY edge_id",
            null,
        ).use { cursor -> while (cursor.moveToNext()) out[cursor.getLong(0)] = cursor.getInt(1) }
        return out
    }

    // ----------------------------------------------------------- geo nodes

    fun geoNodeNear(at: LatLon, radiusM: Double, now: Long): GeoNode? {
        val cellX = (at.lat / CELL_DEGREES).toInt()
        val cellY = (at.lon / CELL_DEGREES).toInt()
        val reach = 5
        var best: GeoNode? = null
        var bestDistance = radiusM
        db.rawQuery(
            "SELECT id, lat, lon, last_seen FROM geo_nodes WHERE cell_x BETWEEN ? AND ? AND cell_y BETWEEN ? AND ?",
            arrayOf((cellX - reach).toString(), (cellX + reach).toString(), (cellY - reach).toString(), (cellY + reach).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val node = GeoNode(cursor.getLong(0), LatLon(cursor.getDouble(1), cursor.getDouble(2)), cursor.getLong(3))
                val distance = Geo.distanceMeters(at, node.at)
                if (distance <= bestDistance) {
                    bestDistance = distance
                    best = node
                }
            }
        }
        best?.let { touchGeoNode(it.id, now) }
        return best
    }

    fun insertGeoNode(at: LatLon, now: Long): Long =
        db.insert(
            "geo_nodes",
            null,
            ContentValues().apply {
                put("lat", at.lat)
                put("lon", at.lon)
                put("cell_x", (at.lat / CELL_DEGREES).toInt())
                put("cell_y", (at.lon / CELL_DEGREES).toInt())
                put("last_seen", now)
            },
        )

    private fun touchGeoNode(id: Long, now: Long) {
        db.update("geo_nodes", ContentValues().apply { put("last_seen", now) }, "id = ?", arrayOf(id.toString()))
    }

    // -------------------------------------------------------------- nodes/roads

    fun roads(): List<Road> =
        db.rawQuery("SELECT id, name, source, osm_name FROM roads ORDER BY name", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Road(c.getLong(0), c.getString(1), c.getString(2) ?: "user", c.getStringOrNull(3)))
                }
            }
        }

    fun nodes(): List<Node> =
        db.rawQuery(
            "SELECT id, ref_kind, ref_node_id, lat, lon, name, junction_id FROM nodes ORDER BY id",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Node(
                            id = c.getLong(0),
                            ref = PointRef(c.getString(1), c.getLong(2)),
                            lat = c.getDouble(3),
                            lon = c.getDouble(4),
                            name = c.getStringOrNull(5),
                            junctionId = c.getLongOrNull(6),
                        ),
                    )
                }
            }
        }

    private fun ensureNode(refKey: String?, at: LatLon, seenAt: Long): Long? {
        val ref = PointRef.parse(refKey) ?: return null
        val existing = db.rawQuery(
            "SELECT id FROM nodes WHERE ref_kind = ? AND ref_node_id = ?",
            arrayOf(ref.kind, ref.nodeId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        if (existing != null) {
            db.update(
                "nodes",
                ContentValues().apply {
                    put("lat", at.lat)
                    put("lon", at.lon)
                    put("last_seen", seenAt)
                },
                "id = ?",
                arrayOf(existing.toString()),
            )
            return existing
        }
        return db.insert(
            "nodes",
            null,
            ContentValues().apply {
                put("ref_kind", ref.kind)
                put("ref_node_id", ref.nodeId)
                put("lat", at.lat)
                put("lon", at.lon)
                put("last_seen", seenAt)
            },
        ).takeIf { it > 0 }
    }

    private fun ensureRoad(name: String, source: String = "user", osmName: String? = null): Long {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return -1L
        val existing = db.rawQuery("SELECT id FROM roads WHERE name = ?", arrayOf(trimmed)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
        if (existing != null) return existing
        return db.insert(
            "roads",
            null,
            ContentValues().apply {
                put("name", trimmed)
                put("source", source)
                if (osmName != null) put("osm_name", osmName)
            },
        )
    }

    private fun deleteOrphanEdges() {
        db.execSQL("DELETE FROM edges WHERE id NOT IN (SELECT edge_id FROM legs WHERE edge_id IS NOT NULL)")
    }

    private fun deleteOrphanSegments() = deleteOrphanEdges()

    // -------------------------------------------------------------- export

    fun allSampleRows(): List<ExportSample> =
        db.rawQuery(
            """
            SELECT e.mode, e.key, e.name, l.start_ms, l.end_ms, l.distance_m, l.direction,
                   l.partial, l.moving_ms, l.stopped_ms, l.stop_count
            FROM legs l JOIN edges e ON e.id = l.edge_id
            ORDER BY l.start_ms
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ExportSample(
                            mode = cursor.getString(0),
                            key = cursor.getString(1),
                            name = cursor.getString(2),
                            startedAt = cursor.getLong(3),
                            durationMs = cursor.getLong(4) - cursor.getLong(3),
                            distanceM = cursor.getDouble(5),
                            direction = cursor.getInt(6),
                            partial = cursor.getInt(7) != 0,
                            movingMs = cursor.getLong(8),
                            stoppedMs = cursor.getLong(9),
                            stopCount = cursor.getInt(10),
                        ),
                    )
                }
            }
        }

    // --------------------------------------------------------- danger zone

    /** Fresh counts for the Danger Zone dialogs; never read from a cached list. */
    fun dangerCounts(): DangerCounts = DangerCounts(
        pointNames = countWhere("nodes", "name IS NOT NULL AND name NOT LIKE '%×%'"),
        places = count("junctions"),
        roadNames = count("node_roads") + countWhere("edges", "user_name IS NOT NULL"),
        trips = count("trips"),
    )

    /** Table names here are fixed literals owned by this class, never user input. */
    private fun count(table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun countWhere(table: String, where: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    fun clearPointNames(): Int {
        val keys = pointNames().keys
        if (keys.isEmpty()) return 0
        db.transaction {
            db.execSQL("UPDATE nodes SET name = NULL WHERE name IS NOT NULL")
            relabelEdgesTouching(keys)
        }
        return keys.size
    }

    fun clearPlaces(): Int {
        val removed = count("junctions")
        if (removed == 0) return 0
        val keys = db.rawQuery(
            "SELECT n.ref_kind, n.ref_node_id FROM junction_nodes jn JOIN nodes n ON n.id = jn.node_id",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add("${c.getString(0)}:${c.getLong(1)}") } }
        db.transaction {
            db.execSQL("UPDATE nodes SET junction_id = NULL WHERE junction_id IS NOT NULL")
            db.delete("junction_nodes", null, null)
            db.delete("junctions", null, null)
            relabelEdgesTouching(keys)
        }
        return removed
    }

    fun clearRoadNames(): Int {
        val pointRoads = count("node_roads")
        val edgeRoads = countWhere("edges", "user_name IS NOT NULL")
        val removed = pointRoads + edgeRoads
        if (removed == 0) return 0
        db.transaction {
            db.delete("node_roads", null, null)
            db.execSQL("UPDATE edges SET user_name = NULL, road_id = NULL WHERE user_name IS NOT NULL OR road_id IS NOT NULL")
            // Keep roads table rows that may still be referenced? No references after nulling.
            db.execSQL("DELETE FROM roads WHERE id NOT IN (SELECT road_id FROM edges WHERE road_id IS NOT NULL) AND id NOT IN (SELECT road_id FROM node_roads)")
        }
        return removed
    }

    fun clearTrips(): Int {
        val removed = count("trips")
        if (removed == 0) return 0
        db.transaction {
            db.delete("trips", null, null)
            deleteOrphanEdges()
        }
        return removed
    }

    fun clearAllData() {
        db.transaction {
            db.delete("junction_nodes", null, null)
            db.delete("junctions", null, null)
            db.delete("node_roads", null, null)
            db.delete("roads", null, null)
            db.execSQL("UPDATE nodes SET name = NULL, junction_id = NULL")
            db.delete("trips", null, null)
            db.delete("geo_nodes", null, null)
            db.delete("edges", null, null)
            db.delete("nodes", null, null)
        }
    }

    // ------------------------------------------------------------ mapping

    private fun Cursor.toTrip() = Trip(
        id = getLong(getColumnIndexOrThrow("id")),
        mode = TripMode.of(getString(getColumnIndexOrThrow("mode"))),
        startedAt = getLong(getColumnIndexOrThrow("started_at")),
        endedAt = getLongOrNull(getColumnIndexOrThrow("ended_at")),
        distanceM = getDouble(getColumnIndexOrThrow("distance_m")),
        pointCount = getInt(getColumnIndexOrThrow("point_count")),
        status = runCatching { TripStatus.valueOf(getString(getColumnIndexOrThrow("status"))) }
            .getOrDefault(TripStatus.FINISHED),
        note = getStringOrNull(getColumnIndexOrThrow("note")),
        legCount = getInt(getColumnIndexOrThrow("leg_count")),
    )

    private fun Cursor.toLeg() = Leg(
        id = getLong(getColumnIndexOrThrow("id")),
        tripId = getLong(getColumnIndexOrThrow("trip_id")),
        seq = getInt(getColumnIndexOrThrow("seq")),
        startMs = getLong(getColumnIndexOrThrow("start_ms")),
        endMs = getLong(getColumnIndexOrThrow("end_ms")),
        distanceM = getDouble(getColumnIndexOrThrow("distance_m")),
        edgeId = getLongOrNull(getColumnIndexOrThrow("edge_id")),
        direction = getInt(getColumnIndexOrThrow("direction")),
        label = getStringOrNull(getColumnIndexOrThrow("label")),
        partial = getInt(getColumnIndexOrThrow("partial")) != 0,
        polyline = getString(getColumnIndexOrThrow("polyline")),
        movingMs = getLong(getColumnIndexOrThrow("moving_ms")),
        stoppedMs = getLong(getColumnIndexOrThrow("stopped_ms")),
        stopCount = getInt(getColumnIndexOrThrow("stop_count")),
        longestStopMs = getLong(getColumnIndexOrThrow("longest_stop_ms")),
        signalStopCount = getInt(getColumnIndexOrThrow("signal_stop_count")),
        signalWaitMs = getLong(getColumnIndexOrThrow("signal_wait_ms")),
    )

    private fun Cursor.toEdge(): Edge {
        val fromRefKind = getStringOrNull(getColumnIndexOrThrow("from_ref_kind"))
        val fromRefNodeId = getLongOrNull(getColumnIndexOrThrow("from_ref_node_id"))
        val toRefKind = getStringOrNull(getColumnIndexOrThrow("to_ref_kind"))
        val toRefNodeId = getLongOrNull(getColumnIndexOrThrow("to_ref_node_id"))
        val fromKey = if (fromRefKind != null && fromRefNodeId != null) "$fromRefKind:$fromRefNodeId" else null
        val toKey = if (toRefKind != null && toRefNodeId != null) "$toRefKind:$toRefNodeId" else null
        return Edge(
            id = getLong(getColumnIndexOrThrow("id")),
            key = getString(getColumnIndexOrThrow("key")),
            mode = TripMode.of(getString(getColumnIndexOrThrow("mode"))),
            name = getStringOrNull(getColumnIndexOrThrow("name")),
            identity = MatchKind.of(getStringOrNull(getColumnIndexOrThrow("identity"))),
            aLat = getDouble(getColumnIndexOrThrow("a_lat")),
            aLon = getDouble(getColumnIndexOrThrow("a_lon")),
            bLat = getDouble(getColumnIndexOrThrow("b_lat")),
            bLon = getDouble(getColumnIndexOrThrow("b_lon")),
            lengthM = getDouble(getColumnIndexOrThrow("length_m")),
            firstSeen = getLong(getColumnIndexOrThrow("first_seen")),
            lastSeen = getLong(getColumnIndexOrThrow("last_seen")),
            userName = getStringOrNull(getColumnIndexOrThrow("user_name")),
            fromKey = fromKey,
            toKey = toKey,
            fromName = getStringOrNull(getColumnIndexOrThrow("from_name")),
            toName = getStringOrNull(getColumnIndexOrThrow("to_name")),
            fromNodeId = getLongOrNull(getColumnIndexOrThrow("from_node_id")),
            toNodeId = getLongOrNull(getColumnIndexOrThrow("to_node_id")),
            roadId = getLongOrNull(getColumnIndexOrThrow("road_id")),
        )
    }

    private fun Cursor.toSegment(): Segment = toEdge()

    private fun Cursor.getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

    private fun Cursor.getFloatOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    companion object {
        private const val CELL_DEGREES = 0.0001

        private const val TRIP_SELECT =
            "SELECT t.*, (SELECT COUNT(*) FROM legs l WHERE l.trip_id = t.id) AS leg_count FROM trips t"
    }
}

data class ImportResult(
    val tripsAdded: Int,
    val tripsSkipped: Int,
    val legsAdded: Int,
    val segmentsAdded: Int,
)

data class ExportSample(    val mode: String,
    val key: String,
    val name: String?,
    val startedAt: Long,
    val durationMs: Long,
    val distanceM: Double,
    val direction: Int,
    val partial: Boolean,
    val movingMs: Long,
    val stoppedMs: Long,
    val stopCount: Int,
)
