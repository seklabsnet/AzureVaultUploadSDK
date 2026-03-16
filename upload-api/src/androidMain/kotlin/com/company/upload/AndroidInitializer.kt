package com.company.upload

import android.content.Context
import com.company.upload.domain.UploadConfig
import com.company.upload.storage.DriverFactory
import com.company.upload.storage.db.UploadDatabase

fun AzureVaultUpload.initialize(context: Context, config: UploadConfig) {
    PlatformDependencies.initialize(context.applicationContext)
    initializeInternal(config)

    // Create platform-specific database driver and complete wiring
    val driverFactory = DriverFactory(context.applicationContext)
    val driver = driverFactory.createDriver()
    val database = UploadDatabase(driver)
    setupWithDatabase(database)
}

internal object PlatformDependencies {
    internal lateinit var appContext: Context
        private set

    fun initialize(context: Context) {
        appContext = context
    }
}
