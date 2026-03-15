plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    iosX64(); iosArm64(); iosSimulatorArm64()

    sourceSets {
        iosMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
