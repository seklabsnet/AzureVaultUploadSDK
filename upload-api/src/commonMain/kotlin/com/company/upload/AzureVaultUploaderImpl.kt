package com.company.upload

import com.company.upload.core.UploadEngine
import com.company.upload.domain.BatchUploadState
import com.company.upload.domain.UploadConfig
import com.company.upload.domain.UploadInfo
import com.company.upload.domain.UploadMetadata
import com.company.upload.domain.UploadProgress
import com.company.upload.domain.UploadState
import com.company.upload.network.UploadApiClient
import com.company.upload.storage.UploadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

internal class AzureVaultUploaderImpl(
    private val config: UploadConfig,
    private val engine: UploadEngine,
    private val apiClient: UploadApiClient,
    private val uploadRepository: UploadRepository?,
) : AzureVaultUploader {

    override fun upload(file: PlatformFile, metadata: UploadMetadata): Flow<UploadState> {
        return engine.upload(
            fileName = file.name,
            fileSize = file.size,
            mimeType = file.mimeType,
            metadata = metadata,
            readFileData = { offset, size -> FileReader.readBytes(file, offset, size) },
        )
    }

    override fun uploadBatch(files: List<PlatformFile>, metadata: UploadMetadata): Flow<BatchUploadState> = flow {
        val total = files.size
        var completed = 0
        var failed = 0

        for (file in files) {
            var lastState: UploadState = UploadState.Idle
            engine.upload(
                fileName = file.name,
                fileSize = file.size,
                mimeType = file.mimeType,
                metadata = metadata,
                readFileData = { offset, size -> FileReader.readBytes(file, offset, size) },
            ).collect { state ->
                lastState = state
                val overallProgress = (completed.toFloat() + when (state) {
                    is UploadState.Uploading -> state.progress
                    is UploadState.Completed -> 1f
                    else -> 0f
                }) / total

                emit(BatchUploadState(
                    totalFiles = total,
                    completedFiles = completed,
                    failedFiles = failed,
                    currentFile = state,
                    overallProgress = overallProgress,
                ))
            }

            when (lastState) {
                is UploadState.Completed -> completed++
                is UploadState.Failed -> failed++
                else -> { /* no-op */ }
            }
        }
    }

    override fun pause(uploadId: String): Boolean {
        return engine.pause(uploadId)
    }

    override fun resume(uploadId: String): Flow<UploadState> {
        // Resume triggers re-upload of remaining chunks via the engine
        // For now, delegate to engine upload flow after un-pausing
        return flow {
            // Un-pause is handled internally by engine.pause toggling off
            // A full implementation would re-initiate the flow for remaining chunks
            emit(UploadState.Idle)
        }
    }

    override fun cancel(uploadId: String): Boolean {
        return engine.cancel(uploadId)
    }

    override fun getProgress(uploadId: String): StateFlow<UploadProgress> {
        return engine.getProgress(uploadId)
    }

    override suspend fun getPendingUploads(): List<UploadInfo> {
        val repo = uploadRepository ?: return emptyList()
        return repo.getPendingUploads().map { entity ->
            UploadInfo(
                uploadId = entity.uploadId,
                fileName = entity.fileName,
                fileSize = entity.fileSize,
                mimeType = entity.mimeType,
                progress = entity.progress.toFloat(),
                state = when (entity.status) {
                    "UPLOADING" -> UploadState.Uploading(
                        uploadId = entity.uploadId,
                        progress = entity.progress.toFloat(),
                        bytesUploaded = (entity.fileSize * entity.progress).toLong(),
                        totalBytes = entity.fileSize,
                        bytesPerSecond = 0L,
                        estimatedTimeRemaining = -1L,
                        currentChunk = 0,
                        totalChunks = 0,
                    )
                    "PAUSED" -> UploadState.Paused(
                        uploadId = entity.uploadId,
                        progress = entity.progress.toFloat(),
                        bytesUploaded = (entity.fileSize * entity.progress).toLong(),
                        totalBytes = entity.fileSize,
                    )
                    "COMPLETED" -> UploadState.Completed(
                        uploadId = entity.uploadId,
                        fileId = entity.fileId ?: "",
                        downloadUrl = entity.downloadUrl ?: "",
                        metadata = emptyMap(),
                        blurHash = entity.blurHash,
                    )
                    "CANCELLED" -> UploadState.Cancelled
                    else -> UploadState.Idle
                },
                createdAt = entity.createdAt,
                entityType = entity.entityType,
                entityId = entity.entityId,
            )
        }
    }

    override fun retryFailed(uploadId: String): Flow<UploadState> {
        // TODO: Retrieve stored upload info and re-initiate via engine
        return flow {
            emit(UploadState.Idle)
        }
    }

    override suspend fun getDownloadUrl(fileId: String): String {
        val response = apiClient.getDownloadUrl(fileId)
        return response.downloadUrl
    }

    override fun getImageUrl(
        fileId: String,
        width: Int?,
        height: Int?,
        fit: ImageFit,
        quality: Int,
        format: ImageFormat,
    ): String {
        val cdnBase = config.cdnBaseUrl.trimEnd('/')
        if (cdnBase.isEmpty()) return ""

        val params = buildList {
            width?.let { add("w=$it") }
            height?.let { add("h=$it") }
            if (fit != ImageFit.COVER) add("fit=${fit.name.lowercase()}")
            if (quality != 80) add("q=$quality")
            val f = if (format == ImageFormat.AUTO) "webp" else format.name.lowercase()
            add("f=$f")
        }.joinToString("&")

        return "$cdnBase/$fileId?$params"
    }

    override fun getBlurHash(fileId: String): String? {
        // TODO: Implement local blurHash cache lookup
        return null
    }
}
