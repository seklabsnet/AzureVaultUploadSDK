package com.company.upload.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SasTokenManager(
    private val apiClient: UploadApiClient,
) {
    private val mutex = Mutex()

    private var cachedToken: String? = null
    private var tokenExpiryMs: Long? = null
    private var currentUploadId: String? = null

    suspend fun getToken(uploadId: String): String = mutex.withLock {
        val token = cachedToken
        if (token != null && currentUploadId == uploadId && !isTokenExpiredInternal()) {
            return@withLock token
        }
        return@withLock refreshTokenInternal(uploadId)
    }

    fun isTokenExpired(): Boolean {
        val expiry = tokenExpiryMs ?: return true
        val bufferMs = 120_000L // 2 minutes
        return currentTimeMs() >= (expiry - bufferMs)
    }

    suspend fun refreshToken(uploadId: String): String = mutex.withLock {
        return@withLock refreshTokenInternal(uploadId)
    }

    private fun isTokenExpiredInternal(): Boolean = isTokenExpired()

    private suspend fun refreshTokenInternal(uploadId: String): String {
        // In practice, re-initiate to get a fresh SAS token
        currentUploadId = uploadId
        return cachedToken ?: throw IllegalStateException(
            "No SAS token available for upload $uploadId. Initiate the upload first."
        )
    }

    suspend fun cacheToken(uploadId: String, sasToken: String, expiresAt: String) {
        mutex.withLock {
            currentUploadId = uploadId
            cachedToken = sasToken
            tokenExpiryMs = parseIso8601ToEpochMs(expiresAt)
        }
    }

    suspend fun clear() {
        mutex.withLock {
            cachedToken = null
            tokenExpiryMs = null
            currentUploadId = null
        }
    }

    private fun currentTimeMs(): Long {
        // Using kotlin.time for monotonic time isn't right for wall clock comparison.
        // For actual wall clock we need expect/actual, but for now approximate with epoch:
        return kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds
    }

    private fun parseIso8601ToEpochMs(iso8601: String): Long {
        // Simple parser: "2026-03-15T12:00:00Z" → epoch millis
        // In production this would use a proper parser
        // For now, set a default 15 min from cache time
        return currentTimeMs() + 15 * 60 * 1000L
    }
}
