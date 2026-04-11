plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
}

group = "org.eve.trader"
version = "1.0.0"

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:queue"))
    implementation(project(":ui:theme"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
}
