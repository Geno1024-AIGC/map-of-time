package com.geno1024.ai.mapoftime.data

import android.content.Context
import com.geno1024.ai.mapoftime.osm.OverpassClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsSnapshot(
    /** Cut legs at OSM intersections when road data is available. */
    val osmMatching: Boolean = true,
    /** Draw OpenStreetMap raster tiles behind the track. */
    val mapTiles: Boolean = true,
    val overpassEndpoint: String = OverpassClient.DEFAULT_ENDPOINT,
    val tileUrlTemplate: String = DEFAULT_TILE_URL,
    /** Road data older than this is refreshed in the background. */
    val osmCacheDays: Int = 120,
    val junctionSnapMeters: Int = 30,
    /** Show segments whose start or end is the middle of a road. */
    val includePartial: Boolean = false,
) {
    companion object {
        const val DEFAULT_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    }
}

/**
 * Small preference store with an observable snapshot. The UI reads it through
 * Compose, the analyzer reads the same values on a background thread.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("map-of-time", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsSnapshot> = _state.asStateFlow()

    fun update(transform: (SettingsSnapshot) -> SettingsSnapshot) {
        val next = transform(_state.value)
        prefs.edit()
            .putBoolean(KEY_OSM_MATCHING, next.osmMatching)
            .putBoolean(KEY_MAP_TILES, next.mapTiles)
            .putString(KEY_OVERPASS, next.overpassEndpoint)
            .putString(KEY_TILE_URL, next.tileUrlTemplate)
            .putInt(KEY_CACHE_DAYS, next.osmCacheDays)
            .putInt(KEY_SNAP, next.junctionSnapMeters)
            .putBoolean(KEY_PARTIAL, next.includePartial)
            .apply()
        _state.value = next
    }

    private fun read() = SettingsSnapshot(
        osmMatching = prefs.getBoolean(KEY_OSM_MATCHING, true),
        mapTiles = prefs.getBoolean(KEY_MAP_TILES, true),
        overpassEndpoint = prefs.getString(KEY_OVERPASS, OverpassClient.DEFAULT_ENDPOINT)
            ?.takeIf { it.isNotBlank() } ?: OverpassClient.DEFAULT_ENDPOINT,
        tileUrlTemplate = prefs.getString(KEY_TILE_URL, SettingsSnapshot.DEFAULT_TILE_URL)
            ?.takeIf { it.isNotBlank() } ?: SettingsSnapshot.DEFAULT_TILE_URL,
        osmCacheDays = prefs.getInt(KEY_CACHE_DAYS, 120).coerceIn(0, 3650),
        junctionSnapMeters = prefs.getInt(KEY_SNAP, 30).coerceIn(10, 60),
        includePartial = prefs.getBoolean(KEY_PARTIAL, false),
    )

    private companion object {
        const val KEY_OSM_MATCHING = "osm_matching"
        const val KEY_MAP_TILES = "map_tiles"
        const val KEY_OVERPASS = "overpass_endpoint"
        const val KEY_TILE_URL = "tile_url"
        const val KEY_CACHE_DAYS = "osm_cache_days"
        const val KEY_SNAP = "junction_snap_meters"
        const val KEY_PARTIAL = "include_partial"
    }
}
