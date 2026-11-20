package com.geno1024.ai.mapoftime.track

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.location.LocationManagerCompat
import com.geno1024.ai.mapoftime.core.LatLon
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Where the device is, for maps that have nothing better to look at.
 *
 * Deliberately not a location client: nothing is subscribed to, no provider is
 * kept warm, and no permission is asked for. Opening a map is not a reason to
 * start the GPS; asking for one fix when the map is otherwise blank is. When
 * the user grants location to record a trip, this gets it for free.
 */
object Locator {
    /** A fix from the last couple of minutes, reused so tab switches cost nothing. */
    private var memo: LatLon? = null
    private var memoAt = 0L

    private const val MEMO_MS = 2 * 60 * 1000L

    /** The fix [current] found recently, or `null` when it is old or absent. */
    fun remembered(): LatLon? =
        memo?.takeIf { System.currentTimeMillis() - memoAt < MEMO_MS }

    /**
     * The freshest fix already known to the system, without waking anything.
     *
     * The permission check is [TrackPermissions.hasLocation] a line below, and
     * every provider read is wrapped, because lint only recognises the inline
     * `checkSelfPermission` form.
     */
    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context): LatLon? {
        if (!TrackPermissions.hasLocation(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val best = runCatching {
            manager.getProviders(true).mapNotNull { provider ->
                // An unknown or disabled provider throws; one dead provider
                // must not take the others with it.
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }.maxByOrNull { it.time }
        }.getOrNull() ?: return null
        return LatLon(best.latitude, best.longitude)
    }

    /**
     * Asks for a single fix and waits at most [timeoutMs] for it. `null` when
     * permission is missing, no provider is on, or nothing arrives in time —
     * a map with no location is still a usable map.
     */
    suspend fun current(context: Context, timeoutMs: Long = 8_000L): LatLon? {
        if (!TrackPermissions.hasLocation(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = provider(manager) ?: return null
        val fix = withTimeoutOrNull(timeoutMs) { fixFrom(manager, provider) } ?: return null
        return LatLon(fix.latitude, fix.longitude).also {
            memo = it
            memoAt = System.currentTimeMillis()
        }
    }

    /** GPS when it is on, then the network; the passive provider only repeats what others saw. */
    private fun provider(manager: LocationManager): String? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    /**
     * One update through the compat helper: on API 30+ it is the platform's
     * own one-shot request, below that the helper registers a listener and
     * unregisters it after the first fix.
     */
    private suspend fun fixFrom(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            // Cancelling the signal is what stops the request when the timeout
            // fires or the screen leaves composition.
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    provider,
                    signal,
                    Executor { it.run() },
                ) { fix ->
                    if (continuation.isActive) continuation.resume(fix)
                }
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            } catch (_: IllegalArgumentException) {
                // The provider disappeared between isProviderEnabled and now.
                if (continuation.isActive) continuation.resume(null)
            }
        }
}
