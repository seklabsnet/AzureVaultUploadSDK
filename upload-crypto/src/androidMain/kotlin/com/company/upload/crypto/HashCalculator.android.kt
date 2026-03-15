package com.company.upload.crypto

import java.security.MessageDigest

actual class HashCalculator actual constructor() {

    private var md5: MessageDigest = MessageDigest.getInstance("MD5")
    private var sha256: MessageDigest = MessageDigest.getInstance("SHA-256")

    actual fun update(bytes: ByteArray, offset: Int, length: Int) {
        md5.update(bytes, offset, length)
        sha256.update(bytes, offset, length)
    }

    actual fun digestMd5(): ByteArray {
        val clone = md5.clone() as MessageDigest
        return clone.digest()
    }

    actual fun digestSha256(): ByteArray {
        val clone = sha256.clone() as MessageDigest
        return clone.digest()
    }

    actual fun md5Hex(): String = digestMd5().toHex()

    actual fun sha256Hex(): String = digestSha256().toHex()

    actual fun reset() {
        md5.reset()
        sha256.reset()
    }
}
