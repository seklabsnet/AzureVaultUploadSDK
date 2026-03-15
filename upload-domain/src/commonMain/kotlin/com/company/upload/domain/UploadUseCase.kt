package com.company.upload.domain

import kotlinx.coroutines.flow.Flow

/**
 * Represents the final result of a completed upload.
 */
data class UploadResult(
    val uploadId: String,
    val remoteUrl: String,
    val eTag: String?,
)

/**
 * Core orchestration contract for file uploads.
 */
interface UploadUseCase {
    fun upload(
        uploadId: String,
        fileName: String,
        fileSize: Long,
        mimeType: String?,
    ): Flow<UploadProgress>

    suspend fun pause(uploadId: String)
    suspend fun cancel(uploadId: String)
    suspend fun getResult(uploadId: String): UploadResult?
}
