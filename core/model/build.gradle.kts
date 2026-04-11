plugins {
    id("org.jetbrains.kotlin.jvm")
    kotlin("plugin.serialization") version "1.9.22"
}

group = "org.eve.trader"
version = "1.0.0"

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
