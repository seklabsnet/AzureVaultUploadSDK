package com.company.upload.domain

sealed class UploadError(
    open val code: String,
    open val userMessage: String,
    open val technicalMessage: String? = null,
) {
    data class Validation(
        override val code: String,
        override val userMessage: String,
        val field: String,
    ) : UploadError(code, userMessage)

    data class Network(
        override val code: String,
        override val userMessage: String,
        val httpStatus: Int? = null,
    ) : UploadError(code, userMessage)

    data class Authentication(
        override val code: String = "AUTH_FAILED",
        override val userMessage: String = "Oturum süresi doldu, lütfen tekrar giriş yapın.",
    ) : UploadError(code, userMessage)

    data class Storage(
        override val code: String,
        override val userMessage: String,
    ) : UploadError(code, userMessage)

    data class Unknown(
        override val code: String = "UNKNOWN",
        override val userMessage: String = "Beklenmeyen bir hata oluştu.",
        override val technicalMessage: String? = null,
    ) : UploadError(code, userMessage, technicalMessage)
}
