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

rootProject.name = "AzureVaultUploadSDK"

include(":upload-api")
include(":upload-core")
include(":upload-domain")
include(":upload-network")
include(":upload-storage")
include(":upload-crypto")
include(":upload-platform-android")
include(":upload-platform-ios")
include(":sample-android")
