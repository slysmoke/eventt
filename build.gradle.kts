// Root build.gradle.kts — common configuration for all subprojects
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
    id("org.jetbrains.compose") version "1.6.0" apply false
}

subprojects {
    tasks.withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = "21"
            freeCompilerArgs = listOf("-Xjsr305=strict")
        }
    }
}
