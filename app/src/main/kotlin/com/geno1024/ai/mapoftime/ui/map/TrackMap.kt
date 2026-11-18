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
import com.geno1024.ai.mapoftime.core.Bounds
import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.LatLon
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

data class MapMarker(val at: LatLon, val emphasized: Boolean = false)

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
    tileUrlTemplate: String = DEFAULT_TILE_URL,
    interactive: Boolean = true,
    emptyHint: String? = null,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val allPoints = remember(tracks) { tracks.flatten() }
    val bounds = remember(tracks) { Geo.bounds(allPoints) }
    val store = remember(tileUrlTemplate) { TileStore(context, tileUrlTemplate) }

    var size by remember { mutableStateOf(Size.Zero) }
    var center by remember(bounds) { mutableStateOf(bounds?.center ?: LatLon(39.9042, 116.4074)) }
    var zoom by remember(bounds) { mutableStateOf(15f) }

    LaunchedEffect(bounds, size) {
        if (bounds != null && size.width > 1f && size.height > 1f) {
            center = bounds.center
            zoom = fitZoom(bounds, size)
        }
    }

    val z = floor(zoom).toInt().coerceIn(2, 19)
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

    val bitmaps = remember { mutableStateMapOf<TileKey, ImageBitmap>() }
    LaunchedEffect(visibleKeys, tileUrlTemplate, tilesEnabled) {
        if (!tilesEnabled) return@LaunchedEffect
        for (key in visibleKeys) {
            if (bitmaps.containsKey(key)) continue
            val image = store.load(key.z, key.x, key.y) ?: continue
            bitmaps[key] = image
        }
    }

    Box(
        modifier = modifier
            .background(colors.surface)
            .onSizeChanged { size = Size(it.width.toFloat(), it.height.toFloat()) }
            .then(
                if (!interactive) Modifier else Modifier
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoomChange, _ ->
                            val viewportWidth = size.width.toDouble()
                            val viewportHeight = size.height.toDouble()
                            val world = TILE_PX * 2.0.pow(zoom.toDouble())
                            val pannedLon = xToLon(lonToX(center.lon, world) - pan.x, world)
                            val pannedLat = yToLat(latToY(center.lat, world) - pan.y, world)
                            val newZoom = (zoom + ln(zoomChange.toDouble()) / LN_2).coerceIn(3.0, 19.0)
                            val newWorld = TILE_PX * 2.0.pow(newZoom)
                            val nx = lonToX(pannedLon, world) / world
                            val ny = latToY(pannedLat, world) / world
                            val anchorX = nx * newWorld - (centroid.x - viewportWidth / 2.0)
                            val anchorY = ny * newWorld - (centroid.y - viewportHeight / 2.0)
                            center = LatLon(
                                yToLat(anchorY, newWorld).coerceIn(-85.0, 85.0),
                                xToLon(anchorX, newWorld),
                            )
                            zoom = newZoom.toFloat()
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { tap ->
                                val newZoom = (zoom + 1f).coerceAtMost(19f)
                                val world = TILE_PX * 2.0.pow(zoom.toDouble())
                                val newWorld = TILE_PX * 2.0.pow(newZoom.toDouble())
                                val nx = lonToX(center.lon, world) / world
                                val ny = latToY(center.lat, world) / world
                                val anchorX = nx * newWorld - (tap.x - size.width / 2.0)
                                val anchorY = ny * newWorld - (tap.y - size.height / 2.0)
                                center = LatLon(yToLat(anchorY, newWorld), xToLon(anchorX, newWorld))
                                zoom = newZoom
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

            for (track in tracks) {
                if (track.size < 2) continue
                val path = Path()
                val first = project(track.first())
                path.moveTo(first.x, first.y)
                for (i in 1 until track.size) {
                    val p = project(track[i])
                    path.lineTo(p.x, p.y)
                }
                drawPath(path, color = Color.Black.copy(alpha = 0.45f), style = Stroke(width = 9.dp.toPx()))
                drawPath(path, color = colors.primary, style = Stroke(width = 4.5.dp.toPx()))
            }

            for (marker in markers) {
                val p = project(marker.at)
                val radius = if (marker.emphasized) 6.dp.toPx() else 4.dp.toPx()
                drawCircle(color = Color.Black.copy(alpha = 0.5f), radius = radius + 2.dp.toPx(), center = p)
                drawCircle(
                    color = if (marker.emphasized) colors.secondary else colors.primary,
                    radius = radius,
                    center = p,
                )
            }

            if (tracks.all { it.isEmpty() } && emptyHint != null) {
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

            if (tilesEnabled) {
                drawContext.canvas.nativeCanvas.drawText(
                    "© OpenStreetMap contributors",
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

private const val TILE_PX = 256.0
private const val LN_2 = 0.6931471805599453
const val DEFAULT_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

private fun lonToTile(lon: Double, worldTiles: Int): Double = (lon + 180.0) / 360.0 * worldTiles

private fun latToTile(lat: Double, worldTiles: Int): Double = Geo.mercatorY(lat) * worldTiles

private fun lonToX(lon: Double, worldPx: Double): Double = (lon + 180.0) / 360.0 * worldPx

private fun latToY(lat: Double, worldPx: Double): Double = Geo.mercatorY(lat) * worldPx

private fun xToLon(x: Double, worldPx: Double): Double = x / worldPx * 360.0 - 180.0

private fun yToLat(y: Double, worldPx: Double): Double = Geo.mercatorLat(y / worldPx)

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
 * Downloads OSM raster tiles into a small disk cache. Memory is an LRU so
 * panning never decodes the same PNG twice.
 */
private class TileStore(
    context: Context,
    private val template: String,
) {
    private val cacheDir = File(context.cacheDir, "osm-tiles")
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
        val url = template
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
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
