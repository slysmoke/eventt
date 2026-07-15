plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "org.eventt"
version = "1.0.0"

dependencies {
    implementation(project(":core:image"))
    implementation(project(":core:model"))
    implementation(project(":core:queue"))
    implementation(project(":ui:theme"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.core)
}
