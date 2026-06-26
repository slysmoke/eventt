import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization") version "2.0.21"
}

val appVersion: String = rootProject.properties["app.version"] as? String ?: "0.0.0"
val githubRepo: String = rootProject.properties["github.repo"] as? String ?: ""

group = "org.eve.trader"
version = appVersion

// ── Generate AppVersion.kt from gradle.properties ─────────────────────────
val generateAppVersion = tasks.register("generateAppVersion") {
    val outputDir = layout.buildDirectory.dir("generated/appversion")
    outputs.dir(outputDir)
    inputs.property("appVersion", appVersion)
    inputs.property("githubRepo", githubRepo)
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        File(dir, "AppVersion.kt").writeText(
            """
            package org.eve.trader

            object AppVersion {
                const val NAME        = "$appVersion"
                const val GITHUB_REPO = "$githubRepo"
            }
            """.trimIndent()
        )
    }
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDir(layout.buildDirectory.dir("generated/appversion"))
        }
    }
}

tasks.named("compileKotlin") { dependsOn(generateAppVersion) }

// ─────────────────────────────────────────────────────────────────────────

dependencies {
    // Core modules
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:http"))
    implementation(project(":core:cache"))
    implementation(project(":core:auth"))
    implementation(project(":core:esi"))
    implementation(project(":core:queue"))
    implementation(project(":core:staticdata"))
    implementation(project(":core:image"))

    // UI modules
    implementation(project(":ui:theme"))
    implementation(project(":ui:common"))

    // Feature modules
    implementation(project(":features:characters"))
    implementation(project(":features:market"))
    implementation(project(":features:assets"))
    implementation(project(":features:wallet"))
    implementation(project(":features:orders"))
    implementation(project(":features:dashboard"))
    implementation(project(":features:alerts"))
    implementation(project(":features:industry"))
    implementation(project(":features:contracts"))
    implementation(project(":features:watchlist"))
    implementation(project(":features:settings"))
    implementation(project(":features:overlay"))
    implementation(project(":core:everef"))

    // Compose
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Update checker
    implementation(libs.okhttp.core)
    implementation(libs.kotlinx.serialization.json)

    // DI
    implementation(libs.koin.core)

    // Coroutines
    implementation(libs.kotlinx.coroutines.swing)

    // Suppress SLF4J "Failed to load StaticLoggerBinder" warning at startup
    runtimeOnly(libs.slf4j.nop)
}

compose.desktop {
    application {
        mainClass = "org.eve.trader.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "eve-trader"
            packageVersion = appVersion
        }
    }
}
