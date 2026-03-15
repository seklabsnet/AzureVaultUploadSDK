package com.company.upload

import com.company.upload.domain.UploadConfig

object AzureVaultUpload {
    private var _config: UploadConfig? = null
    private var _uploader: AzureVaultUploader? = null

    internal val config: UploadConfig
        get() = _config ?: error("AzureVaultUpload not initialized. Call initialize() first.")

    internal fun initializeInternal(config: UploadConfig) {
        _config = config
        // Internal module wiring will be done via DI (Koin)
        // For now, implementation will be set up in platform initializers
    }

    internal fun setUploader(uploader: AzureVaultUploader) {
        _uploader = uploader
    }

    fun uploader(): AzureVaultUploader =
        _uploader ?: error("AzureVaultUpload not initialized. Call initialize() first.")
}
