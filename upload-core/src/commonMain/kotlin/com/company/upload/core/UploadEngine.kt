package com.company.upload.core

import com.company.upload.domain.ErrorClassifier
import com.company.upload.domain.FileValidator
import com.company.upload.domain.UploadConfig
import com.company.upload.domain.UploadError
import com.company.upload.domain.UploadLog
import com.company.upload.domain.UploadLogger
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

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${kb.toInt()} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${(mb * 10).toInt() / 10.0} MB"
        val gb = mb / 1024.0
        return "${(gb * 100).toInt() / 100.0} GB"
    }

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
    ): Flow<UploadState> = channelFlow {

        UploadLog.block(
            "📦 UPLOAD STARTED",
            "File: $fileName",
            "Size: ${formatSize(fileSize)} ($fileSize bytes)",
            "MIME: ${mimeType ?: "unknown"}",
            "Entity: ${metadata.entityType}/${metadata.entityId}",
        )

        // 1. Validate
        send(UploadState.Validating)
        UploadLogger.i("🔍 Step 1/7 — Validating file...")

        val validation = validator.validate(fileName, fileSize, mimeType)
        if (!validation.isValid) {
            UploadLogger.e("❌ VALIDATION FAILED: ${validation.errors.joinToString(", ")}")
            send(UploadState.Failed(
                uploadId = null,
                error = UploadError.Validation(
                    code = "VALIDATION_FAILED",
                    userMessage = validation.errors.joinToString(", "),
                    field = "file",
                ),
                isRetryable = false,
            ))
            return@channelFlow
        }
        UploadLogger.i("✅ Validation passed")

        // 2. Request SAS token
        send(UploadState.RequestingToken)
        UploadLogger.i("🔑 Step 2/7 — Requesting upload token from backend...")

        val initResponse = try {
            val startTime = currentTimeMs()
            val resp = apiClient.initiateUpload(InitiateUploadRequest(
                fileName = fileName,
                fileSize = fileSize,
                mimeType = mimeType,
                entityType = metadata.entityType,
                entityId = metadata.entityId,
                metadata = metadata.customMetadata,
                isPublic = metadata.isPublic,
            ))
            val elapsed = currentTimeMs() - startTime
            UploadLog.block(
                "✅ TOKEN RECEIVED (${elapsed}ms)",
                "Upload ID: ${resp.uploadId}",
                "Blob URL: ${resp.blobUrl.take(80)}...",
                "SAS Token: ${resp.sasToken.take(20)}...",
                "Expires: ${resp.expiresAt}",
            )
            resp
        } catch (e: Exception) {
            UploadLogger.e("❌ TOKEN REQUEST FAILED: ${e.message}")
            val category = errorClassifier.classify(null, e)
            send(UploadState.Failed(
                uploadId = null,
                error = UploadError.Network(code = "INIT_FAILED", userMessage = "Baglanti kurulamadi."),
                isRetryable = errorClassifier.isRetryable(category),
            ))
            return@channelFlow
        }

        val uploadId = initResponse.uploadId
        val now = currentTimeMs()

        // 3. Persist upload state
        UploadLogger.d("💾 Step 3/7 — Saving upload state to local DB...")
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
        UploadLog.block(
            "📊 Step 4/7 — Upload Strategy",
            "Strategy: ${strategy.strategy}",
            "Chunk Size: ${formatSize(strategy.chunkSize)}",
            "Concurrency: ${strategy.concurrency}",
        )

        try {
            if (strategy.strategy == UploadStrategyType.SINGLE_SHOT) {
                UploadLogger.i("⬆️ Step 5/7 — Single-shot upload (${formatSize(fileSize)})...")
                send(UploadState.Uploading(
                    uploadId = uploadId, progress = 0f, bytesUploaded = 0L, totalBytes = fileSize,
                    bytesPerSecond = 0L, estimatedTimeRemaining = -1L, currentChunk = 1, totalChunks = 1,
                ))
                // Single-shot: read entire file and upload
                UploadLogger.d("   Reading file data...")
                val fileData = readFileData(0, fileSize)
                UploadLogger.d("   Uploading to Azure Blob Storage...")
                val startTime = currentTimeMs()
                blobUploader.uploadSingleShot(
                    blobUrl = initResponse.blobUrl,
                    sasToken = initResponse.sasToken,
                    data = fileData,
                    contentType = mimeType,
                )
                val elapsed = currentTimeMs() - startTime
                bandwidthEstimator.recordSample(fileSize, elapsed)
                UploadLogger.i("✅ Single-shot upload complete (${elapsed}ms, ${formatSize((fileSize * 1000 / maxOf(elapsed, 1)))}/s)")
            } else {
                val chunks = chunkManager.createChunks(fileSize, strategy)
                UploadLogger.i("⬆️ Step 5/7 — Chunked upload: ${chunks.size} chunks, ${strategy.concurrency} concurrent")

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
                val uploadStartTime = currentTimeMs()

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

                                UploadLogger.d("   📦 Chunk ${chunk.index + 1}/$totalChunks — offset=${chunk.offset} size=${formatSize(chunk.size)} blockId=${chunk.blockId}")

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
                                    val elapsed = currentTimeMs() - startTime
                                    bandwidthEstimator.recordSample(chunk.size, elapsed)
                                    UploadLogger.d("   ✅ Chunk ${chunk.index + 1}/$totalChunks done (${elapsed}ms)")
                                }

                                chunkStateRepository.markChunkUploaded(uploadId, chunk.index.toLong())
                                uploadedChunks++
                                totalBytesUploaded += chunk.size

                                val progress = totalBytesUploaded.toFloat() / fileSize
                                val bps = bandwidthEstimator.estimateBytesPerSecond()
                                val eta = bandwidthEstimator.estimateTimeRemainingMs(fileSize - totalBytesUploaded)

                                UploadLogger.i("   📈 Progress: ${(progress * 100).toInt()}% — $uploadedChunks/$totalChunks chunks — ${formatSize(bps)}/s — ETA: ${eta / 1000}s")

                                send(UploadState.Uploading(
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

                val totalElapsed = currentTimeMs() - uploadStartTime
                UploadLogger.i("✅ All $totalChunks chunks uploaded in ${totalElapsed}ms")
            }

            // 6. Commit
            UploadLogger.i("🔒 Step 6/7 — Committing block list to Azure...")
            send(UploadState.Committing)
            val allChunks = chunkStateRepository.getAllChunks(uploadId)
            UploadLogger.d("   Block IDs: ${allChunks.map { it.blockId }}")

            val commitStartTime = currentTimeMs()
            val completeResponse = apiClient.completeUpload(CompleteUploadRequest(
                uploadId = uploadId,
                blockIds = allChunks.map { it.blockId },
            ))
            val commitElapsed = currentTimeMs() - commitStartTime

            UploadLog.block(
                "✅ Step 7/7 — UPLOAD COMPLETE (commit: ${commitElapsed}ms)",
                "File ID: ${completeResponse.fileId}",
                "Download URL: ${completeResponse.downloadUrl.take(80)}...",
                "BlurHash: ${completeResponse.blurHash ?: "none"}",
            )

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

            send(UploadState.Completed(
                uploadId = uploadId,
                fileId = completeResponse.fileId,
                downloadUrl = completeResponse.downloadUrl,
                metadata = completeResponse.metadata?.let { json ->
                    json.entries.associate { (k, v) -> k to v.toString().removeSurrounding("\"") }
                } ?: emptyMap(),
                blurHash = completeResponse.blurHash,
            ))
        } catch (e: CancellationException) {
            UploadLogger.w("🛑 Upload CANCELLED: $uploadId")
            send(UploadState.Cancelled)
        } catch (e: Exception) {
            UploadLogger.e("❌ Upload FAILED: ${e::class.simpleName} — ${e.message}")
            val category = errorClassifier.classify(null, e)
            send(UploadState.Failed(
                uploadId = uploadId,
                error = UploadError.Unknown(technicalMessage = e.message),
                isRetryable = errorClassifier.isRetryable(category),
            ))
        }
    }

    fun pause(uploadId: String): Boolean {
        UploadLogger.w("⏸️ PAUSE requested: $uploadId")
        return kotlinx.coroutines.runBlocking {
            pauseMutex.withLock { pausedUploads.add(uploadId) }
            uploadRepository.updateStatus(uploadId, "PAUSED", 0.0, currentTimeMs())
            true
        }
    }

    fun cancel(uploadId: String): Boolean {
        UploadLogger.w("🛑 CANCEL requested: $uploadId")
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
