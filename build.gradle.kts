plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}

/**
 * The stamp the APK is built with, printed for local inspection. Read at
 * execution time so it reports the number this very invocation wrote.
 */
tasks.register("printVersionName") {
    doLast {
        val stamp = com.geno1024.ai.mapoftime.gradle.Versioning.stamp(rootDir, file("app"))
        println(stamp.versionName)
    }
}
