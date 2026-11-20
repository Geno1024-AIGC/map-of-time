package com.geno1024.ai.mapoftime.gradle

import org.gradle.api.Project
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * A build stamp of the form `0.1.<env>.<pack>.<sha8>`, exactly as
 * `RULES/VERSION.md` prescribes:
 *
 * - `env` is the commit count, and it is the only segment that sorts. The same
 *   checkout therefore yields the same `env`.
 * - `pack` is the module total returned by the org's build-count service: every
 *   packaging request POSTs, locally and on CI alike. Attempts count, not
 *   successes.
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
    private const val DEFAULT_URL = "http://geno1024.com:18080"

    /** `build` is an aggregate and exists everywhere; as a prefix it also hits `buildEnvironment`. */
    private val PACKAGING_EXACT = setOf("build")

    /** Android: every variant reaches the APK/AAB through one of these. */
    private val ANDROID_PACKAGING_PREFIXES = listOf("assemble", "bundle", "package", "install")

    /** JVM: exact names only, so a bare `assembleDebug` does not count here. */
    private val JVM_PACKAGING_EXACT = setOf("assemble", "jar", "installDist", "distTar", "distZip")

    /** JVM: names that only ever appear with a suffix. */
    private val JVM_PACKAGING_PREFIXES = listOf("shadowJar", "dist", "publish")

    /** Packaging builds: POST before the stamp is built, then use the returned total. */
    fun stamp(root: File, project: Project): Stamp = Stamp(
        env = env(root),
        pack = packOf(project),
        sha8 = sha8(root),
    )

    /** Read-only stamp for `printVersionName`: GET the current total, never increment. */
    fun current(root: File, project: Project): Stamp = Stamp(
        env = env(root),
        pack = fetch(project),
        sha8 = sha8(root),
    )

    /** Commit count first, per the rule that local and CI must agree. */
    fun env(root: File): String =
        git(root, "rev-list", "--count", "HEAD")
            ?: System.getenv("GITHUB_RUN_NUMBER")
            ?: "0"

    fun sha8(root: File): String =
        git(root, "rev-parse", "--short=8", "HEAD") ?: NO_HEAD

    /**
     * Decides whether this invocation is a packaging build and, if so, POSTs
     * before the stamp is constructed.
     *
     * The count is taken at configuration time, which is why it records
     * *requests* and not successes: a build that fails to compile, cannot sign
     * or is interrupted after configuration already has its number. Query-only
     * tasks (`tasks`, `help`, …) and `--dry-run` stay out and stamp `pack = 0`.
     * `-Ppack.bump=false` skips the service and uses `-Ppack.value` (default 0).
     */
    private fun packOf(project: Project): String {
        val params = project.gradle.startParameter
        val forced = params.projectProperties["pack.bump"]?.toBoolean()
        if (forced == false) return params.projectProperties["pack.value"] ?: "0"
        if (params.isDryRun) return "0"
        if (params.taskNames.none { requestsPackaging(project, it) }) return "0"
        return report(project)
    }

    /** POST one packaging attempt; the body is the new module total. */
    private fun report(project: Project): String {
        val url = "${base()}/projects/${segment(project.rootProject.name)}" +
            "/modules/${segment(moduleName(project))}/builds"
        val token = System.getenv("BUILD_COUNT_TOKEN")
            ?: error("BUILD_COUNT_TOKEN is required to count a packaging build")

        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            connection.outputStream.close()
        } catch (e: Exception) {
            throw IllegalStateException("build-count service unreachable: $url", e)
        }

        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_CREATED) {
            val body = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            error("build-count service returned HTTP $code: $body")
        }
        val pack = connection.inputStream.bufferedReader().use { it.readText() }.trim()
        require(pack.matches(Regex("\\d+"))) {
            "build-count service returned a non-numeric pack: $pack"
        }
        return pack
    }

    /** GET the current module total for display; never increments. */
    private fun fetch(project: Project): String {
        val url = "${base()}/projects/${segment(project.rootProject.name)}" +
            "/modules/${segment(moduleName(project))}"
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            val body = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            error("build-count service returned HTTP $code: $body")
        }
        val json = connection.inputStream.bufferedReader().use { it.readText() }
        return Regex("\"total\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)
            ?: error("build-count service returned no total: $json")
    }

    private fun base(): String =
        System.getenv("BUILD_COUNT_URL")?.takeIf { it.isNotBlank() } ?: DEFAULT_URL

    /** `project` is the root name; `module` is the Gradle path with `:` as `.`. */
    private fun moduleName(project: Project): String =
        project.path.removePrefix(":").replace(':', '.').ifEmpty { project.name }

    private fun segment(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
            "build-count segment must match [A-Za-z0-9._-]{1,64}: $value"
        }
        return value
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
