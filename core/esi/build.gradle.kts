plugins {
    id("org.jetbrains.kotlin.jvm")
    kotlin("plugin.serialization") version "2.0.21"
}

group = "org.eve.trader"
version = "1.0.0"

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:http"))
    implementation(project(":core:cache"))
    implementation(project(":core:database"))
    implementation(project(":core:queue"))
    implementation(project(":core:auth"))
    implementation(libs.okhttp.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
