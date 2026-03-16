package com.company.upload

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfURL
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
actual object FileReader {

    actual fun readBytes(file: PlatformFile, offset: Long, size: Long): ByteArray {
        // Read entire file, then extract the range
        // For large files, a streaming approach with NSFileHandle would be better
        val allBytes = readAll(file)
        val end = minOf(offset + size, allBytes.size.toLong()).toInt()
        val start = offset.toInt()
        if (start >= allBytes.size) return ByteArray(0)
        return allBytes.copyOfRange(start, end)
    }

    actual fun readAll(file: PlatformFile): ByteArray {
        val data = NSData.dataWithContentsOfURL(file.url)
            ?: throw IllegalStateException("Cannot read file: ${file.name}")

        val size = data.length.toInt()
        if (size == 0) return ByteArray(0)

        return data.bytes!!.readBytes(size)
    }
}
