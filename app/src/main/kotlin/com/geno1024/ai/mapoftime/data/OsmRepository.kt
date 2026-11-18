package com.geno1024.ai.mapoftime.data

import android.content.ContentValues
import com.geno1024.ai.mapoftime.core.Bounds
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.osm.OsmWay
import com.geno1024.ai.mapoftime.osm.OverpassClient
import kotlin.math.floor

/**
 * Caches Overpass answers per ~2 km tile, so a repeated walk costs no network
 * and a trip recorded underground can still be matched later.
 *
 * Fetching is best effort: when a tile cannot be downloaded the caller simply
 * gets no road data for that area and falls back to local corner clustering.
 */
class OsmRepository(
    private val db: MapDb,
    private val client: OverpassClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** True when every tile of [bounds] is cached and younger than [maxAgeMs]. */
    fun isCovered(bounds: Bounds, maxAgeMs: Long): Boolean {
        val now = clock()
        for (tile in tilesFor(bounds)) {
            val fetchedAt = tileFetchedAt(tile) ?: return false
            if (now - fetchedAt > maxAgeMs) return false
        }
        return true
    }

    /**
     * Returns the ways that cross [bounds], downloading missing tiles when
     * [online] is true. `null` means "no map data here at all", which the
     * analyzer treats as a request to use the geometric fallback.
     */
    fun waysFor(bounds: Bounds, maxAgeMs: Long, online: Boolean = true): List<OsmWay>? {
        val tiles = tilesFor(bounds)
        if (tiles.size > MAX_TILES) return null

        var fetchedAny = false
        for (tile in tiles) {
            val fresh = tileFetchedAt(tile)?.let { clock() - it <= maxAgeMs } == true
            if (fresh) continue
            if (!online) continue
            val fetched = runCatching { fetchTile(tile) }.getOrNull() ?: continue
            fetchedAny = true
            if (fetched) Thread.sleep(TILE_PAUSE_MS)
        }

        val ways = loadTiles(tiles)
        return if (ways.isEmpty() && !fetchedAny) null else ways
    }

    fun clear() {
        val writable = db.writableDatabase
        writable.delete("osm_way_tiles", null, null)
        writable.delete("osm_ways", null, null)
        writable.delete("osm_tiles", null, null)
    }

    fun cachedTileCount(): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM osm_tiles", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /** Returns true when the tile was actually stored. */
    private fun fetchTile(tile: String): Boolean {
        val bounds = tileBounds(tile).expanded(TILE_MARGIN_METERS)
        val ways = client.fetchWays(bounds)
        val writable = db.writableDatabase
        writable.beginTransaction()
        try {
            for (way in ways) {
                writable.insertWithOnConflict(
                    "osm_ways",
                    null,
                    ContentValues().apply {
                        put("way_id", way.id)
                        put("highway", way.highway)
                        put("name", way.name)
                        put("ref", way.ref)
                        put("oneway", if (way.oneway) 1 else 0)
                        put("node_ids", way.nodeIds.joinToString(","))
                        put("coords", way.points.joinToString(";") { "${it.lat},${it.lon}" })
                    },
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                )
                writable.insertWithOnConflict(
                    "osm_way_tiles",
                    null,
                    ContentValues().apply {
                        put("tile", tile)
                        put("way_id", way.id)
                    },
                    android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            writable.insertWithOnConflict(
                "osm_tiles",
                null,
                ContentValues().apply {
                    put("tile", tile)
                    put("fetched_at", clock())
                    put("way_count", ways.size)
                },
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
            )
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
        return true
    }

    private fun tileFetchedAt(tile: String): Long? =
        db.readableDatabase.rawQuery("SELECT fetched_at FROM osm_tiles WHERE tile = ?", arrayOf(tile))
            .use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun loadTiles(tiles: List<String>): List<OsmWay> {
        if (tiles.isEmpty()) return emptyList()
        val placeholders = tiles.joinToString(",") { "?" }
        val ways = ArrayList<OsmWay>()
        db.readableDatabase.rawQuery(
            """
            SELECT DISTINCT w.way_id, w.highway, w.name, w.ref, w.oneway, w.node_ids, w.coords
            FROM osm_ways w JOIN osm_way_tiles t ON t.way_id = w.way_id
            WHERE t.tile IN ($placeholders)
            """.trimIndent(),
            tiles.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val nodeIds = cursor.getString(5).split(',').mapNotNull { it.toLongOrNull() }
                val points = cursor.getString(6).split(';').mapNotNull { pair ->
                    val comma = pair.indexOf(',')
                    if (comma <= 0) return@mapNotNull null
                    LatLon(pair.substring(0, comma).toDouble(), pair.substring(comma + 1).toDouble())
                }
                if (nodeIds.size != points.size || points.size < 2) continue
                ways += OsmWay(
                    id = cursor.getLong(0),
                    highway = cursor.getString(1) ?: "road",
                    name = cursor.getString(2),
                    ref = cursor.getString(3),
                    oneway = cursor.getInt(4) != 0,
                    nodeIds = nodeIds,
                    points = points,
                )
            }
        }
        return ways
    }

    private fun tilesFor(bounds: Bounds): List<String> {
        val minX = floor(bounds.minLon / TILE_DEGREES).toInt()
        val maxX = floor(bounds.maxLon / TILE_DEGREES).toInt()
        val minY = floor(bounds.minLat / TILE_DEGREES).toInt()
        val maxY = floor(bounds.maxLat / TILE_DEGREES).toInt()
        val out = ArrayList<String>((maxX - minX + 1) * (maxY - minY + 1))
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                out += "$y:$x"
            }
        }
        return out
    }

    private fun tileBounds(tile: String): Bounds {
        val parts = tile.split(':')
        val y = parts[0].toInt()
        val x = parts[1].toInt()
        return Bounds(
            minLat = y * TILE_DEGREES,
            minLon = x * TILE_DEGREES,
            maxLat = (y + 1) * TILE_DEGREES,
            maxLon = (x + 1) * TILE_DEGREES,
        )
    }

    companion object {
        /** ~2.2 km north-south; a city tile is a few thousand ways. */
        const val TILE_DEGREES = 0.02
        private const val TILE_MARGIN_METERS = 250.0
        private const val TILE_PAUSE_MS = 1_000L
        private const val MAX_TILES = 16
    }
}
