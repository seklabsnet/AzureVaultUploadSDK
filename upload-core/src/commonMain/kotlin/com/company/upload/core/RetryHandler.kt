package com.company.upload.core

import com.company.upload.domain.RetryPolicy
import kotlinx.coroutines.delay

internal class RetryHandler(private val policy: RetryPolicy) {

    suspend fun <T> withRetry(
        maxAttempts: Int = 5,
        initialDelayMs: Long = 1000L,
        maxDelayMs: Long = 16_000L,
        retryIf: (Throwable) -> Boolean = { true },
        block: suspend (attempt: Int) -> T,
    ): T {
        var lastException: Throwable? = null

        repeat(maxAttempts) { attempt ->
            try {
                return block(attempt)
            } catch (e: Throwable) {
                lastException = e
                if (!retryIf(e) || attempt == maxAttempts - 1) throw e

                val delayMs = calculateDelay(attempt, initialDelayMs, maxDelayMs)
                delay(delayMs)
            }
        }

        throw lastException ?: IllegalStateException("Retry exhausted")
    }

    private fun calculateDelay(attempt: Int, initialDelayMs: Long, maxDelayMs: Long): Long {
        return when (policy) {
            RetryPolicy.NONE -> 0L
            RetryPolicy.LINEAR -> minOf(initialDelayMs * (attempt + 1), maxDelayMs)
            RetryPolicy.EXPONENTIAL -> minOf(initialDelayMs * (1L shl attempt), maxDelayMs)
        }
    }
}
