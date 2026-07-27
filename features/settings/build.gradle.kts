plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "org.eventt"
version = "1.0.0"

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:everef"))
    implementation(project(":core:staticdata"))
    implementation(project(":core:marketlogs"))
    implementation(project(":core:nostr"))
    implementation(project(":features:overlay")) // StreamOverlayServer, for the OBS overlay settings card
    implementation(project(":ui:theme"))
    implementation(project(":ui:common"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.core)
}
