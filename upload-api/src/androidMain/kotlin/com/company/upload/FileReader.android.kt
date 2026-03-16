package com.company.upload

import java.io.InputStream

actual object FileReader {

    actual fun readBytes(file: PlatformFile, offset: Long, size: Long): ByteArray {
        val inputStream = file.contentResolver.openInputStream(file.uri)
            ?: throw IllegalStateException("Cannot open file: ${file.name}")

        return inputStream.use { stream ->
            // Skip to offset
            var skipped = 0L
            while (skipped < offset) {
                val n = stream.skip(offset - skipped)
                if (n <= 0) break
                skipped += n
            }

            // Read requested size
            val buffer = ByteArray(size.toInt())
            var totalRead = 0
            while (totalRead < size) {
                val read = stream.read(buffer, totalRead, (size - totalRead).toInt())
                if (read == -1) break
                totalRead += read
            }

            if (totalRead == size.toInt()) {
                buffer
            } else {
                buffer.copyOf(totalRead)
            }
        }
    }

    actual fun readAll(file: PlatformFile): ByteArray {
        val inputStream = file.contentResolver.openInputStream(file.uri)
            ?: throw IllegalStateException("Cannot open file: ${file.name}")

        return inputStream.use { it.readBytes() }
    }
}
