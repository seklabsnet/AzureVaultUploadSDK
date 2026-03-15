package com.company.upload.domain

data class BatchUploadState(
    val totalFiles: Int,
    val completedFiles: Int,
    val failedFiles: Int,
    val currentFile: UploadState,
    val overallProgress: Float, // 0.0 — 1.0
)
