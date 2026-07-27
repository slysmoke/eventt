plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization") version "2.4.10"
}

group = "org.eventt"
version = "1.0.0"

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:esi"))
    implementation(project(":core:marketlogs"))
    implementation(project(":features:orders")) // CostBasisService, for the OBS overlay's session P&L
    implementation(project(":ui:theme"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.serialization.json)
}
