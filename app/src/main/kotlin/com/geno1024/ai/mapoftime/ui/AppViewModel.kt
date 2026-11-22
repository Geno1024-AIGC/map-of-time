package com.geno1024.ai.mapoftime.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geno1024.ai.mapoftime.AppContainer
import com.geno1024.ai.mapoftime.analysis.PlaceDraft
import com.geno1024.ai.mapoftime.analysis.PlaceDrafts
import com.geno1024.ai.mapoftime.analysis.PointDetail
import com.geno1024.ai.mapoftime.analysis.PointDetails
import com.geno1024.ai.mapoftime.analysis.EdgeSummary
import com.geno1024.ai.mapoftime.analysis.TripAnalyzer
import com.geno1024.ai.mapoftime.core.BucketTake
import com.geno1024.ai.mapoftime.core.DurationStats
import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.HolidayCalendar
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.Segmenter
import com.geno1024.ai.mapoftime.core.TrackPoint
import com.geno1024.ai.mapoftime.core.TrafficContext
import com.geno1024.ai.mapoftime.core.TrafficDay
import com.geno1024.ai.mapoftime.core.TraversalSample
import com.geno1024.ai.mapoftime.core.TripMode
import com.geno1024.ai.mapoftime.core.groupByTrafficContext
import com.geno1024.ai.mapoftime.core.trafficDayOf
import com.geno1024.ai.mapoftime.data.DangerCounts
import com.geno1024.ai.mapoftime.data.Leg
import com.geno1024.ai.mapoftime.data.MatchKind
import com.geno1024.ai.mapoftime.data.EXPORT_FORMAT
import com.geno1024.ai.mapoftime.data.ImportResult
import com.geno1024.ai.mapoftime.data.Place
import com.geno1024.ai.mapoftime.data.PointRef
import com.geno1024.ai.mapoftime.data.Road
import com.geno1024.ai.mapoftime.data.Edge
import com.geno1024.ai.mapoftime.data.Trip
import com.geno1024.ai.mapoftime.data.TripImport
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
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
    var summaries by mutableStateOf<List<EdgeSummary>>(emptyList())
        private set
    var places by mutableStateOf<List<Place>>(emptyList())
        private set
    var roads by mutableStateOf<List<Road>>(emptyList())
        private set

    /** Names the user gave a single point; these outrank a junction's name. */
    var pointNames by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    /**
     * Roads the user chose at each point, keyed by `kind:node`. Several roads
     * describe a crossing and are shown as `road × road`; never typed into a
     * point name where they would leak into a segment's pair name.
     */
    var pointRoads by mutableStateOf<Map<String, List<String>>>(emptyMap())
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
                val edges = container.repository.edges()
                val includePartial = settings.state.value.includePartial
                val calendar = settings.state.value.holidayCalendar
                val byMode = TripMode.entries.associateWith {
                    container.repository.samplesByMode(it, includePartial)
                }
                val summaries = edges.map { edge ->
                    val samples = byMode[edge.mode]?.get(edge.id).orEmpty()
                    val forward = samples.filter { it.direction > 0 }
                    val backward = samples.filter { it.direction < 0 }
                    EdgeSummary(
                        edge = edge,
                        combined = DurationStats.of(samples),
                        forward = DurationStats.of(forward),
                        backward = DurationStats.of(backward),
                        forwardTakes = takesByContext(forward, calendar),
                        backwardTakes = takesByContext(backward, calendar),
                        forwardFridayRush = takeOn(forward, calendar, TrafficDay.FRIDAY_RUSH),
                        backwardFridayRush = takeOn(backward, calendar, TrafficDay.FRIDAY_RUSH),
                    )
                }.sortedWith(compareByDescending<EdgeSummary> { it.sampleCount }.thenBy { it.edge.displayName })
                Loaded(
                    trips = trips,
                    summaries = summaries,
                    places = container.repository.places(),
                    roads = container.repository.roads(),
                    pointNames = container.repository.pointNames(),
                    pointRoads = container.repository.pointRoads(),
                )
            }
            trips = loaded.trips
            summaries = loaded.summaries
            places = loaded.places
            roads = loaded.roads
            pointNames = loaded.pointNames
            pointRoads = loaded.pointRoads
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

    /** Timestamps of the manual splits, so they can be removed individually. */
    suspend fun markTimes(tripId: Long): List<Long> = withContext(container.dbDispatcher) {
        container.repository.marks(tripId)
    }

    suspend fun edge(id: Long): Edge? = withContext(container.dbDispatcher) {
        container.repository.edge(id)
    }

    /**
     * Names an edge (or clears the road-name input when [name] is blank) and
     * rewrites the labels of its legs, so every screen shows the new name.
     */
    suspend fun renameEdge(edgeId: Long, name: String?) {
        withContext(container.dbDispatcher) { container.repository.renameEdge(edgeId, name) }
        refresh()
    }

    suspend fun setEdgeRoad(edgeId: Long, roadId: Long?) {
        withContext(container.dbDispatcher) { container.repository.setEdgeRoad(edgeId, roadId) }
        refresh()
    }

    /**
     * Road names already given to some edge. Offered as one-tap chips when
     * naming another piece of the same road, so a name is typed once, not once
     * per edge. Derived from what is on disk, never a second stored copy.
     */
    val roadNames: List<String>
        get() = summaries.mapNotNull { it.edge.userName?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .sorted()

    /**
     * Names one end of a segment. Every segment through that point changes
     * name too, which is the whole point of naming the junction once.
     *
     * [roads] is the confirmed road list for the point: null leaves the roads
     * alone (a plain rename), a list — including an empty one — replaces them.
     */
    suspend fun renamePoint(
        ref: PointRef,
        name: String?,
        roads: Collection<String>? = null,
    ) {
        withContext(container.dbDispatcher) {
            container.repository.renamePoint(ref, name)
            if (roads != null) container.repository.setPointRoads(listOf(ref), roads)
        }
        refresh()
    }

    /**
     * Roads worth offering when [ref] is named: the road names on segments that
     * actually touch it, plus roads already chosen at other points of the same
     * junction. These are candidates only — nothing is saved until the user
     * confirms a selection through [renamePoint] or [namePlace].
     */
    fun pointRoadCandidates(ref: PointRef): List<String> = pointRoadCandidatesFor(listOf(ref))

    /** The same inference over every point a junction covers. */
    fun pointRoadCandidatesFor(refs: Collection<PointRef>): List<String> {
        val keys = refs.map { it.key }.toSet()
        val fromSegments = summaries.map { it.edge }
            .filter { it.fromKey in keys || it.toKey in keys }
            .mapNotNull { it.userName?.trim()?.takeIf(String::isNotBlank) }
        val neighbours = refs.flatMap { ref ->
            val place = placeOf(ref)
            if (place != null) {
                place.points.flatMap { pointRoads[it.key].orEmpty() }
            } else {
                pointRoads[ref.key].orEmpty()
            }
        }
        val current = refs.flatMap { pointRoads[it.key].orEmpty() }
        return (fromSegments + neighbours + current).distinct().sorted()
    }

    /** Roads already chosen at these points, used to seed the selection in a dialog. */
    fun roadsAt(refs: Collection<PointRef>): Set<String> =
        refs.flatMap { pointRoads[it.key].orEmpty() }.toSet()

    /** Persists a confirmed road selection for one or more points of a junction. */
    suspend fun setPointRoads(refs: Collection<PointRef>, roads: Collection<String>) {
        withContext(container.dbDispatcher) { container.repository.setPointRoads(refs, roads) }
        refresh()
    }

    /**
     * What the app proposes to call "this junction" when the user taps [ref]:
     * the point, the points around it, and the name they may already share.
     * Cheap enough to recompute on a tap; the lists are small.
     *
     * [reachM] is the preview's "wider" step: the same rules, scaled up, for
     * the interchanges that really are 300 m across.
     */
    fun placeDraft(
        ref: PointRef,
        at: LatLon? = null,
        reachM: Double = PlaceDrafts.SPAN_M,
        manual: Map<String, Boolean> = emptyMap(),
    ): PlaceDraft? = PlaceDrafts.draft(
        ref,
        summaries.map { it.edge },
        places,
        at,
        reachM,
        manual,
        pointNames.keys,
    )

    fun placeOf(ref: PointRef): Place? = places.firstOrNull { place ->
        place.points.any { it.key == ref.key }
    }

    fun placeName(id: Long?): String? = id?.let { wanted ->
        places.firstOrNull { it.id == wanted }?.name
    }

    /**
     * The auto-derived caption for one point of a wide junction: which side of
     * the junction it is on and which roads meet there. While a new junction is
     * being previewed, [context] supplies the centre before anything is saved.
     * A point with no saved junction still gets a caption when the roads
     * meeting there are named: that is how an unnamed crossing is identified.
     */
    fun pointDetail(
        key: String,
        at: LatLon,
        context: PlaceDraft? = null,
        mode: TripMode? = null,
    ): PointDetail? {
        val ref = PointRef.parse(key) ?: return null
        val place = placeOf(ref)
        // With no junction there is no "side" to name, but the roads through
        // the point still say what it is. Using the point as its own centre
        // drops the bearing and leaves just the crossing caption.
        val centre = context?.center ?: place?.at ?: at
        val placeName = context?.existingPlaceId?.let(::placeName) ?: place?.name
        val touching = summaries.map { it.edge }
            .filter { it.fromKey == key || it.toKey == key }
            // A caption describes one traveller's roads: a car must not inherit
            // the footway name just because a zebra shares the same point.
            .filter { mode == null || it.mode == mode }
        // Roads the user chose win over anything derived from the segments.
        val explicitRoads = pointRoads[key].orEmpty() +
            place?.points.orEmpty().flatMap { pointRoads[it.key].orEmpty() }
        return PointDetails.of(at, centre, placeName, touching, explicitRoads)
    }

    /**
     * Names a whole junction in one go. A blank [name] dissolves it and the
     * points fall back to their own names or to the road they sit on.
     *
     * [roads] is the confirmed road list for the crossing: null leaves roads
     * untouched, a list (even empty) replaces them on every member.
     */
    suspend fun namePlace(
        draft: PlaceDraft,
        name: String?,
        clearMemberNames: Boolean = false,
        roads: Collection<String>? = null,
    ) {
        withContext(container.dbDispatcher) {
            // The name is typed by hand. If a nearby junction already carries
            // it, the user is adding this point to that junction — not asking
            // for a second row with the same name. Merging here is what makes
            // "write the same name on both halves" actually work the first
            // time, instead of leaving two rows that then protect each other.
            val target = PlaceDrafts.sameNameTarget(places, name, draft)
            val placeId = target?.id ?: draft.existingPlaceId
            val refs = (draft.refs + target?.points.orEmpty()).distinct()
            val absorbIds = (
                draft.absorbIds +
                    listOfNotNull(draft.existingPlaceId).filter { it != placeId } +
                    listOfNotNull(target?.id).filter { it != placeId }
            ).distinct()

            // Recompute the centre and radius over every member now that a
            // same-name junction's points have been folded in; using the
            // single-point draft's values would leave adoption too narrow.
            val coords = HashMap<String, LatLon>()
            for (summary in summaries) {
                val segment = summary.edge
                segment.fromKey?.let { coords.putIfAbsent(it, segment.from) }
                segment.toKey?.let { coords.putIfAbsent(it, segment.to) }
            }
            val located = refs.mapNotNull { ref -> coords[ref.key]?.let { ref to it } }
            val center = if (located.isEmpty()) {
                draft.center
            } else {
                LatLon(
                    located.map { it.second.lat }.average(),
                    located.map { it.second.lon }.average(),
                )
            }
            val radius = if (located.isEmpty()) {
                draft.radiusM
            } else {
                located.maxOf { Geo.distanceMeters(center, it.second) }
            }

            container.repository.upsertPlace(
                id = placeId,
                name = name,
                members = refs,
                center = center,
                radiusM = maxOf(radius, draft.radiusM).coerceAtLeast(PlaceDrafts.MIN_ADOPT_M),
                clearMemberNames = clearMemberNames,
                roads = roads,
                absorbIds = absorbIds,
            )
        }
        refresh()
    }

    /** How many points of [draft] were named one by one before junctions had names. */
    fun overriddenMembers(draft: PlaceDraft): Int =
        draft.refs.count { pointNames.containsKey(it.key) }

    suspend fun samples(edgeId: Long): List<TraversalSample> = withContext(container.dbDispatcher) {
        container.repository.samples(edgeId, settings.state.value.includePartial)
    }

    suspend fun legsForEdge(edgeId: Long): List<Leg> = withContext(container.dbDispatcher) {
        container.repository.legsForEdge(edgeId)
    }


    suspend fun identities(ids: List<Long>): Map<Long, MatchKind> = withContext(container.dbDispatcher) {
        container.repository.edgeIdentities(ids)
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

    /**
     * Re-analyses many trips back to back. Sequential on purpose: the analyzer
     * may download OSM tiles, and two overlapping analyses would both fetch the
     * same tile and race on the cache. Progress is reported on the caller's
     * dispatcher so a list can show "12 / 30" while it works.
     */
    suspend fun analyzeAll(
        tripIds: List<Long>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        var done = 0
        for (id in tripIds) {
            withContext(container.dbDispatcher) {
                runCatching { container.analyzer.analyze(id) }
            }
            done++
            onProgress(done, tripIds.size)
        }
        refresh()
        return done
    }

    /**
     * Adds a manual split to a recording that has already finished. The mark is
     * the same shape the recorder writes during a trip, so the segmenter cuts
     * at this timestamp on the next analysis. Repeated taps on the same fix are
     * ignored: two marks a second apart would mean the same cut twice.
     */
    suspend fun splitTripAt(tripId: Long, t: Long): TripAnalyzer.Result? {
        withContext(container.dbDispatcher) {
            if (container.repository.marks(tripId).none { abs(it - t) < 1_000L }) {
                container.repository.addMark(tripId, t)
            }
        }
        return analyze(tripId)
    }

    /** Removes one manual split and re-runs the analysis without it. */
    suspend fun removeSplitAt(tripId: Long, t: Long): TripAnalyzer.Result? {
        withContext(container.dbDispatcher) { container.repository.deleteMark(tripId, t) }
        return analyze(tripId)
    }

    /**
     * Removes the tail of a recording (fixes after the user's chosen cutoff)
     * and rebuilds legs, distance and end time from what remains.
     */
    suspend fun trimTripTail(tripId: Long, keepCount: Int): TripAnalyzer.Result? {
        withContext(container.dbDispatcher) {
            container.repository.trimTripTail(tripId, keepCount)
        }
        return analyze(tripId)
    }

    /** Changes the mode and re-runs the analysis with that mode's thresholds. */
    suspend fun setTripMode(tripId: Long, mode: TripMode): TripAnalyzer.Result? {
        // Changing the mode without re-cutting would leave legs pointing at
        // segments of the old mode. Imported trips have no raw fixes, so they
        // cannot be re-cut at all.
        val canAnalyze = withContext(container.dbDispatcher) {
            container.repository.points(tripId).size >= 2
        }
        if (!canAnalyze) return null
        withContext(container.dbDispatcher) { container.repository.setTripMode(tripId, mode) }
        return analyze(tripId)
    }

    suspend fun setTripNote(tripId: Long, note: String?) {
        withContext(container.dbDispatcher) {
            container.repository.setTripNote(tripId, note?.trim()?.takeIf { it.isNotEmpty() })
        }
        refresh()
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

    // ---- Danger Zone -------------------------------------------------------

    /** Read on the DB lane so a dialog never quotes a count from a stale list. */
    suspend fun dangerCounts(): DangerCounts = withContext(container.dbDispatcher) {
        container.repository.dangerCounts()
    }

    /** Each action writes on the single DB lane, then reloads the whole screen. */
    fun clearPointNames(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val removed = withContext(container.dbDispatcher) { container.repository.clearPointNames() }
            refresh()
            onDone(removed)
        }
    }

    fun clearPlaces(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val removed = withContext(container.dbDispatcher) { container.repository.clearPlaces() }
            refresh()
            onDone(removed)
        }
    }

    fun clearRoadNames(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val removed = withContext(container.dbDispatcher) { container.repository.clearRoadNames() }
            refresh()
            onDone(removed)
        }
    }

    fun renameRoad(id: Long, name: String) {
        viewModelScope.launch {
            withContext(container.dbDispatcher) { container.repository.renameRoad(id, name) }
            refresh()
        }
    }

    fun deleteRoad(id: Long) {
        viewModelScope.launch {
            withContext(container.dbDispatcher) { container.repository.deleteRoad(id) }
            refresh()
        }
    }

    fun clearTrips(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val removed = withContext(container.dbDispatcher) { container.repository.clearTrips() }
            refresh()
            onDone(removed)
        }
    }

    fun clearAllData(onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(container.dbDispatcher) {
                container.repository.clearAllData()
                container.osm.clear()
            }
            refresh()
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
                when (val newer = isNewerThanInstalled(release)) {
                    // A stamp we cannot read is an error, never "up to date":
                    // silently treating it as old would hide every release.
                    null -> UpdateState.Failed(UpdateError.VERSION)
                    true -> UpdateState.Available(release)
                    false -> UpdateState.UpToDate(release)
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
        root.put("format", EXPORT_FORMAT)
        root.put("exportedAt", System.currentTimeMillis())

        val roads = repository.roads()
        val roadsById = roads.associateBy { it.id }
        val nodes = repository.nodes()
        val junctions = repository.places()
        val junctionNameById = junctions.associate { it.id to it.name }

        val edgeArray = JSONArray()
        val edgeKeys = HashMap<Long, String>()
        for (summary in summaries) {
            val edge = summary.edge
            edgeKeys[edge.id] = edge.key
            val samples = repository.samples(edge.id)
            val sampleArray = JSONArray()
            for (sample in samples) {
                sampleArray.put(
                    JSONObject().apply {
                        put("startedAt", sample.startedAt)
                        put("durationMs", sample.durationMs)
                        put("movingMs", sample.movingMs)
                        put("stoppedMs", sample.stoppedMs)
                        put("stopCount", sample.stopCount)
                        put("signalWaitMs", sample.signalWaitMs)
                        put("signalStopCount", sample.signalStopCount)
                        put("distanceM", sample.distanceM)
                        put("direction", sample.direction)
                    },
                )
            }
            edgeArray.put(
                JSONObject().apply {
                    put("key", edge.key)
                    put("mode", edge.mode.id)
                    put("name", edge.name)
                    put("userName", edge.userName)
                    put("fromKey", edge.fromKey)
                    put("toKey", edge.toKey)
                    put("roadName", edge.roadId?.let { roadsById[it]?.name })
                    put("identity", edge.identity.name)
                    put("lengthM", edge.lengthM)
                    put("fromLat", edge.aLat)
                    put("fromLon", edge.aLon)
                    put("toLat", edge.bLat)
                    put("toLon", edge.bLon)
                    put("medianMs", summary.combined.medianMs)
                    put("confidence", summary.combined.confidence.name)
                    put("samples", sampleArray)
                },
            )
        }
        root.put("edges", edgeArray)

        val referencedNodeKeys = summaries.flatMapTo(HashSet()) { listOf(it.edge.fromKey, it.edge.toKey) }
        val nodeArray = JSONArray()
        for (node in nodes) {
            if (node.name == null && node.junctionId == null && node.ref.key !in referencedNodeKeys) continue
            nodeArray.put(
                JSONObject().apply {
                    put("kind", node.ref.kind)
                    put("nodeId", node.ref.nodeId)
                    put("lat", node.lat)
                    put("lon", node.lon)
                    node.name?.let { put("name", it) }
                    node.junctionId?.let { junctionNameById[it] }?.let { put("junctionName", it) }
                },
            )
        }
        root.put("nodes", nodeArray)

        val roadArray = JSONArray()
        for (road in roads) {
            roadArray.put(JSONObject().apply { put("name", road.name); put("source", road.source) })
        }
        root.put("roads", roadArray)

        val junctionArray = JSONArray()
        for (place in junctions) {
            val members = JSONArray()
            for (ref in place.points) members.put(JSONObject().apply { put("kind", ref.kind); put("nodeId", ref.nodeId) })
            junctionArray.put(
                JSONObject().apply {
                    put("name", place.name)
                    put("lat", place.at.lat)
                    put("lon", place.at.lon)
                    put("radiusM", place.radiusM)
                    put("points", members)
                },
            )
        }
        root.put("junctions", junctionArray)

        val tripArray = JSONArray()
        for (trip in repository.trips()) {
            val legArray = JSONArray()
            for (leg in repository.legs(trip.id)) {
                legArray.put(
                    JSONObject().apply {
                        put("startMs", leg.startMs)
                        put("endMs", leg.endMs)
                        put("distanceM", leg.distanceM)
                        put("edgeKey", leg.edgeId?.let { edgeKeys[it] })
                        put("label", leg.label)
                        put("partial", leg.partial)
                        put("movingMs", leg.movingMs)
                        put("stoppedMs", leg.stoppedMs)
                        put("stopCount", leg.stopCount)
                        put("longestStopMs", leg.longestStopMs)
                        put("signalWaitMs", leg.signalWaitMs)
                        put("signalStopCount", leg.signalStopCount)
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
                    put("note", trip.note)
                    put("legs", legArray)
                },
            )
        }
        root.put("trips", tripArray)
        root.toString(2)
    }

    /**
     * Reads a document produced by [exportJson]. Parsing happens off the main
     * thread because a long history can be a few megabytes of JSON.
     */
    suspend fun importJson(json: String): ImportResult? {
        val bundle = withContext(Dispatchers.Default) { TripImport.parse(json) } ?: return null
        val result = withContext(container.dbDispatcher) { container.repository.importBundle(bundle) }
        refresh()
        return result
    }

    private companion object {
        /** Six hours: frequent enough to notice a canary, quiet enough for the API. */
        const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}

/**
 * Median per part of the week. A context with no traversal is simply absent:
 * [Map.getValue] would throw, and a zero-length bucket is not a statistic.
 */
private fun takesByContext(
    samples: List<TraversalSample>,
    calendar: HolidayCalendar,
): Map<TrafficContext, BucketTake> =
    groupByTrafficContext(
        // Holidays and Friday evenings are left out for the same reason the
        // detail table leaves them out: "weekday peak" has to mean one thing.
        samples.filter { trafficDayOf(it.startedAt, calendar = calendar) == TrafficDay.ROUTINE },
        calendar = calendar,
    ).mapValues { (_, bucket) -> BucketTake.of(bucket) }

/** The median of the traversals that fall on one kind of day. */
private fun takeOn(
    samples: List<TraversalSample>,
    calendar: HolidayCalendar,
    day: TrafficDay,
): BucketTake = BucketTake.of(
    samples.filter { trafficDayOf(it.startedAt, calendar = calendar) == day },
)

/** Everything one background refresh loads, handed over in one go. */
private data class Loaded(
    val trips: List<Trip>,
    val summaries: List<EdgeSummary>,
    val places: List<Place>,
    val roads: List<Road>,
    val pointNames: Map<String, String>,
    val pointRoads: Map<String, List<String>>,
)
