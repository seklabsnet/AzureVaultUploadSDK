package com.company.upload

import com.company.upload.domain.UploadConfig

fun AzureVaultUpload.initialize(config: UploadConfig) {
    IOSPlatformDependencies.initialize()
    initializeInternal(config)
}

internal object IOSPlatformDependencies {
    fun initialize() {
        // URLSession background config, NWPathMonitor setup
    }
}
