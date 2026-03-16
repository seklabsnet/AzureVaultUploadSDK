package com.company.upload

/**
 * Reads bytes from a PlatformFile.
 * Platform-specific implementations use ContentResolver (Android) and NSFileManager (iOS).
 */
expect object FileReader {
    /**
     * Reads a chunk of bytes from the file.
     * @param file The platform file to read from
     * @param offset Byte offset to start reading from
     * @param size Number of bytes to read
     * @return ByteArray containing the requested bytes
     */
    fun readBytes(file: PlatformFile, offset: Long, size: Long): ByteArray

    /**
     * Reads the entire file into memory. Use only for small files (<4MB).
     */
    fun readAll(file: PlatformFile): ByteArray
}
