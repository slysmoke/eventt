pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "eventt"

// Core modules
include(":core:model")
include(":core:database")
include(":core:http")
include(":core:cache")
include(":core:auth")
include(":core:esi")
include(":core:queue")
include(":core:staticdata")
include(":core:image")
include(":core:everef")
include(":core:marketlogs")

// UI modules
include(":ui:theme")
include(":ui:common")

// Feature modules
include(":features:characters")
include(":features:market")
include(":features:assets")
include(":features:wallet")
include(":features:orders")
include(":features:dashboard")
include(":features:alerts")
include(":features:contracts")
include(":features:watchlist")
include(":features:settings")
include(":features:overlay")
include(":features:tools")

// Main app
include(":app")
