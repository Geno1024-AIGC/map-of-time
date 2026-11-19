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
                longest_stop_ms INTEGER NOT NULL DEFAULT 0
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

        db.execSQL("CREATE INDEX idx_points_trip ON points(trip_id, t)")
        db.execSQL("CREATE INDEX idx_legs_trip ON legs(trip_id, seq)")
        db.execSQL("CREATE INDEX idx_legs_segment ON legs(segment_id)")
        db.execSQL("CREATE INDEX idx_segments_mode ON segments(mode)")
        db.execSQL("CREATE INDEX idx_geo_nodes_cell ON geo_nodes(cell_x, cell_y)")
        db.execSQL("CREATE INDEX idx_osm_way_tiles_tile ON osm_way_tiles(tile)")
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
    }

    companion object {
        const val NAME = "map-of-time.db"
        const val VERSION = 2
    }
}
