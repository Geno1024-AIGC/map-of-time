package com.geno1024.ai.mapoftime.ui.map

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.geno1024.ai.mapoftime.core.Basemap
import com.geno1024.ai.mapoftime.core.Bounds
import com.geno1024.ai.mapoftime.core.Gcj
import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.MapCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * One dot on the map. [label] is drawn next to it — a segment's two ends look
 * identical otherwise, and "which one am I naming" is exactly the question.
 */
data class MapMarker(val at: LatLon, val emphasized: Boolean = false, val label: String? = null)

/**
 * A slippy map drawn on a plain Canvas: raster tiles when they can be fetched,
 * the track itself always. Deliberately no map SDK — the app only needs a few
 * polylines and dots, and this keeps working offline.
 */
@Composable
fun TrackMap(
    tracks: List<List<LatLon>>,
    modifier: Modifier = Modifier,
    markers: List<MapMarker> = emptyList(),
    tilesEnabled: Boolean = true,
    basemap: Basemap = Basemap.OSM,
    /** Only read when [basemap] is [Basemap.CUSTOM]. */
    customTileUrl: String = "",
    /** Only read when [basemap] is [Basemap.CUSTOM]. */
    customTilesGcj02: Boolean = false,
    interactive: Boolean = true,
    emptyHint: String? = null,
    /** Where to look when there is no track yet, e.g. the latest live fix. */
    focus: LatLon? = null,
    /**
     * One leg the user picked in the list. Drawn on top of the whole track and
     * used as the camera bounds, so tapping a leg zooms the map to it.
     */
    highlight: List<LatLon>? = null,
    /**
     * Called with the index into [markers] when a tap lands on one. The map
     * does the hit test in screen space, so callers never have to undo the
     * projection (or the GCJ-02 shift) themselves.
     */
    onMarkerTap: ((Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    // AutoNavi publishes GCJ-02 tiles, so the overlay must be shifted into the
    // same datum. Converting the centre and the bounds too keeps panning,
    // fitting and drawing consistent instead of only moving the polyline.
    val usesGcj = if (basemap == Basemap.CUSTOM) customTilesGcj02 else basemap.gcj02
    val displayTracks = remember(tracks, usesGcj) {
        if (!usesGcj) tracks else tracks.map { track -> track.map(Gcj::toGcj02) }
    }
    val displayMarkers = remember(markers, usesGcj) {
        if (!usesGcj) markers else markers.map { it.copy(at = Gcj.toGcj02(it.at)) }
    }
    val displayFocus = remember(focus, usesGcj) {
        if (!usesGcj) focus else focus?.let(Gcj::toGcj02)
    }
    val displayHighlight = remember(highlight, usesGcj) {
        if (!usesGcj) highlight else highlight?.map(Gcj::toGcj02)
    }

    // The camera follows the selected leg; the rest of the trip stays visible.
    val cameraPoints = remember(displayTracks, displayHighlight) {
        displayHighlight?.takeIf { it.size >= 2 } ?: displayTracks.flatten()
    }
    val bounds = remember(cameraPoints) { Geo.bounds(cameraPoints) }
    val tileTemplate = basemap.tileTemplate ?: customTileUrl
    val storeKey = basemap.id + "|" + tileTemplate
    val store = remember(storeKey) {
        val folder = if (basemap == Basemap.CUSTOM) {
            "tiles-custom-" + Integer.toHexString(tileTemplate.hashCode())
        } else {
            "tiles-" + basemap.id
        }
        TileStore(context, basemap, customTileUrl, folder)
    }

    var size by remember { mutableStateOf(Size.Zero) }
    var center by remember(bounds) {
        mutableStateOf(bounds?.center ?: displayFocus ?: LatLon(39.9042, 116.4074))
    }
    var zoom by remember(bounds) { mutableStateOf(15f) }
    var userMoved by remember { mutableStateOf(false) }

    // Screen position of a marker right now, using the same fractional-zoom
    // world the canvas draws with. Only read from the tap handler.
    val projectMarker: (LatLon) -> Offset = { point ->
        val world = MapCamera.worldSize(zoom.toDouble())
        Offset(
            ((lonToX(point.lon, world) - lonToX(center.lon, world)) + size.width / 2.0).toFloat(),
            ((latToY(point.lat, world) - latToY(center.lat, world)) + size.height / 2.0).toFloat(),
        )
    }

    LaunchedEffect(bounds, size) {
        if (bounds != null && size.width > 1f && size.height > 1f) {
            center = bounds.center
            zoom = fitZoom(bounds, size)
            userMoved = false
        }
    }

    // Before a trip has any track, follow the live fix — but never fight a
    // pan or pinch the user did on purpose.
    LaunchedEffect(displayFocus, bounds) {
        if (bounds != null || userMoved) return@LaunchedEffect
        val target = displayFocus ?: return@LaunchedEffect
        center = target
        if (zoom < 16f) zoom = 16f
    }

    val maxZoom = basemap.maxZoom
    val z = floor(zoom).toInt().coerceIn(2, maxZoom)
    val scale = 2.0.pow((zoom - z).toDouble())
    val worldTiles = 1 shl z
    val worldPx = TILE_PX * worldTiles
    val centerTileX = lonToTile(center.lon, worldTiles)
    val centerTileY = latToTile(center.lat, worldTiles)
    val halfCols = if (size.width > 1f) size.width / (2.0 * TILE_PX * scale) else 0.0
    val halfRows = if (size.height > 1f) size.height / (2.0 * TILE_PX * scale) else 0.0
    val minX = floor(centerTileX - halfCols).toInt() - 1
    val maxX = ceil(centerTileX + halfCols).toInt() + 1
    val minY = floor(centerTileY - halfRows).toInt() - 1
    val maxY = ceil(centerTileY + halfRows).toInt() + 1

    val visibleKeys = remember(minX, maxX, minY, maxY, z, tilesEnabled) {
        if (!tilesEnabled) emptyList()
        else buildList {
            for (ty in minY..maxY) {
                if (ty < 0 || ty >= worldTiles) continue
                for (tx in minX..maxX) {
                    val wrapped = ((tx % worldTiles) + worldTiles) % worldTiles
                    add(TileKey(z, wrapped, ty, tx))
                }
            }
        }
    }

    val bitmaps = remember(storeKey) { mutableStateMapOf<TileKey, ImageBitmap>() }
    LaunchedEffect(visibleKeys, storeKey, tilesEnabled) {
        if (!tilesEnabled) return@LaunchedEffect
        for (key in visibleKeys) {
            if (bitmaps.containsKey(key)) continue
            val image = store.load(key.z, key.x, key.y) ?: continue
            bitmaps[key] = image
        }
    }

    Box(
        modifier = modifier
            // A Canvas draws outside its bounds unless told not to, and a
            // panned track would otherwise paint over the cards around it.
            .clipToBounds()
            .background(colors.surface)
            .onSizeChanged { size = Size(it.width.toFloat(), it.height.toFloat()) }
            .then(
                if (!interactive) Modifier else Modifier
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoomChange, _ ->
                            userMoved = true
                            val targetZoom = (zoom + ln(zoomChange.toDouble()) / LN_2)
                                .coerceIn(3.0, maxZoom.toDouble())
                            // A drag is a drag: move the view by exactly the
                            // finger's travel. The finger only becomes an
                            // anchor when the pinch actually changes zoom.
                            val moved = MapCamera.pan(
                                center = center,
                                zoom = zoom.toDouble(),
                                dx = pan.x.toDouble(),
                                dy = pan.y.toDouble(),
                            )
                            if (abs(targetZoom - zoom) < 0.001) {
                                center = moved
                            } else {
                                center = MapCamera.zoomAbout(
                                    center = moved,
                                    zoom = zoom.toDouble(),
                                    ratio = 2.0.pow(targetZoom - zoom),
                                    focusX = centroid.x.toDouble(),
                                    focusY = centroid.y.toDouble(),
                                    width = size.width.toDouble(),
                                    height = size.height.toDouble(),
                                )
                                zoom = targetZoom.toFloat()
                            }
                        }
                    }
                    .pointerInput(displayMarkers, onMarkerTap, center, zoom, size) {
                        detectTapGestures(
                            onTap = { tap ->
                                val handler = onMarkerTap ?: return@detectTapGestures
                                val reach = 22.dp.toPx()
                                var bestIndex = -1
                                var bestDistance = Float.MAX_VALUE
                                displayMarkers.forEachIndexed { index, marker ->
                                    val distance = (projectMarker(marker.at) - tap).getDistance()
                                    if (distance < bestDistance) {
                                        bestDistance = distance
                                        bestIndex = index
                                    }
                                }
                                if (bestIndex >= 0 && bestDistance <= reach) handler(bestIndex)
                            },
                            onDoubleTap = { tap ->
                                val targetZoom = (zoom + 1f).coerceAtMost(maxZoom.toFloat())
                                val ratio = 2.0.pow((targetZoom - zoom).toDouble())
                                if (ratio > 1.0001) {
                                    center = MapCamera.zoomAbout(
                                        center = center,
                                        zoom = zoom.toDouble(),
                                        ratio = ratio,
                                        focusX = tap.x.toDouble(),
                                        focusY = tap.y.toDouble(),
                                        width = size.width.toDouble(),
                                        height = size.height.toDouble(),
                                    )
                                    zoom = targetZoom
                                }
                            },
                        )
                    },
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val centerWorldX = lonToX(center.lon, worldPx)
            val centerWorldY = latToY(center.lat, worldPx)

            if (tilesEnabled) {
                for (key in visibleKeys) {
                    val image = bitmaps[key] ?: continue
                    val left = ((key.drawX * TILE_PX - centerWorldX) * scale + size.width / 2.0).toFloat()
                    val top = ((key.y * TILE_PX - centerWorldY) * scale + size.height / 2.0).toFloat()
                    val side = (TILE_PX * scale).roundToInt()
                    drawImage(
                        image = image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(image.width, image.height),
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(side, side),
                        filterQuality = FilterQuality.Low,
                    )
                }
            } else {
                drawGrid(colors.outline.copy(alpha = 0.25f))
            }

            fun project(point: LatLon): Offset = Offset(
                ((lonToX(point.lon, worldPx) - centerWorldX) * scale + size.width / 2.0).toFloat(),
                ((latToY(point.lat, worldPx) - centerWorldY) * scale + size.height / 2.0).toFloat(),
            )

            for (track in displayTracks) {
                if (track.size < 2) continue
                val path = Path()
                val first = project(track.first())
                path.moveTo(first.x, first.y)
                for (i in 1 until track.size) {
                    val p = project(track[i])
                    path.lineTo(p.x, p.y)
                }
                // A selected leg must stand out without hiding the rest of the
                // trip: dim everything else instead of removing it.
                val dimmed = displayHighlight != null
                drawPath(
                    path,
                    color = Color.Black.copy(alpha = if (dimmed) 0.2f else 0.45f),
                    style = Stroke(width = 9.dp.toPx()),
                )
                drawPath(
                    path,
                    color = colors.primary.copy(alpha = if (dimmed) 0.35f else 1f),
                    style = Stroke(width = 4.5.dp.toPx()),
                )
            }

            displayHighlight?.let { track ->
                if (track.size >= 2) {
                    val path = Path()
                    val first = project(track.first())
                    path.moveTo(first.x, first.y)
                    for (i in 1 until track.size) {
                        val p = project(track[i])
                        path.lineTo(p.x, p.y)
                    }
                    drawPath(
                        path,
                        color = Color.Black.copy(alpha = 0.55f),
                        style = Stroke(width = 12.dp.toPx()),
                    )
                    drawPath(
                        path,
                        color = colors.secondary,
                        style = Stroke(width = 7.dp.toPx()),
                    )
                }
            }

            for (marker in displayMarkers) {
                val p = project(marker.at)
                val radius = if (marker.emphasized) 6.dp.toPx() else 4.dp.toPx()
                drawCircle(color = Color.Black.copy(alpha = 0.5f), radius = radius + 2.dp.toPx(), center = p)
                drawCircle(
                    color = if (marker.emphasized) colors.secondary else colors.primary,
                    radius = radius,
                    center = p,
                )
                marker.label?.let { label ->
                    drawMarkerLabel(label, p, radius, 4.dp.toPx(), 11.dp.toPx())
                }
            }

            if (displayTracks.all { it.isEmpty() } && emptyHint != null) {
                drawContext.canvas.nativeCanvas.drawText(
                    emptyHint,
                    size.width / 2f,
                    size.height / 2f,
                    android.graphics.Paint().apply {
                        color = 0x88FFFFFF.toInt()
                        textSize = 14.dp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                    },
                )
            }

            if (tilesEnabled && basemap.attribution.isNotEmpty()) {
                drawContext.canvas.nativeCanvas.drawText(
                    basemap.attribution,
                    size.width - 8.dp.toPx(),
                    size.height - 8.dp.toPx(),
                    android.graphics.Paint().apply {
                        color = if (colors.background.luminance() > 0.5f) 0x99202020.toInt() else 0x99FFFFFF.toInt()
                        textSize = 10.dp.toPx()
                        textAlign = android.graphics.Paint.Align.RIGHT
                    },
                )
            }
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private fun DrawScope.drawGrid(color: Color) {
    val step = 64.dp.toPx()
    var x = 0f
    while (x < size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
}

private data class TileKey(val z: Int, val x: Int, val y: Int, val drawX: Int)

private const val TILE_PX = MapCamera.TILE_PX
private const val LN_2 = 0.6931471805599453
private fun lonToTile(lon: Double, worldTiles: Int): Double = (lon + 180.0) / 360.0 * worldTiles

private fun latToTile(lat: Double, worldTiles: Int): Double = Geo.mercatorY(lat) * worldTiles

private fun lonToX(lon: Double, worldPx: Double): Double = MapCamera.worldX(lon, worldPx)

private fun latToY(lat: Double, worldPx: Double): Double = MapCamera.worldY(lat, worldPx)

/** Zoom at which [bounds] fits with a little room to breathe. */
private fun fitZoom(bounds: Bounds, size: Size): Float {
    for (z in 18 downTo 3) {
        val world = TILE_PX * 2.0.pow(z.toDouble())
        val width = abs(lonToX(bounds.maxLon, world) - lonToX(bounds.minLon, world))
        val height = abs(latToY(bounds.maxLat, world) - latToY(bounds.minLat, world))
        if (width <= size.width * 0.85 && height <= size.height * 0.85) return z.toFloat()
    }
    return 3f
}

/**
 * Downloads raster tiles into a small disk cache. Memory is an LRU so panning
 * never decodes the same PNG twice, and the directory is per source so
 * switching basemaps cannot serve one provider's tile under another's name.
 */
private class TileStore(
    context: Context,
    private val basemap: Basemap,
    private val customTemplate: String,
    folder: String,
) {
    private val cacheDir = File(context.cacheDir, folder)
    private val memory = object : LruCache<String, ImageBitmap>(64) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = 1
    }
    private val semaphore = Semaphore(4)

    suspend fun load(z: Int, x: Int, y: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$z/$x/$y"
        memory.get(key)?.let { return@withContext it }
        val file = File(cacheDir, "$z/$x/$y.png")
        val bytes = if (file.isFile && file.length() > 0) {
            runCatching { file.readBytes() }.getOrNull()
        } else {
            semaphore.withPermit { download(z, x, y, file) }
        }
        if (bytes == null || bytes.isEmpty()) return@withContext null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
        bitmap.asImageBitmap().also { memory.put(key, it) }
    }

    private fun download(z: Int, x: Int, y: Int, target: File): ByteArray? {
        val url = basemap.tileUrl(customTemplate, z, x, y)
        if (url.isEmpty()) return null
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "image/png,image/*")
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                val bytes = connection.inputStream.use { it.readBytes() }
                if (bytes.size > MAX_TILE_BYTES) return null
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, "${target.name}.tmp")
                temp.writeBytes(bytes)
                if (!temp.renameTo(target)) temp.delete()
                bytes
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private companion object {
        const val USER_AGENT = "map-of-time/0.1 (https://github.com/Geno1024-AIGC/map-of-time)"
        const val MAX_TILE_BYTES = 2 * 1024 * 1024
    }
}

/**
 * Draws a short label beside a marker, flipping it to the other side near an
 * edge so it never leaves the canvas. The dark pill keeps it readable on top of
 * any basemap, including a bright one.
 */
private fun DrawScope.drawMarkerLabel(
    text: String,
    at: Offset,
    radius: Float,
    gap: Float,
    textSize: Float,
) {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        this.textSize = textSize
        isFakeBoldText = true
    }
    val width = paint.measureText(text)
    val padding = 3.dp.toPx()

    var x = at.x + radius + gap
    if (x + width + padding > size.width) x = at.x - radius - gap - width
    x = x.coerceIn(gap, (size.width - width - gap).coerceAtLeast(gap))

    // Above the dot by default; below it when the dot sits near the top edge.
    var baseline = at.y - radius - gap
    if (baseline - textSize < gap) baseline = at.y + radius + gap + textSize

    drawRoundRect(
        color = Color.Black.copy(alpha = 0.65f),
        topLeft = Offset(x - padding, baseline - textSize),
        size = Size(width + padding * 2, textSize + padding * 2),
        cornerRadius = CornerRadius(padding),
    )
    drawContext.canvas.nativeCanvas.drawText(text, x, baseline, paint)
}
