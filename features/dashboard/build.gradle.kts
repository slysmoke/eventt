plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "org.eventt"
version = "1.0.0"

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:auth"))
    implementation(project(":core:esi"))
    implementation(project(":core:nostr")) // LeaderboardService, for LeaderboardPublisher
    implementation(project(":features:orders"))
    implementation(project(":features:market")) // ItemDetailDialog, opened from any item row
    implementation(project(":ui:theme"))
    implementation(project(":ui:common"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
    testImplementation(libs.compose.ui.test) // headless render test of the dashboard body
}
