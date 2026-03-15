package com.company.upload.domain

data class UploadConfig(
    val baseUrl: String,
    val appId: String,
    val authProvider: suspend () -> String = { "" },
    val maxConcurrentUploads: Int = 3,
    val chunkStrategy: ChunkStrategyType = ChunkStrategyType.AUTO,
    val retryPolicy: RetryPolicy = RetryPolicy.EXPONENTIAL,
    val enableCompression: Boolean = true,
    val maxFileSize: Long = 5L * 1024 * 1024 * 1024, // 5 GB
    val chunkTimeoutMs: Long = 30_000L,               // 30 seconds per chunk
    val cdnBaseUrl: String = "",                       // CDN URL for image transforms
)

enum class ChunkStrategyType { AUTO, SINGLE_SHOT, CHUNKED }
enum class RetryPolicy { NONE, LINEAR, EXPONENTIAL }
