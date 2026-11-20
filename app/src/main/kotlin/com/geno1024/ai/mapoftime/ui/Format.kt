package com.geno1024.ai.mapoftime.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.core.Confidence
import com.geno1024.ai.mapoftime.core.DayKind
import com.geno1024.ai.mapoftime.core.HolidayBlock
import com.geno1024.ai.mapoftime.core.HolidayPhase
import com.geno1024.ai.mapoftime.core.PeakHour
import com.geno1024.ai.mapoftime.core.TimeBucket
import com.geno1024.ai.mapoftime.core.TripMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** `4 min 12 s`, `1 h 07 min`, `42 s` — compact, no zeros padded. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "%d h %02d min".format(hours, minutes)
        minutes > 0 -> "%d min %02d s".format(minutes, seconds)
        else -> "%d s".format(seconds)
    }
}

/** Seconds alone, for tables of short segments. */
fun formatSeconds(ms: Long): String = "${(ms / 1000.0).roundToInt()} s"

fun formatDistance(meters: Double): String = when {
    meters < 1000 -> "${meters.roundToInt()} m"
    else -> "%.2f km".format(meters / 1000)
}

fun formatDistanceShort(meters: Double): String =
    if (meters < 1000) "${meters.roundToInt()} m" else "%.1f km".format(meters / 1000)

fun formatPace(secondsPerKm: Double): String {
    if (secondsPerKm <= 0) return "—"
    val total = secondsPerKm.roundToInt()
    return "%d:%02d /km".format(total / 60, total % 60)
}

private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

fun formatDateTime(epochMs: Long): String =
    dateTimeFormatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/**
 * `2027-02-06 Sat` — a holiday is typed as a date, so the weekday is what the
 * user actually checks before pressing add.
 */
fun formatHolidayDay(date: LocalDate): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd EEE", Locale.getDefault()).format(date)

fun formatHolidayBlock(block: HolidayBlock): String =
    if (block.first == block.last) {
        formatHolidayDay(block.first)
    } else {
        formatHolidayDay(block.first) + " – " + formatHolidayDay(block.last)
    }

fun formatDate(epochMs: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

fun formatClock(epochMs: Long): String =
    DateTimeFormatter.ofPattern("HH:mm:ss")
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** `+2.3 %`, used to say how far a direction differs from the combined median. */
fun formatPercentDelta(value: Long, reference: Long): String {
    if (reference <= 0) return "—"
    val percent = (value - reference) * 100.0 / reference
    val sign = if (percent >= 0) "+" else "−"
    return "%s%.1f %%".format(sign, abs(percent))
}

@Composable
fun confidenceLabel(confidence: Confidence): String = stringResource(
    when (confidence) {
        Confidence.NONE -> R.string.confidence_none
        Confidence.LOW -> R.string.confidence_low
        Confidence.MEDIUM -> R.string.confidence_medium
        Confidence.HIGH -> R.string.confidence_high
    },
)

@Composable
fun modeLabel(mode: TripMode): String = stringResource(
    when (mode) {
        TripMode.WALK -> R.string.mode_walk
        TripMode.BIKE -> R.string.mode_bike
        TripMode.EBIKE -> R.string.mode_ebike
        TripMode.DRIVE -> R.string.mode_drive
    },
)

@Composable
fun timeBucketLabel(bucket: TimeBucket): String = stringResource(
    when (bucket) {
        TimeBucket.MORNING -> R.string.bucket_morning
        TimeBucket.DAY -> R.string.bucket_day
        TimeBucket.EVENING -> R.string.bucket_evening
        TimeBucket.NIGHT -> R.string.bucket_night
    },
)

@Composable
fun dayKindLabel(day: DayKind): String = stringResource(
    when (day) {
        DayKind.WEEKDAY -> R.string.day_weekday
        DayKind.WEEKEND -> R.string.day_weekend
    },
)

@Composable
fun peakLabel(peak: PeakHour): String = stringResource(
    when (peak) {
        PeakHour.PEAK -> R.string.peak_peak
        PeakHour.OFF_PEAK -> R.string.peak_off
    },
)

@Composable
fun holidayPhaseLabel(phase: HolidayPhase): String = stringResource(
    when (phase) {
        HolidayPhase.NONE -> R.string.holiday_none
        HolidayPhase.EVE -> R.string.holiday_eve
        HolidayPhase.DURING -> R.string.holiday_during
        HolidayPhase.LAST -> R.string.holiday_last
    },
)

fun modeLabel(context: Context, mode: TripMode): String = mode.label(context)
