package com.company.upload.domain

data class UploadProgress(
    val uploadId: String,
    val progress: Float,            // 0.0 — 1.0
    val bytesUploaded: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val estimatedTimeRemaining: Long, // milliseconds
)
