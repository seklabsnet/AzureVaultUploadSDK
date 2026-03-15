package com.company.upload.storage

import com.company.upload.storage.db.UploadDatabase

data class ChunkInfo(
    val uploadId: String,
    val chunkIndex: Long,
    val blockId: String,
    val size: Long,
    val offset: Long,
    val uploaded: Boolean,
)

class ChunkStateRepository(private val database: UploadDatabase) {

    private val queries get() = database.chunkStateQueries

    fun saveChunks(uploadId: String, chunks: List<ChunkInfo>) {
        database.transaction {
            chunks.forEach { chunk ->
                queries.insert(
                    upload_id = uploadId,
                    chunk_index = chunk.chunkIndex,
                    block_id = chunk.blockId,
                    size = chunk.size,
                    offset = chunk.offset,
                )
            }
        }
    }

    fun getPendingChunks(uploadId: String): List<ChunkInfo> {
        return queries.selectPendingByUpload(uploadId).executeAsList().map { it.toChunkInfo() }
    }

    fun getAllChunks(uploadId: String): List<ChunkInfo> {
        return queries.selectByUpload(uploadId).executeAsList().map { it.toChunkInfo() }
    }

    fun markChunkUploaded(uploadId: String, chunkIndex: Long) {
        queries.markUploaded(upload_id = uploadId, chunk_index = chunkIndex)
    }

    fun deleteChunks(uploadId: String) {
        queries.deleteByUpload(uploadId)
    }

    fun getProgress(uploadId: String): Float {
        val uploaded = queries.countUploaded(uploadId).executeAsOne()
        val total = queries.countTotal(uploadId).executeAsOne()
        if (total == 0L) return 0f
        return (uploaded.toFloat() / total.toFloat())
    }
}

private fun com.company.upload.storage.db.Chunk_state.toChunkInfo(): ChunkInfo {
    return ChunkInfo(
        uploadId = upload_id,
        chunkIndex = chunk_index,
        blockId = block_id,
        size = size,
        offset = offset,
        uploaded = uploaded != 0L,
    )
}
