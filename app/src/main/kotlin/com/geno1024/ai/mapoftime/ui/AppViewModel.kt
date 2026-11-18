package com.geno1024.ai.mapoftime.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geno1024.ai.mapoftime.AppContainer
import com.geno1024.ai.mapoftime.analysis.SegmentSummary
import com.geno1024.ai.mapoftime.core.DurationStats
import com.geno1024.ai.mapoftime.core.TripMode
import com.geno1024.ai.mapoftime.data.Trip
import com.geno1024.ai.mapoftime.track.Recorder
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            val loaded = withContext(container.dbDispatcher) {
                val trips = container.repository.trips()
                val segments = container.repository.segments()
                val byMode = TripMode.entries.associateWith { container.repository.samplesByMode(it) }
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

    fun reanalyze(tripId: Long) {
        viewModelScope.launch {
            val result = withContext(container.dbDispatcher) {
                runCatching { container.analyzer.analyze(tripId) }.getOrNull()
            }
            note = result?.let { "$it" }
            refresh()
        }
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

    /** Everything the user owns, in one portable JSON document. */
    suspend fun exportJson(): String = withContext(container.dbDispatcher) {
        val root = JSONObject()
        root.put("format", "map-of-time/1")
        root.put("exportedAt", System.currentTimeMillis())

        val segmentArray = JSONArray()
        val summariesById = summaries.associateBy { it.segment.id }
        for (summary in summaries) {
            val segment = summary.segment
            val samples = repository.samples(segment.id)
            val sampleArray = JSONArray()
            for (sample in samples) {
                sampleArray.put(
                    JSONObject().apply {
                        put("startedAt", sample.startedAt)
                        put("durationMs", sample.durationMs)
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
        root.put("segmentSummaries", summariesById.size)
        root.toString(2)
    }
}
