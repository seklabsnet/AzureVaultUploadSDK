package com.company.upload.storage

import com.company.upload.storage.db.UploadDatabase

data class UploadEntity(
    val uploadId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String?,
    val entityType: String,
    val entityId: String,
    val status: String,
    val progress: Double,
    val blobUrl: String?,
    val sasToken: String?,
    val sasExpiresAt: String?,
    val fileId: String?,
    val downloadUrl: String?,
    val blurHash: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

class UploadRepository(private val database: UploadDatabase) {

    private val queries get() = database.uploadQueries

    fun saveUpload(
        uploadId: String,
        fileName: String,
        fileSize: Long,
        mimeType: String?,
        entityType: String,
        entityId: String,
        status: String,
        progress: Double,
        blobUrl: String?,
        sasToken: String?,
        sasExpiresAt: String?,
        createdAt: Long,
        updatedAt: Long,
    ) {
        queries.insert(
            upload_id = uploadId,
            file_name = fileName,
            file_size = fileSize,
            mime_type = mimeType,
            entity_type = entityType,
            entity_id = entityId,
            status = status,
            progress = progress,
            blob_url = blobUrl,
            sas_token = sasToken,
            sas_expires_at = sasExpiresAt,
            created_at = createdAt,
            updated_at = updatedAt,
        )
    }

    fun getUpload(id: String): UploadEntity? {
        return queries.selectById(id).executeAsOneOrNull()?.toEntity()
    }

    fun getPendingUploads(): List<UploadEntity> {
        return queries.selectAll().executeAsList().map { it.toEntity() }
    }

    fun updateStatus(uploadId: String, status: String, progress: Double, updatedAt: Long) {
        queries.updateStatus(
            status = status,
            progress = progress,
            updated_at = updatedAt,
            upload_id = uploadId,
        )
    }

    fun updateSasToken(uploadId: String, sasToken: String, sasExpiresAt: String, updatedAt: Long) {
        queries.updateSasToken(
            sas_token = sasToken,
            sas_expires_at = sasExpiresAt,
            updated_at = updatedAt,
            upload_id = uploadId,
        )
    }

    fun markCompleted(
        uploadId: String,
        fileId: String?,
        downloadUrl: String?,
        blurHash: String?,
        updatedAt: Long,
    ) {
        queries.updateCompleted(
            file_id = fileId,
            download_url = downloadUrl,
            blur_hash = blurHash,
            updated_at = updatedAt,
            upload_id = uploadId,
        )
    }

    fun deleteUpload(uploadId: String) {
        queries.delete(uploadId)
    }
}

private fun com.company.upload.storage.db.Upload.toEntity(): UploadEntity {
    return UploadEntity(
        uploadId = upload_id,
        fileName = file_name,
        fileSize = file_size,
        mimeType = mime_type,
        entityType = entity_type,
        entityId = entity_id,
        status = status,
        progress = progress,
        blobUrl = blob_url,
        sasToken = sas_token,
        sasExpiresAt = sas_expires_at,
        fileId = file_id,
        downloadUrl = download_url,
        blurHash = blur_hash,
        createdAt = created_at,
        updatedAt = updated_at,
    )
}
