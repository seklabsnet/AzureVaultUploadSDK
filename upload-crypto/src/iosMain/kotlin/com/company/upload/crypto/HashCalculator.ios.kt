package com.company.upload.crypto

import kotlinx.cinterop.*
import platform.CoreCrypto.*

@OptIn(ExperimentalForeignApi::class)
actual class HashCalculator actual constructor() {

    private var md5Ctx: CC_MD5_CTX = nativeHeap.alloc()
    private var sha256Ctx: CC_SHA256_CTX = nativeHeap.alloc()

    init {
        CC_MD5_Init(md5Ctx.ptr)
        CC_SHA256_Init(sha256Ctx.ptr)
    }

    actual fun update(bytes: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        bytes.usePinned { pinned ->
            val ptr = pinned.addressOf(offset)
            CC_MD5_Update(md5Ctx.ptr, ptr, length.toUInt())
            CC_SHA256_Update(sha256Ctx.ptr, ptr, length.toUInt())
        }
    }

    actual fun digestMd5(): ByteArray {
        memScoped {
            // Create a fresh context, re-init, re-process is expensive.
            // Instead we just finalize and re-init — simpler for streaming use.
            val digest = allocArray<UByteVar>(CC_MD5_DIGEST_LENGTH)
            // We finalize the current context, which consumes it
            val tmpCtx = alloc<CC_MD5_CTX>()
            // Copy bytes manually using the struct's raw memory
            val srcPtr = md5Ctx.ptr.reinterpret<ByteVar>()
            val dstPtr = tmpCtx.ptr.reinterpret<ByteVar>()
            for (i in 0 until sizeOf<CC_MD5_CTX>().toInt()) {
                dstPtr[i] = srcPtr[i]
            }
            CC_MD5_Final(digest, tmpCtx.ptr)
            return ByteArray(CC_MD5_DIGEST_LENGTH) { digest[it].toByte() }
        }
    }

    actual fun digestSha256(): ByteArray {
        memScoped {
            val digest = allocArray<UByteVar>(CC_SHA256_DIGEST_LENGTH)
            val tmpCtx = alloc<CC_SHA256_CTX>()
            val srcPtr = sha256Ctx.ptr.reinterpret<ByteVar>()
            val dstPtr = tmpCtx.ptr.reinterpret<ByteVar>()
            for (i in 0 until sizeOf<CC_SHA256_CTX>().toInt()) {
                dstPtr[i] = srcPtr[i]
            }
            CC_SHA256_Final(digest, tmpCtx.ptr)
            return ByteArray(CC_SHA256_DIGEST_LENGTH) { digest[it].toByte() }
        }
    }

    actual fun md5Hex(): String = digestMd5().toHex()

    actual fun sha256Hex(): String = digestSha256().toHex()

    actual fun reset() {
        CC_MD5_Init(md5Ctx.ptr)
        CC_SHA256_Init(sha256Ctx.ptr)
    }
}
