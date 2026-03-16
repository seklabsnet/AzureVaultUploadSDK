import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.skie)
    `maven-publish`
}

group = "com.company.upload"
version = "1.0.0"

kotlin {
    androidTarget {
        compilations.all { kotlinOptions { jvmTarget = "17" } }
        publishLibraryVariants("release")
    }

    val xcf = XCFramework("AzureVaultUploadSDK")

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "AzureVaultUploadSDK"
            isStatic = true
            xcf.add(this)
        }
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            api(project(":upload-core"))
            api(project(":upload-domain"))
            api(project(":upload-network"))
            api(project(":upload-storage"))
            implementation(project(":upload-crypto"))
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(project(":upload-platform-android"))
        }
        iosMain.dependencies {
            implementation(project(":upload-platform-ios"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// SKIE enables Flow, sealed class, and suspend interop by default

android {
    namespace = "com.company.upload"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Maven publish configuration
publishing {
    publications.withType<MavenPublication> {
        pom {
            name.set("AzureVault Upload SDK")
            description.set("KMP SDK for uploading files to Azure Blob Storage")
            url.set("https://github.com/company/AzureVaultUploadSDK")
            licenses {
                license {
                    name.set("MIT")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
        }
    }
    repositories {
        maven {
            name = "local"
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
}
