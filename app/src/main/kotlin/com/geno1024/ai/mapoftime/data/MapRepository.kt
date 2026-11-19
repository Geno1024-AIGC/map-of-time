package com.geno1024.ai.mapoftime.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
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

        fun segmentFor(mode: TripMode, key: String?): Long? {
            if (key.isNullOrBlank()) return null
            val cacheKey = mode.id to key
            resolved[cacheKey]?.let { return it }
            val existing = segmentByKey(key, mode)
            val definition = definitions[cacheKey]
            if (existing != null) {
                // Restore a name the user gave the segment, but never replace a
                // local one with what the file happened to say.
                val values = ContentValues()
                if (existing.userName == null && definition?.userName != null) {
                    values.put("user_name", definition.userName)
                }
                if (existing.fromKey == null && definition?.fromKey != null) {
                    values.put("from_key", definition.fromKey)
                }
                if (existing.toKey == null && definition?.toKey != null) {
                    values.put("to_key", definition.toKey)
                }
                if (values.size() > 0) {
                    db.update("segments", values, "id = ?", arrayOf(existing.id.toString()))
                }
                resolved[cacheKey] = existing.id
                return existing.id
            }
            val id = definition?.let { segment ->
                segmentsAdded++
                insertSegment(
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
                        segmentFor(trip.mode, leg.segmentKey)?.let { put("segment_id", it) }
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

    fun lastPointTime(tripId: Long): Long? =
        db.rawQuery("SELECT MAX(t) FROM points WHERE trip_id = ?", arrayOf(tripId.toString()))
            .use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }

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
                        leg.segmentId?.let { put("segment_id", it) }
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
            // Re-analysing cuts the same trip differently, so any segment that
            // only the old cut used is now referenced by nothing. Left behind,
            // it would sit in the segment list with zero samples.
            deleteOrphanSegments()
        }
    }

    fun legs(tripId: Long): List<Leg> =
        db.rawQuery("SELECT * FROM legs WHERE trip_id = ? ORDER BY seq", arrayOf(tripId.toString()))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toLeg()) } }

    fun legsForSegment(segmentId: Long): List<Leg> =
        db.rawQuery(
            "SELECT * FROM legs WHERE segment_id = ? ORDER BY start_ms",
            arrayOf(segmentId.toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toLeg()) } }

    /** Identity of many segments at once, for badging a trip's leg list. */
    fun segmentIdentities(ids: List<Long>): Map<Long, MatchKind> {
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        return db.rawQuery(
            "SELECT id, identity FROM segments WHERE id IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray(),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getLong(0), MatchKind.of(cursor.getString(1)))
                }
            }
        }
    }

    // ----------------------------------------------------------- segments

    /**
     * Segments carry the names of their two ends so a single point name can
     * rename every segment through it. The join is on the full point key; the
     * name table is tiny, so the concatenation costs nothing worth avoiding.
     */
    private val segmentSelect: String
        get() = "SELECT s.*, pf.name AS from_name, pt.name AS to_name FROM segments s " +
            "LEFT JOIN point_names pf ON pf.kind || ':' || pf.node_id = s.from_key " +
            "LEFT JOIN point_names pt ON pt.kind || ':' || pt.node_id = s.to_key"

    fun segment(id: Long): Segment? =
        db.rawQuery("$segmentSelect WHERE s.id = ?", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) cursor.toSegment() else null
        }

    fun segmentByKey(key: String, mode: TripMode): Segment? =
        db.rawQuery(
            "$segmentSelect WHERE s.key = ? AND s.mode = ?",
            arrayOf(key, mode.id),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toSegment() else null }

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
    ): Long {
        val existing = segmentByKey(key, mode)
        if (existing != null) {
            val values = ContentValues().apply {
                put("last_seen", seenAt)
                if (name != null) put("name", name)
                put("length_m", lengthM)
                // Earlier versions did not record which points the ends are;
                // a re-analysis can fill them in without touching anything else.
                if (existing.fromKey == null && fromKey != null) put("from_key", fromKey)
                if (existing.toKey == null && toKey != null) put("to_key", toKey)
            }
            db.update("segments", values, "id = ?", arrayOf(existing.id.toString()))
            return existing.id
        }
        return db.insert(
            "segments",
            null,
            ContentValues().apply {
                put("key", key)
                put("mode", mode.id)
                put("name", name)
                put("user_name", userName)
                put("from_key", fromKey)
                put("to_key", toKey)
                put("identity", identity.name)
                put("a_lat", from.lat)
                put("a_lon", from.lon)
                put("b_lat", to.lat)
                put("b_lon", to.lon)
                put("length_m", lengthM)
                put("first_seen", seenAt)
                put("last_seen", seenAt)
            },
        )
    }

    /**
     * Gives a segment a name of the user's own, or clears it so the point pair
     * or OSM's name is used again. Legs carry a copy of the label for display
     * and export, so they are rewritten in the same transaction — otherwise the
     * trip list would keep showing the old name until the next analysis.
     */
    fun renameSegment(id: Long, name: String?) {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        db.transaction {
            db.update(
                "segments",
                ContentValues().apply { put("user_name", trimmed) },
                "id = ?",
                arrayOf(id.toString()),
            )
            val segment = segment(id) ?: return@transaction
            db.update(
                "legs",
                ContentValues().apply { put("label", segment.preferredLabel) },
                "segment_id = ?",
                arrayOf(id.toString()),
            )
        }
    }

    // ------------------------------------------------------------- points

    /** Every point the user has named, keyed by `kind:node`. */
    fun pointNames(): Map<String, String> =
        db.rawQuery("SELECT kind, node_id, name FROM point_names", null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put("${cursor.getString(0)}:${cursor.getLong(1)}", cursor.getString(2))
                }
            }
        }

    fun namedPoints(): List<NamedPoint> =
        db.rawQuery("SELECT kind, node_id, name FROM point_names ORDER BY name", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(NamedPoint(PointRef(cursor.getString(0), cursor.getLong(1)), cursor.getString(2)))
                }
            }
        }

    /**
     * Names a point, or clears it when [name] is blank. Every segment that ends
     * at this point is relabelled in the same transaction, so the list, the
     * map and the exported legs agree without waiting for a re-analysis.
     */
    fun renamePoint(ref: PointRef, name: String?) {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        db.transaction {
            if (trimmed == null) {
                db.delete("point_names", "kind = ? AND node_id = ?", arrayOf(ref.kind, ref.nodeId.toString()))
            } else {
                db.insertWithOnConflict(
                    "point_names",
                    null,
                    ContentValues().apply {
                        put("kind", ref.kind)
                        put("node_id", ref.nodeId)
                        put("name", trimmed)
                        put("named_at", System.currentTimeMillis())
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            val ids = ArrayList<Long>()
            db.rawQuery(
                "SELECT id FROM segments WHERE from_key = ? OR to_key = ?",
                arrayOf(ref.key, ref.key),
            ).use { cursor ->
                while (cursor.moveToNext()) ids += cursor.getLong(0)
            }
            for (id in ids) {
                val segment = segment(id) ?: continue
                db.update(
                    "legs",
                    ContentValues().apply { put("label", segment.preferredLabel) },
                    "segment_id = ?",
                    arrayOf(id.toString()),
                )
            }
        }
    }

    fun segments(mode: TripMode? = null): List<Segment> {
        val (where, args) = if (mode == null) "" to emptyArray<String>() else "WHERE s.mode = ?" to arrayOf(mode.id)
        return db.rawQuery("$segmentSelect $where ORDER BY s.last_seen DESC", args)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toSegment()) } }
    }

    fun samples(segmentId: Long, includePartial: Boolean = false): List<TraversalSample> =
        db.rawQuery(
            "SELECT end_ms, start_ms, distance_m, direction, moving_ms, stopped_ms, stop_count, " +
                "signal_wait_ms, signal_stop_count FROM legs " +
                "WHERE segment_id = ?" + if (includePartial) "" else " AND partial = 0" +
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

    /** Samples of every segment of one mode, one query for the whole list screen. */
    fun samplesByMode(mode: TripMode, includePartial: Boolean = false): Map<Long, List<TraversalSample>> {
        val out = HashMap<Long, MutableList<TraversalSample>>()
        db.rawQuery(
            """
            SELECT l.segment_id, l.end_ms, l.start_ms, l.distance_m, l.direction,
                   l.moving_ms, l.stopped_ms, l.stop_count, l.signal_wait_ms, l.signal_stop_count
            FROM legs l JOIN segments s ON s.id = l.segment_id
            WHERE s.mode = ?${if (includePartial) "" else " AND l.partial = 0"}
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
            "SELECT segment_id, COUNT(*) FROM legs WHERE segment_id IS NOT NULL AND partial = 0 GROUP BY segment_id",
            null,
        ).use { cursor -> while (cursor.moveToNext()) out[cursor.getLong(0)] = cursor.getInt(1) }
        return out
    }

    private fun deleteOrphanSegments() {
        db.execSQL("DELETE FROM segments WHERE id NOT IN (SELECT segment_id FROM legs WHERE segment_id IS NOT NULL)")
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

    // -------------------------------------------------------------- export

    fun allSampleRows(): List<ExportSample> =
        db.rawQuery(
            """
            SELECT s.mode, s.key, s.name, l.start_ms, l.end_ms, l.distance_m, l.direction,
                   l.partial, l.moving_ms, l.stopped_ms, l.stop_count
            FROM legs l JOIN segments s ON s.id = l.segment_id
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
        segmentId = getLongOrNull(getColumnIndexOrThrow("segment_id")),
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

    private fun Cursor.toSegment() = Segment(
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
        fromKey = getStringOrNull(getColumnIndexOrThrow("from_key")),
        toKey = getStringOrNull(getColumnIndexOrThrow("to_key")),
        fromName = getStringOrNull(getColumnIndexOrThrow("from_name")),
        toName = getStringOrNull(getColumnIndexOrThrow("to_name")),
    )

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
