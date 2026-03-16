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
                baseUrl = "https://YOUR_FUNCTION_APP.azurewebsites.net/api",
                appId = "centauri",
                clientId = "centauri",
                clientSecret = "REDACTED_SECRET",
                cdnBaseUrl = "https://YOUR_CDN_ENDPOINT.azurefd.net",
            )
        )
    }
}
