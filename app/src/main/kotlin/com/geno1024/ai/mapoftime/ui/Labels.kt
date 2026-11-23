package com.geno1024.ai.mapoftime.ui

import android.content.Context
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.core.TripMode

fun TripMode.label(context: Context): String = context.getString(
    when (this) {
        TripMode.WALK -> R.string.mode_walk
        TripMode.BIKE -> R.string.mode_bike
        TripMode.EBIKE -> R.string.mode_ebike
        TripMode.DRIVE -> R.string.mode_drive
        TripMode.BUS -> R.string.mode_bus
    },
)
