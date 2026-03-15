package com.company.upload

import android.content.Context
import com.company.upload.domain.UploadConfig

fun AzureVaultUpload.initialize(context: Context, config: UploadConfig) {
    PlatformDependencies.initialize(context.applicationContext)
    initializeInternal(config)
}

internal object PlatformDependencies {
    internal lateinit var appContext: Context
        private set

    fun initialize(context: Context) {
        appContext = context
    }
}
