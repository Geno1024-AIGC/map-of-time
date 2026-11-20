package com.geno1024.ai.mapoftime.data

import android.content.Context
import androidx.core.content.edit
import com.geno1024.ai.mapoftime.core.Basemap
import com.geno1024.ai.mapoftime.core.HolidayBlock
import com.geno1024.ai.mapoftime.core.HolidayCalendar
import com.geno1024.ai.mapoftime.core.formatHolidayBlocks
import com.geno1024.ai.mapoftime.core.formatHolidayDates
import com.geno1024.ai.mapoftime.core.parseHolidayBlocks
import com.geno1024.ai.mapoftime.core.parseHolidayDates
import com.geno1024.ai.mapoftime.osm.OverpassClient
import com.geno1024.ai.mapoftime.update.Mirrors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

data class SettingsSnapshot(
    /** Cut legs at OSM intersections when road data is available. */
    val osmMatching: Boolean = true,
    /** Draw raster tiles behind the track. */
    val mapTiles: Boolean = true,
    /** OSM, AutoNavi, or whatever template the user typed in. */
    val basemap: Basemap = Basemap.OSM,
    val overpassEndpoint: String = OverpassClient.DEFAULT_ENDPOINT,
    /** Only used when [basemap] is [Basemap.CUSTOM]. */
    val customTileUrl: String = Basemap.OSM.tileTemplate.orEmpty(),
    /** Whether those custom tiles are laid out in GCJ-02. */
    val customTilesGcj02: Boolean = false,
    /** Road data older than this is refreshed in the background. */
    val osmCacheDays: Int = 120,
    val junctionSnapMeters: Int = 30,
    /** Show segments whose start or end is the middle of a road. */
    val includePartial: Boolean = false,
    /** Epoch millis of the last successful GitHub check; 0 when never. */
    val lastUpdateCheckAt: Long = 0L,
    /** Preferred APK download proxy; empty means direct GitHub. */
    val updateMirrorPrefix: String = "",
    /** Days off the user added on top of the shipped calendar. */
    val customHolidays: List<HolidayBlock> = emptyList(),
    /** Weekends the user works, e.g. someone else's 调休. */
    val customWorkdays: Set<LocalDate> = emptySet(),
) {
    /**
     * The calendar every screen reads. Built once per snapshot: a settings
     * change creates a new one, which is exactly when the buckets must change.
     */
    val holidayCalendar: HolidayCalendar by lazy {
        HolidayCalendar.of(customHolidays, customWorkdays)
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
        prefs.edit {
            putBoolean(KEY_OSM_MATCHING, next.osmMatching)
            putBoolean(KEY_MAP_TILES, next.mapTiles)
            putString(KEY_BASEMAP, next.basemap.id)
            putString(KEY_OVERPASS, next.overpassEndpoint)
            putString(KEY_TILE_URL, next.customTileUrl)
            putBoolean(KEY_TILE_GCJ, next.customTilesGcj02)
            putInt(KEY_CACHE_DAYS, next.osmCacheDays)
            putInt(KEY_SNAP, next.junctionSnapMeters)
            putBoolean(KEY_PARTIAL, next.includePartial)
            putLong(KEY_LAST_UPDATE_CHECK, next.lastUpdateCheckAt)
            putString(KEY_UPDATE_MIRROR, next.updateMirrorPrefix)
            putString(KEY_CUSTOM_HOLIDAYS, formatHolidayBlocks(next.customHolidays))
            putString(KEY_CUSTOM_WORKDAYS, formatHolidayDates(next.customWorkdays))
        }
        _state.value = next
    }

    private fun read(): SettingsSnapshot {
        val customUrl = prefs.getString(KEY_TILE_URL, null)?.takeIf { it.isNotBlank() }
        // Before the basemap picker existed the only choice was a URL, so an
        // edited URL means the user was running a custom source.
        val basemap = prefs.getString(KEY_BASEMAP, null)?.let { Basemap.of(it) }
            ?: if (customUrl != null && customUrl != Basemap.OSM.tileTemplate) Basemap.CUSTOM else Basemap.OSM
        return SettingsSnapshot(
            osmMatching = prefs.getBoolean(KEY_OSM_MATCHING, true),
            mapTiles = prefs.getBoolean(KEY_MAP_TILES, true),
            basemap = basemap,
            overpassEndpoint = prefs.getString(KEY_OVERPASS, OverpassClient.DEFAULT_ENDPOINT)
                ?.takeIf { it.isNotBlank() } ?: OverpassClient.DEFAULT_ENDPOINT,
            customTileUrl = customUrl ?: Basemap.OSM.tileTemplate.orEmpty(),
            customTilesGcj02 = prefs.getBoolean(KEY_TILE_GCJ, false),
            osmCacheDays = prefs.getInt(KEY_CACHE_DAYS, 120).coerceIn(0, 3650),
            junctionSnapMeters = prefs.getInt(KEY_SNAP, 30).coerceIn(10, 60),
            includePartial = prefs.getBoolean(KEY_PARTIAL, false),
            lastUpdateCheckAt = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L),
            updateMirrorPrefix = Mirrors.resolve(
                prefs.getString(KEY_UPDATE_MIRROR, "").orEmpty(),
            ).prefix,
            // A stored value that no longer parses is dropped rather than
            // half-applied: the shipped calendar is a better answer than a
            // calendar with a hole in it.
            customHolidays = parseHolidayBlocks(prefs.getString(KEY_CUSTOM_HOLIDAYS, "").orEmpty())
                .orEmpty(),
            customWorkdays = parseHolidayDates(prefs.getString(KEY_CUSTOM_WORKDAYS, "").orEmpty())
                .orEmpty(),
        )
    }

    private companion object {
        const val KEY_OSM_MATCHING = "osm_matching"
        const val KEY_MAP_TILES = "map_tiles"
        const val KEY_BASEMAP = "basemap"
        const val KEY_OVERPASS = "overpass_endpoint"
        const val KEY_TILE_URL = "tile_url"
        const val KEY_TILE_GCJ = "tile_gcj02"
        const val KEY_CACHE_DAYS = "osm_cache_days"
        const val KEY_SNAP = "junction_snap_meters"
        const val KEY_PARTIAL = "include_partial"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        const val KEY_UPDATE_MIRROR = "update_mirror_prefix"
        const val KEY_CUSTOM_HOLIDAYS = "custom_holidays"
        const val KEY_CUSTOM_WORKDAYS = "custom_workdays"
    }
}
