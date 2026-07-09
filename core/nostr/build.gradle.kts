plugins {
    id("org.jetbrains.kotlin.jvm")
}

group = "org.eventt"
version = "1.0.0"

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(libs.quartz.jvm)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // Quartz's relay client needs an explicit (NormalizedRelayUrl) -> OkHttpClient provider handed
    // to it (BasicOkHttpWebSocket.Builder) — it does not manage a default HTTP client itself.
    implementation(libs.okhttp.core)
}
