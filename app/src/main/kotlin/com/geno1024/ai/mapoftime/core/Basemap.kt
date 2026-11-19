package com.geno1024.ai.mapoftime.core

/**
 * Where the raster tiles behind a track come from.
 *
 * AutoNavi is included because `tile.openstreetmap.org` is frequently
 * unreachable from mainland China. Its tiles are laid out in **GCJ-02**, the
 * offset datum required by Chinese law, so the track has to be shifted before
 * it is drawn on them — see [Gcj].
 */
enum class Basemap(
    val id: String,
    /** Shown in the corner of every map that uses this source. */
    val attribution: String,
    /** `null` only for [CUSTOM], where the user supplies the template. */
    val tileTemplate: String?,
    /** True when the tiles are in GCJ-02 rather than WGS-84. */
    val gcj02: Boolean,
    /** Deepest zoom the source actually has tiles for. */
    val maxZoom: Int,
) {
    OSM(
        id = "osm",
        attribution = "© OpenStreetMap contributors",
        tileTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        gcj02 = false,
        maxZoom = 19,
    ),
    AMAP(
        id = "amap",
        attribution = "© AutoNavi",
        tileTemplate = "https://webrd0{s}.is.autonavi.com/appmaptile" +
            "?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
        gcj02 = true,
        // z=19 answers with a blank placeholder tile.
        maxZoom = 18,
    ),
    CUSTOM(
        id = "custom",
        attribution = "",
        tileTemplate = null,
        gcj02 = false,
        maxZoom = 19,
    ),
    ;

    /**
     * One tile URL. [customTemplate] is only used by [CUSTOM].
     *
     * `{s}` picks one of four AutoNavi subdomains; the other sources simply do
     * not contain it, so the same code serves all three.
     */
    fun tileUrl(customTemplate: String, z: Int, x: Int, y: Int): String {
        val template = tileTemplate ?: customTemplate.trim()
        if (template.isEmpty()) return ""
        return template
            .replace("{s}", (1 + ((x + y) and 3)).toString())
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
    }

    companion object {
        /** Falls back to OSM for an unknown or missing stored id. */
        fun of(id: String?): Basemap = entries.firstOrNull { it.id == id } ?: OSM
    }
}
