import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.gradleup.shadow") version "8.3.5"
    kotlin("plugin.serialization") version "2.4.10"
}

val appVersion: String = rootProject.properties["app.version"] as? String ?: "0.0.0"
val githubRepo: String = rootProject.properties["github.repo"] as? String ?: ""

group = "org.eventt"
version = appVersion

// ── Generate AppVersion.kt from gradle.properties ─────────────────────────
val generateAppVersion =
    tasks.register("generateAppVersion") {
        val outputDir = layout.buildDirectory.dir("generated/appversion")
        outputs.dir(outputDir)
        inputs.property("appVersion", appVersion)
        inputs.property("githubRepo", githubRepo)
        doLast {
            val dir = outputDir.get().asFile
            dir.mkdirs()
            File(dir, "AppVersion.kt").writeText(
                """
                package org.eventt

                object AppVersion {
                    const val NAME        = "$appVersion"
                    const val GITHUB_REPO = "$githubRepo"
                }
                """.trimIndent(),
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

// ktlint/detekt scan the whole Kotlin source set, which includes the generated appversion/
// dir above — without this, Gradle flags an undeclared dependency on generateAppVersion's output.
tasks.matching { it.name.startsWith("runKtlint") || it.name == "detekt" }.configureEach {
    dependsOn(generateAppVersion)
}

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
    implementation(project(":core:nostr"))

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
    implementation(project(":features:contracts"))
    implementation(project(":features:settings"))
    implementation(project(":features:overlay"))
    implementation(project(":features:tools"))
    implementation(project(":features:p2pmarket"))
    implementation(project(":core:everef"))
    implementation(project(":core:marketlogs"))

    // Compose
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Update checker
    implementation(libs.okhttp.core)
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.swing)

    // Suppress SLF4J "Failed to load StaticLoggerBinder" warning at startup
    runtimeOnly(libs.slf4j.nop)

    // Global hotkey backends: JNA for direct native calls (X11/Win32/Carbon),
    // dbus-java for the Wayland xdg-desktop-portal GlobalShortcuts fallback.
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.junixsocket)
}

compose.desktop {
    application {
        mainClass = "org.eventt.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "eventt"
            packageVersion = appVersion
            description = "Desktop trading toolkit for EVE Online"
            vendor = "EVE Night Trade Tools"

            modules("java.sql", "java.naming", "jdk.httpserver")

            windows {
                // Installs to %LOCALAPPDATA% instead of Program Files so the app can
                // self-update (overwrite its own install dir) without admin rights.
                perUserInstall = true
                menu = true
                shortcut = true
                upgradeUuid = "8f2c1a6e-3b7d-4e9a-9c1f-2d6a7b4e5f10"
                iconFile.set(project.file("icons/icon.ico"))
            }
            macOS {
                iconFile.set(project.file("icons/icon.icns"))
            }
            linux {
                iconFile.set(project.file("icons/icon.png"))
                // Becomes the .desktop file's Categories — freedesktop main categories.
                menuGroup = "Game;Utility"
            }
        }
    }
}

// ── Fat jar for the "run anywhere with a JVM" release asset ────────────────
// Skiko/Compose native libs resolved via compose.desktop.currentOs are
// platform-specific, so this jar is only runnable on the OS it was built on —
// CI builds one per platform (see .github/workflows/release.yml).
tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("eventt")
    archiveVersion.set(appVersion)
    archiveClassifier.set("")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    manifest {
        attributes["Main-Class"] = "org.eventt.MainKt"
    }
}
