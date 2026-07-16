// Root build.gradle.kts — common configuration for all subprojects
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.10" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    tasks.withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    // ktlintCheck and detekt are both wired into `check` by their plugins, so
    // `./gradlew build` (assemble + check) already gates on tests + both linters passing —
    // CI just also runs them as separate steps for clearer pass/fail output per stage.
    extensions.configure<KtlintExtension> {
        version.set("1.8.0")
        // Several modules add generated sources (AppVersion.kt, Compose resource accessors,
        // etc.) under build/ to their Kotlin source sets — those aren't ours to style-check.
        filter {
            exclude { entry -> entry.file.path.contains("${layout.buildDirectory.get()}") }
        }
    }

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(files("$rootDir/detekt.yml"))
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    // The `testImplementation` configuration only exists once the Kotlin plugin (applied by
    // each subproject's own build.gradle.kts) has run — which happens after this block, since
    // the root project's script evaluates first. withPlugin() defers until that's actually true.
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }
        dependencies {
            "testImplementation"(rootProject.libs.junit.jupiter)
            "testImplementation"(rootProject.libs.mockk)
            "testImplementation"(rootProject.libs.kotest.assertions)
            "testImplementation"(rootProject.libs.kotlinx.coroutines.test)
            "testImplementation"(rootProject.libs.turbine)
        }
    }
}
