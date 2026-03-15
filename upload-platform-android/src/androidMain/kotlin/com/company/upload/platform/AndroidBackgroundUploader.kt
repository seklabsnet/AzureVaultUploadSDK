package com.company.upload.platform

import android.content.Context
import androidx.work.*

internal class AndroidBackgroundUploader(private val context: Context) {

    fun enqueueUpload(uploadId: String) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf("uploadId" to uploadId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag("upload_$uploadId")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork("upload_$uploadId", ExistingWorkPolicy.KEEP, request)
    }

    fun cancelUpload(uploadId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("upload_$uploadId")
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag("azurevault_upload")
    }
}
