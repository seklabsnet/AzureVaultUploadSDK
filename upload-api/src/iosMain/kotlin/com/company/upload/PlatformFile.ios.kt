package com.company.upload

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL

actual class PlatformFile(internal val url: NSURL) {
    actual val name: String
        get() = url.lastPathComponent ?: "unknown"

    actual val size: Long
        @OptIn(ExperimentalForeignApi::class)
        get() {
            val path = url.path ?: return 0L
            val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
            return (attrs?.get(NSFileSize) as? NSNumber)?.longValue ?: 0L
        }

    actual val mimeType: String?
        get() = null // Resolved internally via UTType
}
