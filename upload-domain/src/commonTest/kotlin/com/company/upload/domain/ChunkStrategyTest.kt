package com.company.upload.domain

import kotlin.test.*

class ChunkStrategyTest {

    private val MB = 1_048_576L
    private val GB = 1_073_741_824L

    @Test
    fun fileSmallerThan4MB_singleShot() {
        val config = ChunkStrategy.determineStrategy(3 * MB)
        assertEquals(UploadStrategyType.SINGLE_SHOT, config.strategy)
        assertEquals(3 * MB, config.chunkSize)
        assertEquals(1, config.concurrency)
    }

    @Test
    fun fileExactly4MB_singleShot() {
        // 4 MB <= SINGLE_SHOT_THRESHOLD (4 MB), so SINGLE_SHOT
        val config = ChunkStrategy.determineStrategy(4 * MB)
        assertEquals(UploadStrategyType.SINGLE_SHOT, config.strategy)
        assertEquals(4 * MB, config.chunkSize)
        assertEquals(1, config.concurrency)
    }

    @Test
    fun fileJustOver4MB_chunked4MBConcurrency4() {
        val config = ChunkStrategy.determineStrategy(4 * MB + 1)
        assertEquals(UploadStrategyType.CHUNKED, config.strategy)
        assertEquals(4 * MB, config.chunkSize)
        assertEquals(4, config.concurrency)
    }

    @Test
    fun file100MB_chunked4MBConcurrency4() {
        val config = ChunkStrategy.determineStrategy(100 * MB)
        assertEquals(UploadStrategyType.CHUNKED, config.strategy)
        assertEquals(4 * MB, config.chunkSize)
        assertEquals(4, config.concurrency)
    }

    @Test
    fun file256MB_chunked8MBConcurrency6() {
        val config = ChunkStrategy.determineStrategy(256 * MB)
        assertEquals(UploadStrategyType.CHUNKED, config.strategy)
        // 256 MB <= MEDIUM_THRESHOLD (256 MB), so 4MB chunks, concurrency 4
        assertEquals(4 * MB, config.chunkSize)
        assertEquals(4, config.concurrency)
    }

    @Test
    fun fileJustOver256MB_chunked8MBConcurrency6() {
        val config = ChunkStrategy.determineStrategy(256 * MB + 1)
        assertEquals(UploadStrategyType.CHUNKED, config.strategy)
        assertEquals(8 * MB, config.chunkSize)
        assertEquals(6, config.concurrency)
    }

    @Test
    fun file500MB_chunked8MBConcurrency6() {
        val config = ChunkStrategy.determineStrategy(500 * MB)
        assertEquals(UploadStrategyType.CHUNKED, config.strategy)
        assertEquals(8 * MB, config.chunkSize)
        assertEquals(6, config.concurrency)
    }

    @Test
    fun file1GB_chunked16MBConcurrency8() {
        val config = ChunkStrategy.determineStrategy(1 * GB)
        assertEquals(UploadStrategyType.CHUNKED, config.strategy)
        // 1 GB <= LARGE_THRESHOLD (1 GB), so 8MB chunks, concurrency 6
        assertEquals(8 * MB, config.chunkSize)
        assertEquals(6, config.concurrency)
    }

    @Test
    fun fileJustOver1GB_chunked16MBConcurrency8() {
        val config = ChunkStrategy.determineStrategy(1 * GB + 1)
        assertEquals(UploadStrategyType.CHUNKED, config.strategy)
        assertEquals(16 * MB, config.chunkSize)
        assertEquals(8, config.concurrency)
    }

    @Test
    fun file3GB_chunked16MBConcurrency8() {
        val config = ChunkStrategy.determineStrategy(3 * GB)
        assertEquals(UploadStrategyType.CHUNKED, config.strategy)
        assertEquals(16 * MB, config.chunkSize)
        assertEquals(8, config.concurrency)
    }

    @Test
    fun file5GB_chunked32MBConcurrency8() {
        val config = ChunkStrategy.determineStrategy(5 * GB)
        assertEquals(UploadStrategyType.CHUNKED, config.strategy)
        // 5 GB <= VERY_LARGE_THRESHOLD (5 GB), so 16MB chunks, concurrency 8
        assertEquals(16 * MB, config.chunkSize)
        assertEquals(8, config.concurrency)
    }

    @Test
    fun fileJustOver5GB_chunked32MBConcurrency8() {
        val config = ChunkStrategy.determineStrategy(5 * GB + 1)
        assertEquals(UploadStrategyType.CHUNKED, config.strategy)
        assertEquals(32 * MB, config.chunkSize)
        assertEquals(8, config.concurrency)
    }

    @Test
    fun file10GB_chunked32MBConcurrency8() {
        val config = ChunkStrategy.determineStrategy(10 * GB)
        assertEquals(UploadStrategyType.CHUNKED, config.strategy)
        assertEquals(32 * MB, config.chunkSize)
        assertEquals(8, config.concurrency)
    }
}
