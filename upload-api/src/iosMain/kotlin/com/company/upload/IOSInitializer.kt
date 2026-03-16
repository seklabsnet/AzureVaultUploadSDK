package com.company.upload

import com.company.upload.domain.UploadConfig
import com.company.upload.storage.DriverFactory
import com.company.upload.storage.db.UploadDatabase

fun AzureVaultUpload.initialize(config: UploadConfig) {
    IOSPlatformDependencies.initialize()
    initializeInternal(config)

    // Create platform-specific database driver and complete wiring
    val driverFactory = DriverFactory()
    val driver = driverFactory.createDriver()
    val database = UploadDatabase(driver)
    setupWithDatabase(database)
}

internal object IOSPlatformDependencies {
    fun initialize() {
        // URLSession background config, NWPathMonitor setup
    }
}
