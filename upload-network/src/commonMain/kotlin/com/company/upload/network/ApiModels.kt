package com.company.upload.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Generic API wrapper — backend returns { success: true/false, data: {...}, error: {...} }
 */
@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ApiErrorBody? = null,
)

@Serializable
data class ApiErrorBody(
    val code: String,
    val message: String,
    val details: JsonObject? = null,
)

@Serializable
data class InitiateUploadRequest(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String?,
    val md5Hash: String? = null,
    val entityType: String,
    val entityId: String,
    val metadata: Map<String, String> = emptyMap(),
    val isPublic: Boolean = false,
)

@Serializable
data class InitiateUploadResponse(
    val uploadId: String,
    val blobUrl: String,
    val sasToken: String,
    val strategy: String, // SINGLE_SHOT or CHUNKED
    val maxBlockSize: Long,
    val expiresAt: String,
    val chunkCount: Int? = null,
)

@Serializable
data class CompleteUploadRequest(
    val uploadId: String,
    val blockIds: List<String>,
    val md5Hash: String? = null,
)

@Serializable
data class CompleteUploadResponse(
    val fileId: String,
    val downloadUrl: String,
    val metadata: JsonObject? = null,
    val processingStatus: String,
    val blurHash: String? = null,
)

@Serializable
data class UploadStatusResponse(
    val uploadId: String,
    val status: String,
    val progress: Float = 0f,
    val fileId: String? = null,
    val downloadUrl: String? = null,
    val blurHash: String? = null,
)

@Serializable
data class DownloadUrlResponse(
    val downloadUrl: String,
    val expiresAt: String? = null,
    val contentType: String? = null,
    val fileSize: Long = 0,
    val blurHash: String? = null,
)

@Serializable
data class BatchInitiateRequest(
    val files: List<InitiateUploadRequest>,
)

@Serializable
data class BatchInitiateResponse(
    val uploads: List<InitiateUploadResponse>,
)
