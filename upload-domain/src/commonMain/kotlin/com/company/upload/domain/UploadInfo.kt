package com.company.upload.domain

data class UploadInfo(
    val uploadId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String?,
    val progress: Float,
    val state: UploadState,
    val createdAt: Long,
    val entityType: String,
    val entityId: String,
)
