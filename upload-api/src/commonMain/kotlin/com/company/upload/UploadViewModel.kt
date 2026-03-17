package com.company.upload

import com.company.upload.domain.UploadMetadata
import com.company.upload.domain.UploadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UploadViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val uploader: AzureVaultUploader by lazy { AzureVaultUpload.uploader() }

    private val _state = MutableStateFlow<UploadUiState>(UploadUiState.Idle)
    val state: StateFlow<UploadUiState> = _state.asStateFlow()

    private var currentUploadId: String? = null

    fun upload(fileRef: PlatformFile, entityType: String, entityId: String) {
        scope.launch {
            uploader.upload(
                file = fileRef,
                metadata = UploadMetadata(entityType = entityType, entityId = entityId),
            ).collect { uploadState ->
                _state.value = when (uploadState) {
                    is UploadState.Validating ->
                        UploadUiState.Loading("Dosya kontrol ediliyor...")

                    is UploadState.RequestingToken ->
                        UploadUiState.Loading("Baglanti kuruluyor...")

                    is UploadState.Uploading -> {
                        currentUploadId = uploadState.uploadId
                        UploadUiState.Uploading(
                            progress = uploadState.progress,
                            speed = uploadState.bytesPerSecond,
                            eta = uploadState.estimatedTimeRemaining,
                        )
                    }

                    is UploadState.Paused ->
                        UploadUiState.Paused(uploadState.progress)

                    is UploadState.Committing ->
                        UploadUiState.Loading("Tamamlaniyor...")

                    is UploadState.Processing ->
                        UploadUiState.Loading("Isleniyor: ${uploadState.step}")

                    is UploadState.Completed ->
                        UploadUiState.Done(uploadState.downloadUrl, uploadState.fileId)

                    is UploadState.Failed ->
                        UploadUiState.Error(uploadState.error.userMessage, uploadState.isRetryable)

                    is UploadState.Cancelled ->
                        UploadUiState.Idle

                    is UploadState.Idle ->
                        UploadUiState.Idle
                }
            }
        }
    }

    fun pause() {
        currentUploadId?.let { uploader.pause(it) }
    }

    fun resume() {
        val id = currentUploadId ?: return
        scope.launch {
            uploader.resume(id).collect { uploadState ->
                if (uploadState is UploadState.Uploading) {
                    _state.value = UploadUiState.Uploading(
                        progress = uploadState.progress,
                        speed = uploadState.bytesPerSecond,
                        eta = uploadState.estimatedTimeRemaining,
                    )
                }
            }
        }
    }

    fun cancel() {
        currentUploadId?.let { uploader.cancel(it) }
        _state.value = UploadUiState.Idle
        currentUploadId = null
    }

    fun retry() {
        val id = currentUploadId ?: return
        scope.launch {
            uploader.retryFailed(id).collect { uploadState ->
                _state.value = when (uploadState) {
                    is UploadState.Uploading -> UploadUiState.Uploading(
                        progress = uploadState.progress,
                        speed = uploadState.bytesPerSecond,
                        eta = uploadState.estimatedTimeRemaining,
                    )
                    is UploadState.Completed -> UploadUiState.Done(uploadState.downloadUrl, uploadState.fileId)
                    is UploadState.Failed -> UploadUiState.Error(uploadState.error.userMessage, uploadState.isRetryable)
                    else -> _state.value
                }
            }
        }
    }

    fun clear() {
        currentUploadId?.let { uploader.cancel(it) }
        _state.value = UploadUiState.Idle
        currentUploadId = null
    }
}
