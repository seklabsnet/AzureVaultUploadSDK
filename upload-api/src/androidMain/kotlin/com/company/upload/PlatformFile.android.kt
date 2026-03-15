package com.company.upload

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

actual class PlatformFile(
    internal val uri: Uri,
    internal val contentResolver: ContentResolver,
) {
    actual val name: String
        get() {
            val cursor = contentResolver.query(uri, null, null, null, null)
            return cursor?.use {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && it.moveToFirst()) it.getString(idx) else null
            } ?: uri.lastPathSegment ?: "unknown"
        }

    actual val size: Long
        get() {
            val cursor = contentResolver.query(uri, null, null, null, null)
            return cursor?.use {
                val idx = it.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && it.moveToFirst()) it.getLong(idx) else 0L
            } ?: 0L
        }

    actual val mimeType: String?
        get() = contentResolver.getType(uri)
}

fun Uri.toPlatformFile(contentResolver: ContentResolver) = PlatformFile(this, contentResolver)
