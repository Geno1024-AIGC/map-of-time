package com.geno1024.ai.mapoftime.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.track.Locator

/**
 * The device position, for maps that would otherwise open on the middle of
 * nowhere.
 *
 * The last fix the system already has is shown at once — a map that waits two
 * seconds to be in the right city is worse than one that is right immediately
 * and refines. A single fresh fix then replaces it for the case where that
 * last one is from yesterday, in another city. No permission is requested:
 * without it, this is simply `null` and the map behaves as before.
 */
@Composable
fun rememberHere(): LatLon? {
    val context = LocalContext.current
    var here by remember { mutableStateOf(Locator.remembered() ?: Locator.lastKnown(context)) }
    LaunchedEffect(Unit) {
        // A fix from the last couple of minutes is good enough; asking for
        // another one on every tab switch would be a GPS request per tap.
        if (Locator.remembered() != null) return@LaunchedEffect
        val fix = Locator.current(context) ?: return@LaunchedEffect
        here = fix
    }
    return here
}
