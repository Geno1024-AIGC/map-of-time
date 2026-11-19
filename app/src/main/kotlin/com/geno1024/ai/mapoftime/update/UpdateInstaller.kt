package com.geno1024.ai.mapoftime.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

enum class InstallOutcome {
    /** The system installer is on screen. */
    LAUNCHED,

    /** The user has to allow this app to install packages first. */
    NEEDS_PERMISSION,

    /** The cached APK vanished or no installer accepted the intent. */
    UNAVAILABLE,
}

/**
 * Hands a downloaded APK to the system package installer.
 *
 * Deliberately not a `PackageInstaller` session: the platform's own UI already
 * shows the version, the permissions and the signature check, and it stays
 * correct across OEM skins. Android still refuses any APK that is not signed
 * with the key of the installed app, so the download cannot replace the app
 * with somebody else's build.
 */
object UpdateInstaller {
    fun install(context: Context, apk: File): InstallOutcome {
        if (!apk.exists() || apk.length() == 0L) return InstallOutcome.UNAVAILABLE

        if (!context.packageManager.canRequestPackageInstalls()) {
            val perApp = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri(),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching { context.startActivity(perApp) }.isSuccess ||
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.isSuccess
            return if (opened) InstallOutcome.NEEDS_PERMISSION else InstallOutcome.UNAVAILABLE
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (runCatching { context.startActivity(intent) }.isSuccess) {
            InstallOutcome.LAUNCHED
        } else {
            InstallOutcome.UNAVAILABLE
        }
    }
}
