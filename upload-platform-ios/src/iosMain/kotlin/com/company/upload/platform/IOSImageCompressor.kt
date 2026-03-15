package com.company.upload.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.*
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

internal class IOSImageCompressor {

    @OptIn(ExperimentalForeignApi::class)
    fun compress(url: NSURL, quality: Double = 0.85): ByteArray? {
        val image = UIImage(contentsOfFile = url.path ?: return null) ?: return null
        val data = UIImageJPEGRepresentation(image, quality) ?: return null
        return data.toByteArray()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun NSData.toByteArray(): ByteArray {
        val size = this.length.toInt()
        val bytes = ByteArray(size)
        if (size > 0) {
            bytes.usePinned { pinned ->
                this.getBytes(pinned.addressOf(0), this.length)
            }
        }
        return bytes
    }
}
