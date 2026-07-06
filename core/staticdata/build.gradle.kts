plugins {
    id("org.jetbrains.kotlin.jvm")
    kotlin("plugin.serialization") version "2.0.21"
}

group = "org.eventt"
version = "1.0.0"

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:esi"))
    implementation(project(":core:http"))
    implementation(libs.okhttp.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
