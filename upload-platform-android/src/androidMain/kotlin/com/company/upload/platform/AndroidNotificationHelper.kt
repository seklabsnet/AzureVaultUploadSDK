package com.company.upload.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

internal object AndroidNotificationHelper {
    private const val CHANNEL_ID = "azurevault_upload"
    private const val CHANNEL_NAME = "File Uploads"

    fun createUploadNotification(
        context: Context,
        uploadId: String,
        title: String,
        progress: Int,
    ): Notification {
        ensureChannel(context)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    fun updateProgress(context: Context, uploadId: String, progress: Int, title: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = createUploadNotification(context, uploadId, title, progress)
        manager.notify(uploadId.hashCode(), notification)
    }

    fun cancelNotification(context: Context, uploadId: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(uploadId.hashCode())
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows upload progress"
                setShowBadge(false)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
