package com.company.upload.domain

enum class ErrorCategory {
    TRANSIENT,
    AUTH,
    VALIDATION,
    SERVER,
    RATE_LIMITED,
    STORAGE,
    CLIENT,
}

object ErrorClassifier {

    /**
     * Classifies an error into a category based on the HTTP status code
     * and/or the thrown exception.
     */
    fun classify(httpStatus: Int? = null, exception: Throwable? = null): ErrorCategory {
        // HTTP status takes precedence when available.
        if (httpStatus != null) {
            return classifyStatus(httpStatus)
        }

        // Fall back to exception-based heuristics.
        if (exception != null) {
            return classifyException(exception)
        }

        return ErrorCategory.CLIENT
    }

    /**
     * Returns `true` when the error category is eligible for automatic retry.
     */
    fun isRetryable(category: ErrorCategory): Boolean = when (category) {
        ErrorCategory.TRANSIENT,
        ErrorCategory.SERVER,
        ErrorCategory.RATE_LIMITED -> true

        ErrorCategory.AUTH,
        ErrorCategory.VALIDATION,
        ErrorCategory.STORAGE,
        ErrorCategory.CLIENT -> false
    }

    // ---- internal helpers -----------------------------------------------

    private fun classifyStatus(status: Int): ErrorCategory = when (status) {
        401, 403 -> ErrorCategory.AUTH
        400, 404, 405, 406, 411, 413, 415, 422 -> ErrorCategory.VALIDATION
        408, 502, 503, 504 -> ErrorCategory.TRANSIENT
        429 -> ErrorCategory.RATE_LIMITED
        507 -> ErrorCategory.STORAGE
        in 500..599 -> ErrorCategory.SERVER
        else -> ErrorCategory.CLIENT
    }

    private fun classifyException(ex: Throwable): ErrorCategory {
        val name = ex::class.simpleName.orEmpty()
        val message = ex.message.orEmpty()

        return when {
            // Common I/O / timeout exceptions are transient.
            "Timeout" in name || "timeout" in message -> ErrorCategory.TRANSIENT
            "IOException" in name || "ConnectException" in name -> ErrorCategory.TRANSIENT
            "SocketException" in name || "UnknownHost" in name -> ErrorCategory.TRANSIENT

            // Security / auth signals.
            "SecurityException" in name || "Auth" in name -> ErrorCategory.AUTH

            // Anything with "IllegalArgument" smells like validation.
            "IllegalArgument" in name -> ErrorCategory.VALIDATION

            else -> ErrorCategory.CLIENT
        }
    }
}
