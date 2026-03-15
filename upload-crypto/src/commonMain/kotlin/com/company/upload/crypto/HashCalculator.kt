package com.company.upload.crypto

expect class HashCalculator() {
    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)
    fun digestMd5(): ByteArray
    fun digestSha256(): ByteArray
    fun md5Hex(): String
    fun sha256Hex(): String
    fun reset()
}

@OptIn(ExperimentalStdlibApi::class)
fun ByteArray.toHex(): String = toHexString()
