package com.company.upload.core

import com.company.upload.domain.ErrorClassifier
import com.company.upload.domain.FileValidator
import com.company.upload.domain.UploadConfig
import com.company.upload.domain.UploadError
import com.company.upload.domain.UploadMetadata
import com.company.upload.domain.UploadProgress
import com.company.upload.domain.UploadState
import com.company.upload.domain.UploadStrategyType
import com.company.upload.network.BlobUploader
import com.company.upload.network.InitiateUploadRequest
import com.company.upload.network.CompleteUploadRequest
import com.company.upload.network.SasTokenManager
import com.company.upload.network.UploadApiClient
import com.company.upload.storage.UploadRepository
import com.company.upload.storage.ChunkStateRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class UploadEngine(
    private val config: UploadConfig,
    private val apiClient: UploadApiClient,
    private val blobUploader: BlobUploader,
    private val sasManager: SasTokenManager,
    private val uploadRepository: UploadRepository,
    private val chunkStateRepository: ChunkStateRepository,
    private val validator: FileValidator,
    private val errorClassifier: ErrorClassifier,
) {
    private val chunkManager = ChunkManager(config)
    private val retryHandler = RetryHandler(config.retryPolicy)
    private val bandwidthEstimator = BandwidthEstimator()
    private val uploadQueue = UploadQueue(config.maxConcurrentUploads)

    private val pausedUploads = mutableSetOf<String>()
    private val cancelledUploads = mutableSetOf<String>()
    private val pauseMutex = Mutex()

    private val progressFlows = mutableMapOf<String, MutableStateFlow<UploadProgress>>()

    private val timeMark = kotlin.time.TimeSource.Monotonic.markNow()
    private fun currentTimeMs(): Long = timeMark.elapsedNow().inWholeMilliseconds

    /**
     * @param readFileData Lambda that reads bytes from the file. (offset, size) -> ByteArray
     *   Provided by upload-api layer which has access to PlatformFile + FileReader.
     */
    fun upload(
        fileName: String,
        fileSize: Long,
        mimeType: String?,
        metadata: UploadMetadata,
        readFileData: (offset: Long, size: Long) -> ByteArray,
    ): Flow<UploadState> = flow {
        emit(UploadState.Validating)

        // 1. Validate
        val validation = validator.validate(fileName, fileSize, mimeType)
        if (!validation.isValid) {
            emit(UploadState.Failed(
                uploadId = null,
                error = UploadError.Validation(
                    code = "VALIDATION_FAILED",
                    userMessage = validation.errors.joinToString(", "),
                    field = "file",
                ),
                isRetryable = false,
            ))
            return@flow
        }

        // 2. Request SAS token
        emit(UploadState.RequestingToken)
        val initResponse = try {
            apiClient.initiateUpload(InitiateUploadRequest(
                fileName = fileName,
                fileSize = fileSize,
                mimeType = mimeType,
                entityType = metadata.entityType,
                entityId = metadata.entityId,
                metadata = metadata.customMetadata,
                isPublic = metadata.isPublic,
            ))
        } catch (e: Exception) {
            val category = errorClassifier.classify(null, e)
            emit(UploadState.Failed(
                uploadId = null,
                error = UploadError.Network(code = "INIT_FAILED", userMessage = "Baglanti kurulamadi."),
                isRetryable = errorClassifier.isRetryable(category),
            ))
            return@flow
        }

        val uploadId = initResponse.uploadId
        val now = currentTimeMs()

        // 3. Persist upload state
        uploadRepository.saveUpload(
            uploadId = uploadId,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            entityType = metadata.entityType,
            entityId = metadata.entityId,
            status = "UPLOADING",
            progress = 0.0,
            blobUrl = initResponse.blobUrl,
            sasToken = initResponse.sasToken,
            sasExpiresAt = initResponse.expiresAt,
            createdAt = now,
            updatedAt = now,
        )

        // 4. Initialize progress flow
        progressFlows[uploadId] = MutableStateFlow(UploadProgress(
            uploadId = uploadId,
            progress = 0f,
            bytesUploaded = 0L,
            totalBytes = fileSize,
            bytesPerSecond = 0L,
            estimatedTimeRemaining = -1L,
        ))

        // 5. Determine strategy and upload
        val strategy = chunkManager.determineStrategy(fileSize)

        try {
            if (strategy.strategy == UploadStrategyType.SINGLE_SHOT) {
                emit(UploadState.Uploading(
                    uploadId = uploadId, progress = 0f, bytesUploaded = 0L, totalBytes = fileSize,
                    bytesPerSecond = 0L, estimatedTimeRemaining = -1L, currentChunk = 1, totalChunks = 1,
                ))
                // Single-shot: read entire file and upload
                val fileData = readFileData(0, fileSize)
                val startTime = currentTimeMs()
                blobUploader.uploadSingleShot(
                    blobUrl = initResponse.blobUrl,
                    sasToken = initResponse.sasToken,
                    data = fileData,
                    contentType = mimeType,
                )
                bandwidthEstimator.recordSample(fileSize, currentTimeMs() - startTime)
            } else {
                val chunks = chunkManager.createChunks(fileSize, strategy)

                chunkStateRepository.saveChunks(
                    uploadId = uploadId,
                    chunks = chunks.map { chunk ->
                        com.company.upload.storage.ChunkInfo(
                            uploadId = uploadId,
                            chunkIndex = chunk.index.toLong(),
                            blockId = chunk.blockId,
                            size = chunk.size,
                            offset = chunk.offset,
                            uploaded = false,
                        )
                    }
                )

                val totalChunks = chunks.size
                var uploadedChunks = 0
                var totalBytesUploaded = 0L

                coroutineScope {
                    val semaphore = kotlinx.coroutines.sync.Semaphore(strategy.concurrency)

                    chunks.map { chunk ->
                        async {
                            semaphore.acquire()
                            try {
                                pauseMutex.withLock {
                                    if (uploadId in cancelledUploads) throw CancellationException("Upload cancelled")
                                }
                                while (pauseMutex.withLock { uploadId in pausedUploads }) { delay(500) }

                                retryHandler.withRetry(
                                    maxAttempts = 5,
                                    retryIf = { e -> errorClassifier.isRetryable(errorClassifier.classify(null, e)) }
                                ) {
                                    val chunkData = readFileData(chunk.offset, chunk.size)
                                    val startTime = currentTimeMs()
                                    blobUploader.uploadBlock(
                                        blobUrl = initResponse.blobUrl,
                                        sasToken = initResponse.sasToken,
                                        blockId = chunk.blockId,
                                        data = chunkData,
                                    )
                                    bandwidthEstimator.recordSample(chunk.size, currentTimeMs() - startTime)
                                }

                                chunkStateRepository.markChunkUploaded(uploadId, chunk.index.toLong())
                                uploadedChunks++
                                totalBytesUploaded += chunk.size

                                val progress = totalBytesUploaded.toFloat() / fileSize
                                val bps = bandwidthEstimator.estimateBytesPerSecond()
                                val eta = bandwidthEstimator.estimateTimeRemainingMs(fileSize - totalBytesUploaded)

                                emit(UploadState.Uploading(
                                    uploadId = uploadId, progress = progress, bytesUploaded = totalBytesUploaded,
                                    totalBytes = fileSize, bytesPerSecond = bps, estimatedTimeRemaining = eta,
                                    currentChunk = uploadedChunks, totalChunks = totalChunks,
                                ))
                            } finally {
                                semaphore.release()
                            }
                        }
                    }.awaitAll()
                }
            }

            // 6. Commit
            emit(UploadState.Committing)
            val allChunks = chunkStateRepository.getAllChunks(uploadId)
            val completeResponse = apiClient.completeUpload(CompleteUploadRequest(
                uploadId = uploadId,
                blockIds = allChunks.map { it.blockId },
            ))

            // 7. Update repository
            uploadRepository.markCompleted(
                uploadId = uploadId,
                fileId = completeResponse.fileId,
                downloadUrl = completeResponse.downloadUrl,
                blurHash = completeResponse.blurHash,
                updatedAt = currentTimeMs(),
            )

            // 8. Cleanup
            chunkStateRepository.deleteChunks(uploadId)
            progressFlows.remove(uploadId)

            emit(UploadState.Completed(
                uploadId = uploadId,
                fileId = completeResponse.fileId,
                downloadUrl = completeResponse.downloadUrl,
                metadata = completeResponse.metadata?.let { json ->
                    json.entries.associate { (k, v) -> k to v.toString().removeSurrounding("\"") }
                } ?: emptyMap(),
                blurHash = completeResponse.blurHash,
            ))
        } catch (e: CancellationException) {
            emit(UploadState.Cancelled)
        } catch (e: Exception) {
            val category = errorClassifier.classify(null, e)
            emit(UploadState.Failed(
                uploadId = uploadId,
                error = UploadError.Unknown(technicalMessage = e.message),
                isRetryable = errorClassifier.isRetryable(category),
            ))
        }
    }

    fun pause(uploadId: String): Boolean {
        return kotlinx.coroutines.runBlocking {
            pauseMutex.withLock { pausedUploads.add(uploadId) }
            uploadRepository.updateStatus(uploadId, "PAUSED", 0.0, currentTimeMs())
            true
        }
    }

    fun cancel(uploadId: String): Boolean {
        return kotlinx.coroutines.runBlocking {
            pauseMutex.withLock {
                cancelledUploads.add(uploadId)
                pausedUploads.remove(uploadId)
            }
            uploadRepository.updateStatus(uploadId, "CANCELLED", 0.0, currentTimeMs())
            chunkStateRepository.deleteChunks(uploadId)
            progressFlows.remove(uploadId)
            true
        }
    }

    fun getProgress(uploadId: String): StateFlow<UploadProgress> {
        return progressFlows[uploadId] ?: MutableStateFlow(UploadProgress(
            uploadId = uploadId, progress = 0f, bytesUploaded = 0L,
            totalBytes = 0L, bytesPerSecond = 0L, estimatedTimeRemaining = -1L,
        ))
    }
}
