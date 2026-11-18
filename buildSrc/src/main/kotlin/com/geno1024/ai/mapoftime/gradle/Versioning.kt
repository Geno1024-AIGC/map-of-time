package com.geno1024.ai.mapoftime.gradle

import org.gradle.api.Project
import java.io.File
import java.io.RandomAccessFile

/**
 * A build stamp of the form `0.1.<env>.<pack>.<sha8>`, exactly as
 * `RULES/VERSION.md` prescribes:
 *
 * - `env` is the commit count, and it is the only segment that sorts. The same
 *   checkout therefore yields the same version name locally and on CI.
 * - `pack` counts packaging builds of this module: attempts, not successes.
 *   CI reuses the committed number instead of incrementing it.
 * - `sha8` ties the artifact back to its revision; it never sorts.
 */
data class Stamp(
    val env: String,
    val pack: String,
    val sha8: String,
) {
    val versionName: String get() = "$BASE_VERSION.$env.$pack.$sha8"

    /** Android `versionCode`: the ordering authority, never `pack`. */
    val versionCode: Int get() = (env.toIntOrNull() ?: 0).coerceAtLeast(1)

    companion object {
        /** Only moves when the capability surface does. */
        const val BASE_VERSION = "0.1"
    }
}

object Versioning {
    private const val NO_HEAD = "00000000"

    /** `build` is an aggregate and exists everywhere; as a prefix it also hits `buildEnvironment`. */
    private val PACKAGING_EXACT = setOf("build")

    /** Android: every variant reaches the APK/AAB through one of these. */
    private val ANDROID_PACKAGING_PREFIXES = listOf("assemble", "bundle", "package", "install")

    /** JVM: exact names only, so a bare `assembleDebug` does not count here. */
    private val JVM_PACKAGING_EXACT = setOf("assemble", "jar", "installDist", "distTar", "distZip")

    /** JVM: names that only ever appear with a suffix. */
    private val JVM_PACKAGING_PREFIXES = listOf("shadowJar", "dist", "publish")

    fun stamp(root: File, moduleDir: File): Stamp = Stamp(
        env = env(root),
        pack = packOf(moduleDir),
        sha8 = sha8(root),
    )

    /** Commit count first, per the rule that local and CI must agree. */
    fun env(root: File): String =
        git(root, "rev-list", "--count", "HEAD")
            ?: System.getenv("GITHUB_RUN_NUMBER")
            ?: "0"

    fun sha8(root: File): String =
        git(root, "rev-parse", "--short=8", "HEAD") ?: NO_HEAD

    fun packFile(moduleDir: File) = File(moduleDir, "count.pack")

    private fun packOf(moduleDir: File): String {
        val file = packFile(moduleDir)
        return if (file.exists()) file.readText().trim().ifEmpty { "0" } else "0"
    }

    /**
     * Counts this invocation as a packaging build of the module. Call it before
     * [stamp], so the artifact carries its own one-based number and the file
     * equals the number of packaging builds that have happened.
     *
     * The count is taken at configuration time, which is why it records
     * *requests* and not successes: a build that fails to compile, cannot sign
     * or is interrupted after configuration already has its number. Query-only
     * tasks (`tasks`, `help`, …) and `--dry-run` stay out. `-Ppack.bump=true|false`
     * forces the counter on or off; CI skips it by default, because an increment
     * that is never written back would invent a number nobody has seen.
     */
    fun bumpOnPackaging(project: Project, moduleDir: File) {
        val params = project.gradle.startParameter
        if (params.isDryRun) return
        val forced = params.projectProperties["pack.bump"]?.toBoolean()
        if (forced == false) return
        if (forced == null && !System.getenv("CI").isNullOrEmpty()) return
        if (params.taskNames.none { requestsPackaging(project, it) }) return
        incrementPack(moduleDir)
    }

    /**
     * A qualified name (`:app:assembleDebug`) belongs to one module only, and
     * every module sees the same `taskNames`, so without the path check a
     * sibling module's build would also bump this counter. A bare name
     * (`assembleDebug`) is how Gradle asks every module that has the task, so it
     * counts in each of them — provided the module actually has such a task.
     */
    private fun requestsPackaging(project: Project, taskName: String): Boolean {
        val name = taskName.substringAfterLast(':')
        if (!packagesAnything(project, name)) return false
        if (!taskName.contains(':')) return true
        val path = taskName.removePrefix(":")
        val owner = if (path.contains(':')) ":" + path.substringBeforeLast(':') else ":"
        return owner == project.path
    }

    private fun packagesAnything(project: Project, name: String): Boolean {
        if (name in PACKAGING_EXACT) return true
        val android = project.plugins.hasPlugin("com.android.application") ||
            project.plugins.hasPlugin("com.android.library")
        return if (android) {
            ANDROID_PACKAGING_PREFIXES.any(name::startsWith)
        } else {
            name in JVM_PACKAGING_EXACT || JVM_PACKAGING_PREFIXES.any(name::startsWith)
        }
    }

    /**
     * Serialised through a file lock: two builds of the same checkout would read
     * the same value and the later write would swallow the earlier increment.
     */
    private fun incrementPack(moduleDir: File) {
        val file = packFile(moduleDir)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.channel.lock().use {
                val bytes = ByteArray(raf.length().toInt())
                raf.seek(0L)
                raf.readFully(bytes)
                val current = String(bytes, Charsets.UTF_8).trim().toIntOrNull() ?: 0
                raf.setLength(0L)
                raf.seek(0L)
                raf.write("${current + 1}\n".toByteArray())
            }
        }
    }

    private fun git(root: File, vararg args: String): String? = try {
        val process = ProcessBuilder(listOf("git", "-C", root.absolutePath) + args)
            .redirectErrorStream(false)
            .start()
        val out = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && out.isNotEmpty()) out else null
    } catch (_: Exception) {
        null
    }
}
