package com.geno1024.ai.mapoftime.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * One database for everything the app owns. Hand-written SQL instead of an ORM
 * keeps the schema visible and keeps the build free of annotation processors.
 */
class MapDb(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE trips(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                mode TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                distance_m REAL NOT NULL DEFAULT 0,
                point_count INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                note TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE points(
                trip_id INTEGER NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
                seq INTEGER NOT NULL,
                t INTEGER NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                acc REAL NOT NULL,
                speed REAL,
                bearing REAL,
                PRIMARY KEY(trip_id, seq)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE marks(
                trip_id INTEGER NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
                t INTEGER NOT NULL,
                PRIMARY KEY(trip_id, t)
            )
            """.trimIndent(),
        )

        // ---- First-class junction grouping (was places) --------------------
        db.execSQL(
            """
            CREATE TABLE junctions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                radius_m REAL NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        // ---- First-class nodes ---------------------------------------------
        db.execSQL(
            """
            CREATE TABLE nodes(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ref_kind TEXT NOT NULL,
                ref_node_id INTEGER NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                name TEXT,
                junction_id INTEGER REFERENCES junctions(id) ON DELETE SET NULL,
                last_seen INTEGER NOT NULL DEFAULT 0,
                UNIQUE(ref_kind, ref_node_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE junction_nodes(
                junction_id INTEGER NOT NULL REFERENCES junctions(id) ON DELETE CASCADE,
                node_id INTEGER NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
                PRIMARY KEY(node_id)
            )
            """.trimIndent(),
        )

        // ---- First-class roads ---------------------------------------------
        db.execSQL(
            """
            CREATE TABLE roads(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                source TEXT NOT NULL DEFAULT 'user',
                osm_name TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE node_roads(
                node_id INTEGER NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
                road_id INTEGER NOT NULL REFERENCES roads(id) ON DELETE CASCADE,
                PRIMARY KEY(node_id, road_id)
            )
            """.trimIndent(),
        )

        // ---- First-class traversable edges (was segments) ------------------
        db.execSQL(
            """
            CREATE TABLE edges(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                key TEXT NOT NULL,
                mode TEXT NOT NULL,
                name TEXT,
                user_name TEXT,
                identity TEXT NOT NULL,
                a_lat REAL NOT NULL,
                a_lon REAL NOT NULL,
                b_lat REAL NOT NULL,
                b_lon REAL NOT NULL,
                length_m REAL NOT NULL,
                first_seen INTEGER NOT NULL,
                last_seen INTEGER NOT NULL,
                from_node_id INTEGER REFERENCES nodes(id) ON DELETE SET NULL,
                to_node_id INTEGER REFERENCES nodes(id) ON DELETE SET NULL,
                road_id INTEGER REFERENCES roads(id) ON DELETE SET NULL,
                UNIQUE(key, mode)
            )
            """.trimIndent(),
        )

        // Effective node label: explicit node name wins, else junction name.
        db.execSQL(
            """
            CREATE VIEW node_labels AS
                SELECT n.id AS node_id, n.ref_kind, n.ref_node_id,
                       COALESCE(n.name, j.name) AS name
                  FROM nodes n
                  LEFT JOIN junctions j ON j.id = n.junction_id
                 WHERE COALESCE(n.name, j.name) IS NOT NULL
                   AND COALESCE(n.name, j.name) NOT LIKE '%×%'
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE legs(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                trip_id INTEGER NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
                seq INTEGER NOT NULL,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                distance_m REAL NOT NULL,
                edge_id INTEGER REFERENCES edges(id) ON DELETE SET NULL,
                direction INTEGER NOT NULL DEFAULT 0,
                label TEXT,
                partial INTEGER NOT NULL DEFAULT 0,
                polyline TEXT NOT NULL,
                moving_ms INTEGER NOT NULL DEFAULT 0,
                stopped_ms INTEGER NOT NULL DEFAULT 0,
                stop_count INTEGER NOT NULL DEFAULT 0,
                longest_stop_ms INTEGER NOT NULL DEFAULT 0,
                signal_stop_count INTEGER NOT NULL DEFAULT 0,
                signal_wait_ms INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE geo_nodes(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                cell_x INTEGER NOT NULL,
                cell_y INTEGER NOT NULL,
                last_seen INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE osm_tiles(
                tile TEXT PRIMARY KEY,
                fetched_at INTEGER NOT NULL,
                way_count INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE osm_ways(
                way_id INTEGER PRIMARY KEY,
                highway TEXT,
                name TEXT,
                ref TEXT,
                oneway INTEGER NOT NULL,
                node_ids TEXT NOT NULL,
                coords TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE osm_way_tiles(
                tile TEXT NOT NULL,
                way_id INTEGER NOT NULL,
                PRIMARY KEY(tile, way_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE osm_signals(
                signal_id INTEGER NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                tile TEXT NOT NULL,
                PRIMARY KEY(signal_id, tile)
            )
            """.trimIndent(),
        )

        db.execSQL("CREATE INDEX idx_points_trip ON points(trip_id, t)")
        db.execSQL("CREATE INDEX idx_nodes_ref ON nodes(ref_kind, ref_node_id)")
        db.execSQL("CREATE INDEX idx_nodes_junction ON nodes(junction_id)")
        db.execSQL("CREATE INDEX idx_junction_nodes_junction ON junction_nodes(junction_id)")
        db.execSQL("CREATE INDEX idx_node_roads_road ON node_roads(road_id)")
        db.execSQL("CREATE INDEX idx_edges_mode ON edges(mode)")
        db.execSQL("CREATE INDEX idx_edges_from_node ON edges(from_node_id)")
        db.execSQL("CREATE INDEX idx_edges_to_node ON edges(to_node_id)")
        db.execSQL("CREATE INDEX idx_edges_road ON edges(road_id)")
        db.execSQL("CREATE INDEX idx_legs_trip ON legs(trip_id, seq)")
        db.execSQL("CREATE INDEX idx_legs_edge ON legs(edge_id)")
        db.execSQL("CREATE INDEX idx_geo_nodes_cell ON geo_nodes(cell_x, cell_y)")
        db.execSQL("CREATE INDEX idx_osm_way_tiles_tile ON osm_way_tiles(tile)")
        db.execSQL("CREATE INDEX idx_osm_signals_tile ON osm_signals(tile)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // New leg columns only: the trip recordings are the user's data and the
        // legs are derived, so there is nothing here worth dropping.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE legs ADD COLUMN moving_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE legs ADD COLUMN stopped_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE legs ADD COLUMN stop_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE legs ADD COLUMN longest_stop_ms INTEGER NOT NULL DEFAULT 0")
            // Older legs never measured the split, so their whole duration is
            // travel. That keeps totals right and claims no wait we never saw.
            db.execSQL("UPDATE legs SET moving_ms = end_ms - start_ms")
        }
        // Traffic lights arrived with v3; the OSM tables are a cache, so they
        // are created in place instead of being thrown away.
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE legs ADD COLUMN signal_stop_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE legs ADD COLUMN signal_wait_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS osm_signals(
                    signal_id INTEGER NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    tile TEXT NOT NULL,
                    PRIMARY KEY(signal_id, tile)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_osm_signals_tile ON osm_signals(tile)")
        }
        // v4 keeps a name the user gave a segment apart from the OSM name, so
        // re-analysis can refresh the latter without losing the former.
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE segments ADD COLUMN user_name TEXT")
        }
        // v5 names the points a segment connects, so one name covers every
        // segment through that junction.
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE segments ADD COLUMN from_key TEXT")
            db.execSQL("ALTER TABLE segments ADD COLUMN to_key TEXT")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS point_names(
                    kind TEXT NOT NULL,
                    node_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    named_at INTEGER NOT NULL,
                    PRIMARY KEY(kind, node_id)
                )
                """.trimIndent(),
            )
            backfillEndpointKeys(db)
        }
        // v6 makes the junction a naming unit of its own: a wide crossing is
        // many points, and asking the user to name each one never converges.
        if (oldVersion < 6) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS places(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    radius_m REAL NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS place_points(
                    kind TEXT NOT NULL,
                    node_id INTEGER NOT NULL,
                    place_id INTEGER NOT NULL REFERENCES places(id) ON DELETE CASCADE,
                    PRIMARY KEY(kind, node_id)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_place_points_place ON place_points(place_id)")
            db.execSQL("DROP VIEW IF EXISTS point_labels")
            db.execSQL(
                """
                CREATE VIEW point_labels AS
                    SELECT kind, node_id, name FROM point_names
                    UNION ALL
                    SELECT pp.kind, pp.node_id, p.name
                      FROM place_points pp JOIN places p ON p.id = pp.place_id
                     WHERE NOT EXISTS (
                        SELECT 1 FROM point_names pn
                         WHERE pn.kind = pp.kind AND pn.node_id = pp.node_id
                     )
                """.trimIndent(),
            )
        }
        // v7 records roads on points. A name typed as "A × B" was always a
        // crossing, not an atomic point name: split it into roads and drop the
        // composite label so it can no longer leak into a segment's name.
        if (oldVersion < 7) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS point_roads(
                    kind TEXT NOT NULL,
                    node_id INTEGER NOT NULL,
                    road TEXT NOT NULL,
                    PRIMARY KEY(kind, node_id, road)
                )
                """.trimIndent(),
            )
            val compositePoints = ArrayList<Triple<String, Long, String>>()
            db.rawQuery(
                "SELECT kind, node_id, name FROM point_names WHERE name LIKE '%×%'",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    compositePoints += Triple(
                        cursor.getString(0),
                        cursor.getLong(1),
                        cursor.getString(2),
                    )
                }
            }
            for ((kind, nodeId, name) in compositePoints) {
                insertPointRoads(db, kind, nodeId, RoadNames.split(name))
                db.delete(
                    "point_names",
                    "kind = ? AND node_id = ?",
                    arrayOf(kind, nodeId.toString()),
                )
            }
            val compositePlaces = ArrayList<Pair<Long, String>>()
            db.rawQuery(
                "SELECT id, name FROM places WHERE name LIKE '%×%'",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) compositePlaces += cursor.getLong(0) to cursor.getString(1)
            }
            for ((placeId, name) in compositePlaces) {
                val roads = RoadNames.split(name)
                val members = ArrayList<Pair<String, Long>>()
                db.rawQuery(
                    "SELECT kind, node_id FROM place_points WHERE place_id = ?",
                    arrayOf(placeId.toString()),
                ).use { cursor ->
                    while (cursor.moveToNext()) members += cursor.getString(0) to cursor.getLong(1)
                }
                for ((kind, nodeId) in members) insertPointRoads(db, kind, nodeId, roads)
                // The junction itself was named after two roads, which is not a
                // junction name. Keep its members' roads, dissolve the place.
                db.delete("places", "id = ?", arrayOf(placeId.toString()))
            }
            db.execSQL("DROP VIEW IF EXISTS point_labels")
            db.execSQL(
                """
                CREATE VIEW point_labels AS
                    SELECT kind, node_id, name FROM point_names
                     WHERE name NOT LIKE '%×%'
                    UNION ALL
                    SELECT pp.kind, pp.node_id, p.name
                      FROM place_points pp JOIN places p ON p.id = pp.place_id
                     WHERE p.name NOT LIKE '%×%'
                       AND NOT EXISTS (
                         SELECT 1 FROM point_names pn
                          WHERE pn.kind = pp.kind AND pn.node_id = pp.node_id
                     )
                """.trimIndent(),
            )
            // Legs carry a copy of the segment label. The rule just changed from
            // road-name-first to atomic-point-pair-first, so rewrite them once.
            db.execSQL(
                """
                UPDATE legs SET label = COALESCE(
                    (SELECT pf.name || ' × ' || pt.name
                       FROM segments s
                       JOIN point_labels pf
                         ON pf.kind || ':' || pf.node_id = s.from_key
                       JOIN point_labels pt
                         ON pt.kind || ':' || pt.node_id = s.to_key
                      WHERE s.id = legs.segment_id AND pf.name <> pt.name),
                    (SELECT name FROM segments WHERE id = legs.segment_id),
                    (SELECT key FROM segments WHERE id = legs.segment_id)
                )
                """.trimIndent(),
            )
        }
        if (oldVersion < 8) migrateToV8(db)
    }

    /**
     * v8: first-class Node, Road, Edge, Junction.
     *
     * Preserves all user data: trips/points/marks/legs, point names, roads,
     * places, segments, geo nodes. Old JSON import compatibility is not required.
     */
    private fun migrateToV8(db: SQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = OFF")

        // ---- new tables ----------------------------------------------------
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS junctions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                radius_m REAL NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS nodes(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ref_kind TEXT NOT NULL,
                ref_node_id INTEGER NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                name TEXT,
                junction_id INTEGER REFERENCES junctions(id) ON DELETE SET NULL,
                last_seen INTEGER NOT NULL DEFAULT 0,
                UNIQUE(ref_kind, ref_node_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS junction_nodes(
                junction_id INTEGER NOT NULL REFERENCES junctions(id) ON DELETE CASCADE,
                node_id INTEGER NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
                PRIMARY KEY(node_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS roads(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                source TEXT NOT NULL DEFAULT 'user',
                osm_name TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS node_roads(
                node_id INTEGER NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
                road_id INTEGER NOT NULL REFERENCES roads(id) ON DELETE CASCADE,
                PRIMARY KEY(node_id, road_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS edges(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                key TEXT NOT NULL,
                mode TEXT NOT NULL,
                name TEXT,
                user_name TEXT,
                identity TEXT NOT NULL,
                a_lat REAL NOT NULL,
                a_lon REAL NOT NULL,
                b_lat REAL NOT NULL,
                b_lon REAL NOT NULL,
                length_m REAL NOT NULL,
                first_seen INTEGER NOT NULL,
                last_seen INTEGER NOT NULL,
                from_node_id INTEGER REFERENCES nodes(id) ON DELETE SET NULL,
                to_node_id INTEGER REFERENCES nodes(id) ON DELETE SET NULL,
                road_id INTEGER REFERENCES roads(id) ON DELETE SET NULL,
                UNIQUE(key, mode)
            )
            """.trimIndent(),
        )

        // ---- junctions from places -----------------------------------------
        db.execSQL(
            """
            INSERT INTO junctions(id, name, lat, lon, radius_m, created_at)
            SELECT id, name, lat, lon, radius_m, created_at FROM places
            """.trimIndent(),
        )

        // ---- collect node coordinates --------------------------------------
        // Best-effort: segment endpoints, geo_nodes, junction centres.
        data class NodeSeed(
            var lat: Double,
            var lon: Double,
            var lastSeen: Long = 0L,
            var name: String? = null,
            var junctionId: Long? = null,
        )
        val seeds = LinkedHashMap<String, NodeSeed>() // "kind:refNodeId" -> seed

        fun seed(key: String, lat: Double, lon: Double, lastSeen: Long = 0L): NodeSeed =
            seeds.getOrPut(key) { NodeSeed(lat, lon, lastSeen) }.also {
                if (it.lastSeen == 0L || (lastSeen > 0L && lastSeen > it.lastSeen)) {
                    it.lat = lat; it.lon = lon; it.lastSeen = lastSeen
                }
            }

        db.rawQuery(
            "SELECT from_key, to_key, a_lat, a_lon, b_lat, b_lon, last_seen FROM segments",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val fk = c.getString(0); val tk = c.getString(1)
                val aLat = c.getDouble(2); val aLon = c.getDouble(3)
                val bLat = c.getDouble(4); val bLon = c.getDouble(5)
                val seen = c.getLong(6)
                if (!fk.isNullOrBlank()) seed(fk, aLat, aLon, seen)
                if (!tk.isNullOrBlank()) seed(tk, bLat, bLon, seen)
            }
        }
        db.rawQuery("SELECT id, lat, lon, last_seen FROM geo_nodes", null).use { c ->
            while (c.moveToNext()) {
                seed("geo:${c.getLong(0)}", c.getDouble(1), c.getDouble(2), c.getLong(3))
            }
        }
        db.rawQuery("SELECT kind, node_id, name FROM point_names", null).use { c ->
            while (c.moveToNext()) {
                val key = "${c.getString(0)}:${c.getLong(1)}"
                val name = c.getString(2)
                val s = seeds.getOrPut(key) { NodeSeed(0.0, 0.0) }
                if (!name.isNullOrBlank() && !RoadNames.isComposite(name)) s.name = name
            }
        }
        db.rawQuery("SELECT kind, node_id FROM point_roads", null).use { c ->
            while (c.moveToNext()) {
                val key = "${c.getString(0)}:${c.getLong(1)}"
                seeds.getOrPut(key) { NodeSeed(0.0, 0.0) }
            }
        }
        db.rawQuery(
            "SELECT pp.kind, pp.node_id, pp.place_id, p.lat, p.lon FROM place_points pp JOIN places p ON p.id = pp.place_id",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val key = "${c.getString(0)}:${c.getLong(1)}"
                val placeId = c.getLong(2)
                val lat = c.getDouble(3); val lon = c.getDouble(4)
                val s = seeds.getOrPut(key) { NodeSeed(lat, lon) }
                s.junctionId = placeId
                if (s.lat == 0.0 && s.lon == 0.0) { s.lat = lat; s.lon = lon }
            }
        }

        // ---- insert nodes --------------------------------------------------
        val nodeIdByRef = HashMap<String, Long>()
        for ((key, s) in seeds) {
            val kind = key.substringBefore(':')
            val refNodeId = key.substringAfter(':').toLongOrNull() ?: continue
            val cv = ContentValues().apply {
                put("ref_kind", kind)
                put("ref_node_id", refNodeId)
                put("lat", s.lat)
                put("lon", s.lon)
                if (s.name != null) put("name", s.name)
                if (s.junctionId != null) put("junction_id", s.junctionId)
                put("last_seen", s.lastSeen)
            }
            val id = db.insert("nodes", null, cv)
            if (id > 0) nodeIdByRef[key] = id
        }

        // ---- junction_nodes ------------------------------------------------
        db.rawQuery("SELECT place_id, kind, node_id FROM place_points", null).use { c ->
            while (c.moveToNext()) {
                val placeId = c.getLong(0)
                val key = "${c.getString(1)}:${c.getLong(2)}"
                val nodeId = nodeIdByRef[key] ?: continue
                db.insertWithOnConflict(
                    "junction_nodes", null,
                    ContentValues().apply {
                        put("junction_id", placeId)
                        put("node_id", nodeId)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
        }

        // ---- roads from point_roads text -----------------------------------
        val roadIdByName = HashMap<String, Long>()
        fun ensureRoad(name: String, source: String = "user", osmName: String? = null): Long {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return -1L
            roadIdByName[trimmed]?.let { return it }
            val existing = db.rawQuery("SELECT id FROM roads WHERE name = ?", arrayOf(trimmed)).use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
            if (existing != null) { roadIdByName[trimmed] = existing; return existing }
            val id = db.insert("roads", null, ContentValues().apply {
                put("name", trimmed)
                put("source", source)
                if (osmName != null) put("osm_name", osmName)
            })
            if (id > 0) roadIdByName[trimmed] = id
            return id
        }

        db.rawQuery("SELECT kind, node_id, road FROM point_roads", null).use { c ->
            while (c.moveToNext()) {
                val key = "${c.getString(0)}:${c.getLong(1)}"
                val roadName = c.getString(2) ?: continue
                val nodeId = nodeIdByRef[key] ?: continue
                val roadId = ensureRoad(roadName)
                if (roadId <= 0) continue
                db.insertWithOnConflict(
                    "node_roads", null,
                    ContentValues().apply { put("node_id", nodeId); put("road_id", roadId) },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
        }

        // ---- edges from segments -------------------------------------------
        db.rawQuery("SELECT * FROM segments", null).use { c ->
            val cols = c.columnNames.toList()
            fun col(name: String) = cols.indexOf(name)
            while (c.moveToNext()) {
                val fromKey = c.getString(col("from_key"))
                val toKey = c.getString(col("to_key"))
                val fromNodeId = fromKey?.let { nodeIdByRef[it] }
                val toNodeId = toKey?.let { nodeIdByRef[it] }
                val userName = c.getString(col("user_name"))
                val roadId = userName?.takeIf { it.isNotBlank() }?.let { ensureRoad(it, "user") }?.takeIf { it > 0 }
                // Also register OSM name as a road source hint if present
                val osmName = c.getString(col("name"))
                if (!osmName.isNullOrBlank()) ensureRoad(osmName, "osm", osmName)

                db.insert(
                    "edges", null,
                    ContentValues().apply {
                        put("id", c.getLong(col("id")))
                        put("key", c.getString(col("key")))
                        put("mode", c.getString(col("mode")))
                        put("name", osmName)
                        put("user_name", userName)
                        put("identity", c.getString(col("identity")))
                        put("a_lat", c.getDouble(col("a_lat")))
                        put("a_lon", c.getDouble(col("a_lon")))
                        put("b_lat", c.getDouble(col("b_lat")))
                        put("b_lon", c.getDouble(col("b_lon")))
                        put("length_m", c.getDouble(col("length_m")))
                        put("first_seen", c.getLong(col("first_seen")))
                        put("last_seen", c.getLong(col("last_seen")))
                        if (fromNodeId != null) put("from_node_id", fromNodeId)
                        if (toNodeId != null) put("to_node_id", toNodeId)
                        if (roadId != null) put("road_id", roadId)
                    },
                )
            }
        }

        // ---- legs: segment_id -> edge_id -----------------------------------
        db.execSQL("ALTER TABLE legs RENAME TO legs_old")
        db.execSQL(
            """
            CREATE TABLE legs(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                trip_id INTEGER NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
                seq INTEGER NOT NULL,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                distance_m REAL NOT NULL,
                edge_id INTEGER REFERENCES edges(id) ON DELETE SET NULL,
                direction INTEGER NOT NULL DEFAULT 0,
                label TEXT,
                partial INTEGER NOT NULL DEFAULT 0,
                polyline TEXT NOT NULL,
                moving_ms INTEGER NOT NULL DEFAULT 0,
                stopped_ms INTEGER NOT NULL DEFAULT 0,
                stop_count INTEGER NOT NULL DEFAULT 0,
                longest_stop_ms INTEGER NOT NULL DEFAULT 0,
                signal_stop_count INTEGER NOT NULL DEFAULT 0,
                signal_wait_ms INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO legs(id, trip_id, seq, start_ms, end_ms, distance_m, edge_id,
                direction, label, partial, polyline, moving_ms, stopped_ms, stop_count,
                longest_stop_ms, signal_stop_count, signal_wait_ms)
            SELECT id, trip_id, seq, start_ms, end_ms, distance_m, segment_id,
                direction, label, partial, polyline, moving_ms, stopped_ms, stop_count,
                longest_stop_ms, signal_stop_count, signal_wait_ms
            FROM legs_old
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE legs_old")

        // ---- views, cleanup, indexes ---------------------------------------
        db.execSQL("DROP VIEW IF EXISTS point_labels")
        db.execSQL(
            """
            CREATE VIEW node_labels AS
                SELECT n.id AS node_id, n.ref_kind, n.ref_node_id,
                       COALESCE(n.name, j.name) AS name
                  FROM nodes n
                  LEFT JOIN junctions j ON j.id = n.junction_id
                 WHERE COALESCE(n.name, j.name) IS NOT NULL
                   AND COALESCE(n.name, j.name) NOT LIKE '%×%'
            """.trimIndent(),
        )
        // Keep a compatibility view for any code still reading point_labels during transition.
        db.execSQL(
            """
            CREATE VIEW point_labels AS
                SELECT ref_kind AS kind, ref_node_id AS node_id, name
                  FROM node_labels
            """.trimIndent(),
        )

        db.execSQL("DROP TABLE IF EXISTS place_points")
        db.execSQL("DROP TABLE IF EXISTS places")
        db.execSQL("DROP TABLE IF EXISTS point_names")
        db.execSQL("DROP TABLE IF EXISTS point_roads")
        db.execSQL("DROP TABLE IF EXISTS segments")

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_points_trip ON points(trip_id, t)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_nodes_ref ON nodes(ref_kind, ref_node_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_nodes_junction ON nodes(junction_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_junction_nodes_junction ON junction_nodes(junction_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_node_roads_road ON node_roads(road_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edges_mode ON edges(mode)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edges_from_node ON edges(from_node_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edges_to_node ON edges(to_node_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edges_road ON edges(road_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_legs_trip ON legs(trip_id, seq)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_legs_edge ON legs(edge_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_geo_nodes_cell ON geo_nodes(cell_x, cell_y)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_osm_way_tiles_tile ON osm_way_tiles(tile)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_osm_signals_tile ON osm_signals(tile)")

        db.execSQL("PRAGMA foreign_keys = ON")
    }

    private fun insertPointRoads(
        db: SQLiteDatabase,
        kind: String,
        nodeId: Long,
        roads: Collection<String>,
    ) {
        for (road in roads) {
            db.insertWithOnConflict(
                "point_roads",
                null,
                ContentValues().apply {
                    put("kind", kind)
                    put("node_id", nodeId)
                    put("road", road)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }

    /**
     * `osm:` and `geo:` keys already spell out both ends, so older segments can
     * take part in point naming before they are analysed again. A `mix:` key
     * sorts the two ids together and no longer says which kind each one is, so
     * those wait for the next analysis instead of being guessed at.
     */
    private fun backfillEndpointKeys(db: SQLiteDatabase) {
        val rows = ArrayList<Pair<Long, String>>()
        db.rawQuery(
            "SELECT id, key FROM segments WHERE key LIKE 'osm:%' OR key LIKE 'geo:%'",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) rows += cursor.getLong(0) to cursor.getString(1)
        }
        for ((id, key) in rows) {
            val kind = key.substringBefore(':')
            val parts = key.substringAfter(':').split('-')
            if (parts.size != 2) continue
            val a = parts[0].toLongOrNull() ?: continue
            val b = parts[1].toLongOrNull() ?: continue
            db.execSQL(
                "UPDATE segments SET from_key = ?, to_key = ? WHERE id = ?",
                arrayOf("$kind:$a", "$kind:$b", id.toString()),
            )
        }
    }

    companion object {
        const val NAME = "map-of-time.db"
        const val VERSION = 8
    }
}
