package com.company.upload.platform

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

internal class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uploadId = inputData.getString("uploadId") ?: return Result.failure()

        return try {
            setForeground(createForegroundInfo(uploadId))
            // Upload engine will be resolved via Koin/service locator
            // The actual upload logic is in upload-core
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun createForegroundInfo(uploadId: String): ForegroundInfo {
        val notification = AndroidNotificationHelper.createUploadNotification(
            applicationContext,
            uploadId,
            "Uploading...",
            0
        )
        return ForegroundInfo(uploadId.hashCode(), notification)
    }
}
