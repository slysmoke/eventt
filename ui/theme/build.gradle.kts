import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "org.eventt"
version = "1.0.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    api(libs.compose.materialIconsCore)
}
