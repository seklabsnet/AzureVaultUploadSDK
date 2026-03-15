package com.company.upload.core

import com.company.upload.domain.ChunkConfig
import com.company.upload.domain.ChunkStrategy
import com.company.upload.domain.UploadConfig
import com.company.upload.domain.UploadStrategyType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkManagerTest {

    private val MB = 1_048_576L

    private val config = UploadConfig(
        baseUrl = "https://example.com",
        appId = "test-app",
    )
    private val manager = ChunkManager(config)

    // ---- Single-shot file ----

    @Test
    fun createChunks_singleShot_returnsSingleChunk() {
        val fileSize = 2 * MB
        val chunkConfig = ChunkConfig(
            chunkSize = fileSize,
            concurrency = 1,
            strategy = UploadStrategyType.SINGLE_SHOT,
        )
        val chunks = manager.createChunks(fileSize, chunkConfig)

        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].index)
        assertEquals(0L, chunks[0].offset)
        assertEquals(fileSize, chunks[0].size)
    }

    // ---- 16 MB file → 4 chunks of 4 MB ----

    @Test
    fun createChunks_16MB_returnsFourChunks() {
        val fileSize = 16 * MB
        val chunkConfig = ChunkConfig(
            chunkSize = 4 * MB,
            concurrency = 4,
            strategy = UploadStrategyType.CHUNKED,
        )
        val chunks = manager.createChunks(fileSize, chunkConfig)

        assertEquals(4, chunks.size)
        chunks.forEachIndexed { i, chunk ->
            assertEquals(i, chunk.index)
            assertEquals(i * 4 * MB, chunk.offset)
            assertEquals(4 * MB, chunk.size)
        }
    }

    // ---- File not evenly divisible: 10 MB → 3 chunks (4, 4, 2) ----

    @Test
    fun createChunks_10MB_returnsThreeChunksWithLastSmaller() {
        val fileSize = 10 * MB
        val chunkConfig = ChunkConfig(
            chunkSize = 4 * MB,
            concurrency = 4,
            strategy = UploadStrategyType.CHUNKED,
        )
        val chunks = manager.createChunks(fileSize, chunkConfig)

        assertEquals(3, chunks.size)
        assertEquals(4 * MB, chunks[0].size)
        assertEquals(4 * MB, chunks[1].size)
        assertEquals(2 * MB, chunks[2].size)

        // Verify offsets
        assertEquals(0L, chunks[0].offset)
        assertEquals(4 * MB, chunks[1].offset)
        assertEquals(8 * MB, chunks[2].offset)
    }

    // ---- Block IDs are all same length (Azure requirement) ----

    @Test
    fun blockIds_areSameLength() {
        val fileSize = 20 * MB
        val chunkConfig = ChunkConfig(
            chunkSize = 4 * MB,
            concurrency = 4,
            strategy = UploadStrategyType.CHUNKED,
        )
        val chunks = manager.createChunks(fileSize, chunkConfig)

        assertTrue(chunks.size > 1, "Need multiple chunks for this test")

        val lengths = chunks.map { it.blockId.length }.toSet()
        assertEquals(1, lengths.size, "All block IDs must be the same length")
    }

    // ---- Block IDs are unique per chunk ----

    @Test
    fun blockIds_areUnique() {
        val fileSize = 20 * MB
        val chunkConfig = ChunkConfig(
            chunkSize = 4 * MB,
            concurrency = 4,
            strategy = UploadStrategyType.CHUNKED,
        )
        val chunks = manager.createChunks(fileSize, chunkConfig)

        val ids = chunks.map { it.blockId }
        assertEquals(ids.size, ids.toSet().size, "Block IDs must be unique")
    }

    // ---- determineStrategy delegates to ChunkStrategy ----

    @Test
    fun determineStrategy_smallFile_singleShot() {
        val result = manager.determineStrategy(2 * MB)
        assertEquals(UploadStrategyType.SINGLE_SHOT, result.strategy)
    }

    @Test
    fun determineStrategy_mediumFile_chunked() {
        val result = manager.determineStrategy(16 * MB)
        assertEquals(UploadStrategyType.CHUNKED, result.strategy)
        assertEquals(4 * MB, result.chunkSize)
        assertEquals(4, result.concurrency)
    }

    @Test
    fun determineStrategy_matchesChunkStrategy() {
        val fileSize = 500 * MB
        val expected = ChunkStrategy.determineStrategy(fileSize)
        val actual = manager.determineStrategy(fileSize)
        assertEquals(expected, actual)
    }
}
