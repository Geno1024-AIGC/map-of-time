package com.geno1024.ai.mapoftime.update

/**
 * The version name the build stamps: `<major>.<minor>.<env>.<pack>.<sha8>`.
 *
 * Only `env` orders. It is the commit count, which is exactly what the APK's
 * `versionCode` is, so "is this newer" can never disagree with what Android
 * would accept at install time. `pack` counts packaging attempts and `sha8`
 * identifies the revision; neither may decide an update.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val env: Int,
    val pack: Int,
    val sha8: String,
) : Comparable<AppVersion> {

    /** Debug builds carry `-debug`; the stamp itself is untouched. */
    val base: String get() = "$major.$minor.$env.$pack.$sha8"

    override fun compareTo(other: AppVersion): Int = env.compareTo(other.env)

    companion object {
        private val STAMP = Regex(
            """(\d+)\.(\d+)\.(\d+)\.(\d+)\.([0-9a-fA-F]{8})""",
        )

        /**
         * Finds the stamp anywhere in `raw`, so a file name such as
         * `map-of-time-0.1.37.7.82fdc33a-build13-82fdc33a.apk` or a version
         * name with `-debug` appended both parse.
         */
        fun parse(raw: String): AppVersion? {
            val match = STAMP.find(raw) ?: return null
            val (major, minor, env, pack) = match.destructured
            return AppVersion(
                major = major.toIntOrNull() ?: return null,
                minor = minor.toIntOrNull() ?: return null,
                env = env.toIntOrNull() ?: return null,
                pack = pack.toIntOrNull() ?: return null,
                sha8 = match.groupValues[5].lowercase(),
            )
        }
    }
}
