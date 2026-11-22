pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ridge-light"

include(":core")
include(":cli")
include(":osmand-aidl")
include(":dem-android")
include(":companion-app")
