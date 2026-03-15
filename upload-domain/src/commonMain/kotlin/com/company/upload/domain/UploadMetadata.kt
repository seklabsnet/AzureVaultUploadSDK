package com.company.upload.domain

import kotlinx.serialization.Serializable

@Serializable
data class UploadMetadata(
    val entityType: String,
    val entityId: String,
    val isPublic: Boolean = false,
    val customMetadata: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
)
