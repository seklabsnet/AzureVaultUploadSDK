package com.company.upload.domain

sealed class UploadState {
    data object Idle : UploadState()
    data object Validating : UploadState()
    data object RequestingToken : UploadState()

    data class Uploading(
        val uploadId: String,
        val progress: Float,            // 0.0 — 1.0
        val bytesUploaded: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
        val estimatedTimeRemaining: Long,
        val currentChunk: Int,
        val totalChunks: Int,
    ) : UploadState()

    data class Paused(
        val uploadId: String,
        val progress: Float,
        val bytesUploaded: Long,
        val totalBytes: Long,
    ) : UploadState()

    data object Committing : UploadState()

    data class Processing(
        val uploadId: String,
        val step: String,
    ) : UploadState()

    data class Completed(
        val uploadId: String,
        val fileId: String,
        val downloadUrl: String,
        val metadata: Map<String, String>,
        val blurHash: String? = null,
    ) : UploadState()

    data class Failed(
        val uploadId: String?,
        val error: UploadError,
        val isRetryable: Boolean,
    ) : UploadState()

    data object Cancelled : UploadState()
}
