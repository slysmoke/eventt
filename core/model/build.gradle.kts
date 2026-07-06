plugins {
    id("org.jetbrains.kotlin.jvm")
    kotlin("plugin.serialization") version "2.0.21"
}

group = "org.eventt"
version = "1.0.0"

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
