package com.company.upload.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class UploadStatus {
    IDLE,
    VALIDATING,
    REQUESTING_TOKEN,
    UPLOADING,
    PAUSED,
    COMMITTING,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

internal class UploadStateMachine(initialStatus: UploadStatus = UploadStatus.IDLE) {

    private val _status = MutableStateFlow(initialStatus)
    val status: StateFlow<UploadStatus> = _status.asStateFlow()

    fun transition(to: UploadStatus): Boolean {
        val from = _status.value
        if (!isValidTransition(from, to)) return false
        _status.value = to
        return true
    }

    private fun isValidTransition(from: UploadStatus, to: UploadStatus): Boolean {
        val allowed = validTransitions[from] ?: return false
        return to in allowed
    }

    companion object {
        private val validTransitions = mapOf(
            UploadStatus.IDLE to setOf(UploadStatus.VALIDATING),
            UploadStatus.VALIDATING to setOf(UploadStatus.REQUESTING_TOKEN, UploadStatus.FAILED),
            UploadStatus.REQUESTING_TOKEN to setOf(UploadStatus.UPLOADING, UploadStatus.FAILED),
            UploadStatus.UPLOADING to setOf(
                UploadStatus.COMMITTING,
                UploadStatus.PAUSED,
                UploadStatus.FAILED,
                UploadStatus.CANCELLED,
            ),
            UploadStatus.PAUSED to setOf(UploadStatus.UPLOADING, UploadStatus.CANCELLED),
            UploadStatus.COMMITTING to setOf(UploadStatus.PROCESSING, UploadStatus.COMPLETED, UploadStatus.FAILED),
            UploadStatus.PROCESSING to setOf(UploadStatus.COMPLETED, UploadStatus.FAILED),
            UploadStatus.FAILED to setOf(UploadStatus.UPLOADING), // manual retry
            UploadStatus.COMPLETED to emptySet(), // terminal
            UploadStatus.CANCELLED to emptySet(), // terminal
        )
    }
}
