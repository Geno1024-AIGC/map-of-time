package com.geno1024.ai.mapoftime.data

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
        db.execSQL(
            """
            CREATE TABLE segments(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                key TEXT NOT NULL,
                mode TEXT NOT NULL,
                name TEXT,
                user_name TEXT,
                from_key TEXT,
                to_key TEXT,
                identity TEXT NOT NULL,
                a_lat REAL NOT NULL,
                a_lon REAL NOT NULL,
                b_lat REAL NOT NULL,
                b_lon REAL NOT NULL,
                length_m REAL NOT NULL,
                first_seen INTEGER NOT NULL,
                last_seen INTEGER NOT NULL,
                UNIQUE(key, mode)
            )
            """.trimIndent(),
        )
        // Names live on the points, not on every segment that touches them: a
        // junction is shared by everything that crosses it.
        db.execSQL(
            """
            CREATE TABLE point_names(
                kind TEXT NOT NULL,
                node_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                named_at INTEGER NOT NULL,
                PRIMARY KEY(kind, node_id)
            )
            """.trimIndent(),
        )
        // A wide crossing is many OSM nodes, one per lane and one per corner
        // refuge, and more of them show up every time a new turning movement
        // brushes another corner. The junction is therefore its own thing: a
        // name with a set of points under it, so one name covers all of them
        // and later arrivals inherit it without being named again.
        db.execSQL(
            """
            CREATE TABLE places(
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
            CREATE TABLE place_points(
                kind TEXT NOT NULL,
                node_id INTEGER NOT NULL,
                place_id INTEGER NOT NULL REFERENCES places(id) ON DELETE CASCADE,
                PRIMARY KEY(kind, node_id)
            )
            """.trimIndent(),
        )
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
        db.execSQL(
            """
            CREATE TABLE legs(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                trip_id INTEGER NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
                seq INTEGER NOT NULL,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                distance_m REAL NOT NULL,
                segment_id INTEGER REFERENCES segments(id) ON DELETE SET NULL,
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
        db.execSQL("CREATE INDEX idx_place_points_place ON place_points(place_id)")
        db.execSQL("CREATE INDEX idx_legs_trip ON legs(trip_id, seq)")
        db.execSQL("CREATE INDEX idx_legs_segment ON legs(segment_id)")
        db.execSQL("CREATE INDEX idx_segments_mode ON segments(mode)")
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
        const val VERSION = 6
    }
}
