package com.company.upload.sample

import android.app.Application
import com.company.upload.AzureVaultUpload
import com.company.upload.domain.UploadConfig
import com.company.upload.initialize

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AzureVaultUpload.initialize(
            context = this,
            config = UploadConfig(
                baseUrl = "https://api.company.com/v1",
                appId = "com.company.sampleapp",
                authProvider = { "sample-token" },
            )
        )
    }
}
