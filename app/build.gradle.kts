import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    kotlin("plugin.serialization") version "1.9.22"
}

group = "org.eve.trader"
version = "1.0.0"

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

    // Compose
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // DI
    implementation(libs.koin.core)

    // Coroutines
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "org.eve.trader.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "eve-trader"
            packageVersion = "1.0.0"
        }
    }
}
