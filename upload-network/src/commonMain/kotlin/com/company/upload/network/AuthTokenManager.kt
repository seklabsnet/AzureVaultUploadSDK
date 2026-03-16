package com.company.upload.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

class AuthTokenManager(
    private val authClient: AuthTokenClient,
    private val clientId: String,
    private val clientSecret: String,
) {
    private val mutex = Mutex()
    private val timeMark = TimeSource.Monotonic.markNow()

    private var cachedToken: String? = null
    private var tokenExpiresAtMs: Long = 0L

    private fun nowMs(): Long = timeMark.elapsedNow().inWholeMilliseconds

    /**
     * Returns a valid JWT access token, refreshing if the cached one is expired
     * or will expire within 60 seconds.
     */
    suspend fun getValidToken(): String = mutex.withLock {
        val token = cachedToken
        if (token != null && nowMs() < tokenExpiresAtMs) {
            return@withLock token
        }
        return@withLock refreshTokenInternal()
    }

    private suspend fun refreshTokenInternal(): String {
        val response = authClient.getToken(clientId, clientSecret)
        cachedToken = response.accessToken
        // expiresIn is in seconds; subtract 60s buffer to refresh before actual expiry
        val bufferSeconds = 60
        val effectiveLifetimeMs = (response.expiresIn - bufferSeconds).coerceAtLeast(0) * 1000L
        tokenExpiresAtMs = nowMs() + effectiveLifetimeMs
        return response.accessToken
    }

    /**
     * Force-clears the cached token so the next call to [getValidToken] fetches a fresh one.
     */
    suspend fun invalidate() {
        mutex.withLock {
            cachedToken = null
            tokenExpiresAtMs = 0L
        }
    }
}
