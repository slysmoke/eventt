plugins {
    id("org.jetbrains.kotlin.jvm")
}

group = "org.eve.trader"
version = "1.0.0"

dependencies {
    implementation(project(":core:http"))
    implementation(libs.okhttp.core)
    implementation(libs.kotlinx.coroutines.core)
}
