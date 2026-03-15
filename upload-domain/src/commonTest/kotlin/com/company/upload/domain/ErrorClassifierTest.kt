package com.company.upload.domain

import kotlin.test.*

class ErrorClassifierTest {

    // --- HTTP status classification ---

    @Test
    fun http401_isAuth() {
        assertEquals(ErrorCategory.AUTH, ErrorClassifier.classify(httpStatus = 401))
    }

    @Test
    fun http403_isAuth() {
        assertEquals(ErrorCategory.AUTH, ErrorClassifier.classify(httpStatus = 403))
    }

    @Test
    fun http400_isValidation() {
        assertEquals(ErrorCategory.VALIDATION, ErrorClassifier.classify(httpStatus = 400))
    }

    @Test
    fun http404_isValidation() {
        assertEquals(ErrorCategory.VALIDATION, ErrorClassifier.classify(httpStatus = 404))
    }

    @Test
    fun http429_isRateLimited() {
        assertEquals(ErrorCategory.RATE_LIMITED, ErrorClassifier.classify(httpStatus = 429))
    }

    @Test
    fun http500_isServer() {
        assertEquals(ErrorCategory.SERVER, ErrorClassifier.classify(httpStatus = 500))
    }

    @Test
    fun http502_isTransient() {
        // 502 is mapped to TRANSIENT (not SERVER) per classifyStatus
        assertEquals(ErrorCategory.TRANSIENT, ErrorClassifier.classify(httpStatus = 502))
    }

    @Test
    fun http503_isTransient() {
        // 503 is mapped to TRANSIENT per classifyStatus
        assertEquals(ErrorCategory.TRANSIENT, ErrorClassifier.classify(httpStatus = 503))
    }

    @Test
    fun http501_isServer() {
        // 501 falls into the 500..599 catch-all -> SERVER
        assertEquals(ErrorCategory.SERVER, ErrorClassifier.classify(httpStatus = 501))
    }

    // --- Exception-based classification ---

    @Test
    fun nullStatusWithGenericException_isClient() {
        // A generic RuntimeException doesn't match any heuristic -> CLIENT
        val ex = RuntimeException("something went wrong")
        assertEquals(ErrorCategory.CLIENT, ErrorClassifier.classify(exception = ex))
    }

    @Test
    fun nullStatusWithTimeoutException_isTransient() {
        val ex = RuntimeException("Request timeout exceeded")
        assertEquals(ErrorCategory.TRANSIENT, ErrorClassifier.classify(exception = ex))
    }

    @Test
    fun noStatusNoException_isClient() {
        assertEquals(ErrorCategory.CLIENT, ErrorClassifier.classify())
    }

    // --- isRetryable ---

    @Test
    fun transientIsRetryable() {
        assertTrue(ErrorClassifier.isRetryable(ErrorCategory.TRANSIENT))
    }

    @Test
    fun authIsNotRetryable() {
        assertFalse(ErrorClassifier.isRetryable(ErrorCategory.AUTH))
    }

    @Test
    fun validationIsNotRetryable() {
        assertFalse(ErrorClassifier.isRetryable(ErrorCategory.VALIDATION))
    }

    @Test
    fun serverIsRetryable() {
        assertTrue(ErrorClassifier.isRetryable(ErrorCategory.SERVER))
    }

    @Test
    fun rateLimitedIsRetryable() {
        assertTrue(ErrorClassifier.isRetryable(ErrorCategory.RATE_LIMITED))
    }

    @Test
    fun storageIsNotRetryable() {
        assertFalse(ErrorClassifier.isRetryable(ErrorCategory.STORAGE))
    }

    @Test
    fun clientIsNotRetryable() {
        assertFalse(ErrorClassifier.isRetryable(ErrorCategory.CLIENT))
    }
}
