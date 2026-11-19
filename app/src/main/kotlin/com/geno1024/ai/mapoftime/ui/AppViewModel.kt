package com.geno1024.ai.mapoftime.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geno1024.ai.mapoftime.AppContainer
import com.geno1024.ai.mapoftime.analysis.SegmentSummary
import com.geno1024.ai.mapoftime.analysis.TripAnalyzer
import com.geno1024.ai.mapoftime.core.DurationStats
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.Segmenter
import com.geno1024.ai.mapoftime.core.TrackPoint
import com.geno1024.ai.mapoftime.core.TraversalSample
import com.geno1024.ai.mapoftime.core.TripMode
import com.geno1024.ai.mapoftime.data.Leg
import com.geno1024.ai.mapoftime.data.MatchKind
import com.geno1024.ai.mapoftime.data.Segment
import com.geno1024.ai.mapoftime.data.Trip
import com.geno1024.ai.mapoftime.track.Recorder
import com.geno1024.ai.mapoftime.update.AppVersion
import com.geno1024.ai.mapoftime.update.InstallOutcome
import com.geno1024.ai.mapoftime.update.Mirror
import com.geno1024.ai.mapoftime.update.Mirrors
import com.geno1024.ai.mapoftime.update.ReleaseInfo
import com.geno1024.ai.mapoftime.update.UpdateChecker
import com.geno1024.ai.mapoftime.update.UpdateDownloader
import com.geno1024.ai.mapoftime.update.UpdateError
import com.geno1024.ai.mapoftime.update.UpdateException
import com.geno1024.ai.mapoftime.update.UpdateInstaller
import com.geno1024.ai.mapoftime.update.UpdateState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AppViewModel(private val container: AppContainer) : ViewModel() {

    val repository get() = container.repository
    val dbDispatcher get() = container.dbDispatcher
    val settings = container.settings

    val recording: StateFlow<com.geno1024.ai.mapoftime.track.RecordingState> = Recorder.state

    var trips by mutableStateOf<List<Trip>>(emptyList())
        private set
    var summaries by mutableStateOf<List<SegmentSummary>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var note by mutableStateOf<String?>(null)

    /** The in-app update flow; the settings card and the tab badge share it. */
    var update by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    val hasUpdate: Boolean
        get() = update is UpdateState.Available ||
            update is UpdateState.Downloading ||
            update is UpdateState.Ready

    private val updateChecker = UpdateChecker()
    private val updateDownloader = UpdateDownloader()
    private val updatesDir: File get() = File(container.appContext.cacheDir, "updates")

    /** The preferred source first, then the presets; a dead mirror cannot block an update. */
    private val mirrors: List<Mirror> get() = Mirrors.candidates(settings.state.value.updateMirrorPrefix)

    init {
        refresh()
        // Silent: the result only surfaces as a badge, never as a dialog.
        checkForUpdate(manual = false)
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            val loaded = withContext(container.dbDispatcher) {
                val trips = container.repository.trips()
                val segments = container.repository.segments()
                val includePartial = settings.state.value.includePartial
                val byMode = TripMode.entries.associateWith {
                    container.repository.samplesByMode(it, includePartial)
                }
                val summaries = segments.map { segment ->
                    val samples = byMode[segment.mode]?.get(segment.id).orEmpty()
                    SegmentSummary(
                        segment = segment,
                        combined = DurationStats.of(samples),
                        forward = DurationStats.of(samples.filter { it.direction > 0 }),
                        backward = DurationStats.of(samples.filter { it.direction < 0 }),
                    )
                }.sortedWith(compareByDescending<SegmentSummary> { it.sampleCount }.thenBy { it.segment.name ?: "" })
                trips to summaries
            }
            trips = loaded.first
            summaries = loaded.second
            loading = false
        }
    }

    fun start(context: Context, mode: TripMode) = Recorder.start(context, mode)

    fun stop(context: Context) = Recorder.stop(context)

    fun mark(context: Context) = Recorder.mark(context)

    // Screen-level reads. Screens call these from LaunchedEffect and keep the
    // result in their own state, so a detail page always shows what is on disk
    // right now instead of a stale copy from the list.

    suspend fun trip(id: Long): Trip? = withContext(container.dbDispatcher) {
        container.repository.trip(id)
    }

    suspend fun legs(tripId: Long): List<Leg> = withContext(container.dbDispatcher) {
        container.repository.legs(tripId)
    }

    suspend fun points(tripId: Long): List<TrackPoint> = withContext(container.dbDispatcher) {
        container.repository.points(tripId)
    }

    /**
     * Where the user pressed "split here" during the walk. The marks are stored
     * as timestamps, so the map position comes from the nearest fix — the same
     * mapping the segmenter uses when it cuts.
     */
    suspend fun markPositions(tripId: Long): List<LatLon> = withContext(container.dbDispatcher) {
        val points = container.repository.points(tripId)
        container.repository.marks(tripId).mapNotNull { mark ->
            Segmenter.nearestIndex(points, mark)?.let { points[it].at }
        }
    }

    suspend fun segment(id: Long): Segment? = withContext(container.dbDispatcher) {
        container.repository.segment(id)
    }

    suspend fun samples(segmentId: Long): List<TraversalSample> = withContext(container.dbDispatcher) {
        container.repository.samples(segmentId, settings.state.value.includePartial)
    }

    suspend fun legsForSegment(segmentId: Long): List<Leg> = withContext(container.dbDispatcher) {
        container.repository.legsForSegment(segmentId)
    }

    suspend fun identities(ids: List<Long>): Map<Long, MatchKind> = withContext(container.dbDispatcher) {
        container.repository.segmentIdentities(ids)
    }

    /**
     * Re-runs the whole pipeline of one trip and returns the fresh result.
     * Detail screens await this before reloading, so the map and the leg list
     * are rebuilt in the same pass the caller sees.
     */
    suspend fun analyze(tripId: Long): TripAnalyzer.Result? {
        val result = withContext(container.dbDispatcher) {
            runCatching { container.analyzer.analyze(tripId) }.getOrNull()
        }
        refresh()
        return result
    }

    fun deleteTrip(tripId: Long) {
        viewModelScope.launch {
            withContext(container.dbDispatcher) { container.repository.deleteTrip(tripId) }
            refresh()
        }
    }

    fun clearOsmCache(onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(container.dbDispatcher) { container.osm.clear() }
            onDone()
        }
    }

    suspend fun cachedTileCount(): Int = withContext(container.dbDispatcher) {
        container.osm.cachedTileCount()
    }

    // ---- In-app update -----------------------------------------------------

    /**
     * Asks GitHub for the rolling canary release and compares its commit count
     * with the installed build. A manual tap always fetches; the automatic
     * check respects the interval so opening the app does not burn the hourly
     * anonymous quota.
     */
    fun checkForUpdate(manual: Boolean = false) {
        val current = update
        if (current is UpdateState.Checking || current is UpdateState.Downloading) return
        if (!manual &&
            System.currentTimeMillis() - settings.state.value.lastUpdateCheckAt < AUTO_CHECK_INTERVAL_MS
        ) {
            return
        }
        viewModelScope.launch {
            update = UpdateState.Checking
            update = try {
                val release = updateChecker.latest()
                settings.update { it.copy(lastUpdateCheckAt = System.currentTimeMillis()) }
                when {
                    release == null -> UpdateState.NoRelease
                    else -> when (val newer = isNewerThanInstalled(release)) {
                        // A stamp we cannot read is an error, never "up to date":
                        // silently treating it as old would hide every release.
                        null -> UpdateState.Failed(UpdateError.VERSION)
                        true -> UpdateState.Available(release)
                        false -> UpdateState.UpToDate(release)
                    }
                }
            } catch (e: UpdateException) {
                UpdateState.Failed(e.error)
            }
        }
    }

    fun downloadUpdate() {
        val available = update as? UpdateState.Available ?: return
        viewModelScope.launch {
            update = UpdateState.Downloading(available.release, 0)
            update = try {
                val apk = updateDownloader.download(available.release.asset, updatesDir, mirrors) { percent ->
                    update = UpdateState.Downloading(available.release, percent)
                }
                UpdateState.Ready(available.release, apk)
            } catch (e: UpdateException) {
                UpdateState.Failed(e.error)
            }
        }
    }

    fun installUpdate() {
        val ready = update as? UpdateState.Ready ?: return
        update = when (UpdateInstaller.install(container.appContext, ready.apk)) {
            InstallOutcome.LAUNCHED -> ready
            InstallOutcome.NEEDS_PERMISSION -> ready.copy(needsPermission = true)
            InstallOutcome.UNAVAILABLE -> UpdateState.Failed(UpdateError.INSTALL)
        }
    }

    /**
     * `env` is the commit count and the versionCode, so it alone decides.
     * Returns null when the installed stamp cannot be read — the caller must
     * report that instead of quietly claiming the device is up to date.
     */
    private fun isNewerThanInstalled(release: ReleaseInfo): Boolean? {
        val installed = installedVersion() ?: return null
        return release.version > installed
    }

    private fun installedVersion(): AppVersion? = runCatching {
        val info = container.appContext.packageManager
            .getPackageInfo(container.appContext.packageName, 0)
        AppVersion.parse(info.versionName.orEmpty())
    }.getOrNull()


    /** Everything the user owns, in one portable JSON document. */
    suspend fun exportJson(): String = withContext(container.dbDispatcher) {
        val root = JSONObject()
        root.put("format", "map-of-time/1")
        root.put("exportedAt", System.currentTimeMillis())

        val segmentArray = JSONArray()
        for (summary in summaries) {
            val segment = summary.segment
            val samples = repository.samples(segment.id)
            val sampleArray = JSONArray()
            for (sample in samples) {
                sampleArray.put(
                    JSONObject().apply {
                        put("startedAt", sample.startedAt)
                        put("durationMs", sample.durationMs)
                        put("movingMs", sample.movingMs)
                        put("stoppedMs", sample.stoppedMs)
                        put("stopCount", sample.stopCount)
                        put("distanceM", sample.distanceM)
                        put("direction", sample.direction)
                    },
                )
            }
            segmentArray.put(
                JSONObject().apply {
                    put("key", segment.key)
                    put("mode", segment.mode.id)
                    put("name", segment.name)
                    put("identity", segment.identity.name)
                    put("lengthM", segment.lengthM)
                    put("fromLat", segment.aLat)
                    put("fromLon", segment.aLon)
                    put("toLat", segment.bLat)
                    put("toLon", segment.bLon)
                    put("medianMs", summary.combined.medianMs)
                    put("confidence", summary.combined.confidence.name)
                    put("samples", sampleArray)
                },
            )
        }
        root.put("segments", segmentArray)

        val tripArray = JSONArray()
        for (trip in repository.trips()) {
            val legArray = JSONArray()
            for (leg in repository.legs(trip.id)) {
                legArray.put(
                    JSONObject().apply {
                        put("startMs", leg.startMs)
                        put("endMs", leg.endMs)
                        put("distanceM", leg.distanceM)
                        put("segmentId", leg.segmentId)
                        put("label", leg.label)
                        put("partial", leg.partial)
                        put("movingMs", leg.movingMs)
                        put("stoppedMs", leg.stoppedMs)
                        put("stopCount", leg.stopCount)
                        put("longestStopMs", leg.longestStopMs)
                        put("polyline", leg.polyline)
                    },
                )
            }
            tripArray.put(
                JSONObject().apply {
                    put("id", trip.id)
                    put("mode", trip.mode.id)
                    put("startedAt", trip.startedAt)
                    put("endedAt", trip.endedAt)
                    put("distanceM", trip.distanceM)
                    put("status", trip.status.name)
                    put("legs", legArray)
                },
            )
        }
        root.put("trips", tripArray)
        root.toString(2)
    }

    private companion object {
        /** Six hours: frequent enough to notice a canary, quiet enough for the API. */
        const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
