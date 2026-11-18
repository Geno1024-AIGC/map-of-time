package com.geno1024.ai.mapoftime

import android.app.Application
import com.geno1024.ai.mapoftime.analysis.TripAnalyzer
import com.geno1024.ai.mapoftime.data.AppSettings
import com.geno1024.ai.mapoftime.data.MapDb
import com.geno1024.ai.mapoftime.data.MapRepository
import com.geno1024.ai.mapoftime.data.OsmRepository
import com.geno1024.ai.mapoftime.osm.OverpassClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency container. One database, one set of settings, one analyzer
 * — shared by the UI, the recorder and the post-processing coroutines.
 */
class AppContainer(application: Application) {
    val settings = AppSettings(application)
    val db = MapDb(application)
    val repository = MapRepository(db)
    val osm = OsmRepository(db, clientProvider = { OverpassClient(settings.state.value.overpassEndpoint) })
    val analyzer = TripAnalyzer(repository, osm, settings = { settings.state.value })

    /** Outlives any single screen or service, so finishing a trip is never cancelled. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * All SQLite writes go through this single lane: a fix must be stored before
     * the analyzer reads the trip, and SQLite gives no ordering guarantees
     * between two `Dispatchers.IO` tasks.
     */
    val dbDispatcher = Dispatchers.IO.limitedParallelism(1)
}

class MapOfTimeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // A trip still marked as recording at process start has no live service
        // behind it: the walk was interrupted. Keep its fixes and analyze what
        // was collected instead of silently dropping it.
        container.scope.launch {
            val stranded = container.repository.trips()
                .filter { it.status == com.geno1024.ai.mapoftime.data.TripStatus.RECORDING }
            stranded.forEach { trip ->
                runCatching { container.analyzer.analyze(trip.id) }
            }
        }
    }
}
