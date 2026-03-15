package com.company.upload.core

import com.company.upload.domain.UploadConfig
import com.company.upload.domain.ChunkConfig
import com.company.upload.domain.ChunkStrategy
import com.company.upload.domain.UploadStrategyType
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal data class ChunkInfo(
    val index: Int,
    val blockId: String,
    val offset: Long,
    val size: Long,
)

internal class ChunkManager(private val config: UploadConfig) {

    fun determineStrategy(fileSize: Long): ChunkConfig {
        return ChunkStrategy.determineStrategy(fileSize)
    }

    fun createChunks(fileSize: Long, chunkConfig: ChunkConfig): List<ChunkInfo> {
        if (chunkConfig.strategy == UploadStrategyType.SINGLE_SHOT) {
            return listOf(
                ChunkInfo(
                    index = 0,
                    blockId = generateBlockId(0),
                    offset = 0,
                    size = fileSize,
                )
            )
        }

        val chunks = mutableListOf<ChunkInfo>()
        var offset = 0L
        var index = 0

        while (offset < fileSize) {
            val size = minOf(chunkConfig.chunkSize, fileSize - offset)
            chunks.add(
                ChunkInfo(
                    index = index,
                    blockId = generateBlockId(index),
                    offset = offset,
                    size = size,
                )
            )
            offset += size
            index++
        }

        return chunks
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun generateBlockId(index: Int): String {
        // Azure requires all block IDs in a blob to be the same length
        // Pad index to 6 digits, then base64 encode
        val padded = index.toString().padStart(6, '0')
        return Base64.encode(padded.encodeToByteArray())
    }
}
