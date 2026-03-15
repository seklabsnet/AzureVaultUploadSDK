package com.company.upload

import com.company.upload.domain.BatchUploadState
import com.company.upload.domain.UploadInfo
import com.company.upload.domain.UploadMetadata
import com.company.upload.domain.UploadProgress
import com.company.upload.domain.UploadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AzureVaultUploader {
    fun upload(file: PlatformFile, metadata: UploadMetadata): Flow<UploadState>
    fun uploadBatch(files: List<PlatformFile>, metadata: UploadMetadata): Flow<BatchUploadState>
    fun pause(uploadId: String): Boolean
    fun resume(uploadId: String): Flow<UploadState>
    fun cancel(uploadId: String): Boolean
    fun getProgress(uploadId: String): StateFlow<UploadProgress>
    suspend fun getPendingUploads(): List<UploadInfo>
    fun retryFailed(uploadId: String): Flow<UploadState>
    suspend fun getDownloadUrl(fileId: String): String

    /** Optimized image CDN URL — deterministic, no network request */
    fun getImageUrl(
        fileId: String,
        width: Int? = null,
        height: Int? = null,
        fit: ImageFit = ImageFit.COVER,
        quality: Int = 80,
        format: ImageFormat = ImageFormat.AUTO,
    ): String

    /** BlurHash placeholder — local cache, zero network */
    fun getBlurHash(fileId: String): String?
}

enum class ImageFit { COVER, CONTAIN, SCALE_DOWN }
enum class ImageFormat { AUTO, WEBP, AVIF, JPEG, PNG }
