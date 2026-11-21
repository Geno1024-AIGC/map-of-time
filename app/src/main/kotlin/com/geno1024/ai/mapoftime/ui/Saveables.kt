package com.geno1024.ai.mapoftime.ui

import androidx.compose.runtime.saveable.Saver

/**
 * Persist an enum by its name rather than relying on the platform's automatic
 * (Serializable) saver. A String is cheap and keeps screen state identical
 * across save/restore regardless of how the value was constructed.
 */
fun <T : Enum<T>> enumNameSaver(values: Array<T>): Saver<T, String> = Saver(
    save = { it.name },
    restore = { name -> values.firstOrNull { it.name == name } },
)

/** Nullable flavour of [enumNameSaver]; an empty String means null. */
fun <T : Enum<T>> nullableEnumNameSaver(values: Array<T>): Saver<T?, String> = Saver(
    save = { it?.name.orEmpty() },
    restore = { name -> if (name.isEmpty()) null else values.firstOrNull { it.name == name } },
)

/** Persist a set of ids as a list of longs, both natively saveable types. */
val longSetSaver: Saver<Set<Long>, List<Long>> = Saver(
    save = { it.toList() },
    restore = { it.toSet() },
)
