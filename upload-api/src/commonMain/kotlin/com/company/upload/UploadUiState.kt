package com.company.upload

sealed class UploadUiState {
    data object Idle : UploadUiState()
    data class Loading(val message: String) : UploadUiState()
    data class Uploading(val progress: Float, val speed: Long, val eta: Long) : UploadUiState()
    data class Paused(val progress: Float) : UploadUiState()
    data class Done(val downloadUrl: String, val fileId: String) : UploadUiState()
    data class Error(val message: String, val isRetryable: Boolean) : UploadUiState()
}
