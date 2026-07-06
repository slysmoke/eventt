plugins {
    id("org.jetbrains.kotlin.jvm")
}

group = "org.eventt"
version = "1.0.0"

dependencies {
    implementation(project(":core:model"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.coroutines.core)
}
