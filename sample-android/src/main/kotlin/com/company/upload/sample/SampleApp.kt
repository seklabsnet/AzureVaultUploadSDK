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
                baseUrl = "https://YOUR_FUNCTION_APP.azurewebsites.net/api/v1",
                appId = "centauri",
                authProvider = { /* TODO: Replace with real JWT from your auth provider */ "sample-token" },
                cdnBaseUrl = "https://YOUR_CDN_ENDPOINT.azurefd.net",
            )
        )
    }
}
