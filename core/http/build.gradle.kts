plugins {
    id("org.jetbrains.kotlin.jvm")
}

group = "org.eventt"
version = "1.0.0"

dependencies {
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.core)
}
