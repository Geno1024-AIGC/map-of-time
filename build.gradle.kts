plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}

/**
 * The version the last packaging build wrote, printed for inspection. This is a
 * read-only GET against the build-count service, so running it does not consume
 * a pack number.
 */
tasks.register("printVersionName") {
    val root = rootDir
    val app = project(":app")
    doLast {
        println(com.geno1024.ai.mapoftime.gradle.Versioning.current(root, app).versionName)
    }
}
