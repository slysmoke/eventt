plugins {
    id("org.jetbrains.kotlin.jvm")
    kotlin("plugin.serialization") version "1.9.22"
}

group = "org.eve.trader"
version = "1.0.0"

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:esi"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
