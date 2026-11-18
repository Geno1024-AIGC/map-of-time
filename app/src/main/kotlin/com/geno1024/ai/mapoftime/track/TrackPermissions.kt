package com.geno1024.ai.mapoftime.track

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object TrackPermissions {
    fun hasLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasNotifications(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Only what still needs asking; requesting a granted permission is a no-op but noisy in the log. */
    fun missing(context: Context): Array<String> = buildList {
        if (!hasLocation(context)) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (!hasNotifications(context)) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}
