package com.geno1024.ai.mapoftime.update

import java.io.File

/**
 * Where the update flow currently stands. Held by the view model so the
 * settings card and the tab badge read the same truth.
 */
sealed interface UpdateState {
    /** No check has run yet in this process. */
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** The release tag does not exist, e.g. while CI is recreating it. */
    data object NoRelease : UpdateState

    /** [release] is the published build; null when nothing is published yet. */
    data class UpToDate(val release: ReleaseInfo?) : UpdateState

    data class Available(val release: ReleaseInfo) : UpdateState

    data class Downloading(val release: ReleaseInfo, val percent: Int) : UpdateState

    data class Ready(
        val release: ReleaseInfo,
        val apk: File,
        /** The user came back from the "install unknown apps" screen. */
        val needsPermission: Boolean = false,
    ) : UpdateState

    data class Failed(val error: UpdateError) : UpdateState
}
