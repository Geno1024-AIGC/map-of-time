package com.geno1024.ai.mapoftime.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
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
        db.beginTransaction()
        try {
            db.delete("legs", "trip_id = ?", arrayOf(tripId.toString()))
            legs.forEachIndexed { index, leg ->
                db.insert(
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
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
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

    // ----------------------------------------------------------- segments

    fun segment(id: Long): Segment? =
        db.rawQuery("SELECT * FROM segments WHERE id = ?", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) cursor.toSegment() else null
        }

    fun segmentByKey(key: String, mode: TripMode): Segment? =
        db.rawQuery(
            "SELECT * FROM segments WHERE key = ? AND mode = ?",
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
    ): Long {
        val existing = segmentByKey(key, mode)
        if (existing != null) {
            val values = ContentValues().apply {
                put("last_seen", seenAt)
                if (name != null) put("name", name)
                put("length_m", lengthM)
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

    fun segments(mode: TripMode? = null): List<Segment> {
        val (where, args) = if (mode == null) "" to emptyArray<String>() else "WHERE mode = ?" to arrayOf(mode.id)
        return db.rawQuery("SELECT * FROM segments $where ORDER BY last_seen DESC", args)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toSegment()) } }
    }

    fun samples(segmentId: Long): List<TraversalSample> =
        db.rawQuery(
            "SELECT end_ms, start_ms, distance_m, direction FROM legs WHERE segment_id = ? ORDER BY start_ms",
            arrayOf(segmentId.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val end = cursor.getLong(0)
                    val start = cursor.getLong(1)
                    add(TraversalSample(end - start, cursor.getDouble(2), cursor.getInt(3), start))
                }
            }
        }

    /** Samples of every segment of one mode, one query for the whole list screen. */
    fun samplesByMode(mode: TripMode): Map<Long, List<TraversalSample>> {
        val out = HashMap<Long, MutableList<TraversalSample>>()
        db.rawQuery(
            """
            SELECT l.segment_id, l.end_ms, l.start_ms, l.distance_m, l.direction
            FROM legs l JOIN segments s ON s.id = l.segment_id
            WHERE s.mode = ? AND l.partial = 0
            """.trimIndent(),
            arrayOf(mode.id),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val end = cursor.getLong(1)
                val start = cursor.getLong(2)
                out.getOrPut(id) { ArrayList() }
                    .add(TraversalSample(end - start, cursor.getDouble(3), cursor.getInt(4), start))
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
            SELECT s.mode, s.key, s.name, l.start_ms, l.end_ms, l.distance_m, l.direction, l.partial
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

data class ExportSample(
    val mode: String,
    val key: String,
    val name: String?,
    val startedAt: Long,
    val durationMs: Long,
    val distanceM: Double,
    val direction: Int,
    val partial: Boolean,
)
