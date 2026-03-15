package com.company.upload.domain

enum class UploadStrategyType {
    SINGLE_SHOT,
    CHUNKED,
}

data class ChunkConfig(
    val chunkSize: Long,
    val concurrency: Int,
    val strategy: UploadStrategyType,
)

object ChunkStrategy {

    private const val MB = 1_048_576L
    private const val GB = 1_073_741_824L

    private const val SINGLE_SHOT_THRESHOLD = 4 * MB       // 0 – 4 MB
    private const val MEDIUM_THRESHOLD = 256 * MB           // 4 – 256 MB
    private const val LARGE_THRESHOLD = 1 * GB              // 256 MB – 1 GB
    private const val VERY_LARGE_THRESHOLD = 5 * GB         // 1 – 5 GB

    /**
     * Determines chunking parameters based on total file size.
     *
     * | File size       | Chunk size | Concurrency | Strategy    |
     * |-----------------|-----------|-------------|-------------|
     * | 0 – 4 MB        | N/A       | 1           | SINGLE_SHOT |
     * | 4 – 256 MB      | 4 MB      | 4           | CHUNKED     |
     * | 256 MB – 1 GB   | 8 MB      | 6           | CHUNKED     |
     * | 1 – 5 GB        | 16 MB     | 8           | CHUNKED     |
     * | 5 GB+           | 32 MB     | 8           | CHUNKED     |
     */
    fun determineStrategy(fileSize: Long): ChunkConfig = when {
        fileSize <= SINGLE_SHOT_THRESHOLD -> ChunkConfig(
            chunkSize = fileSize,
            concurrency = 1,
            strategy = UploadStrategyType.SINGLE_SHOT,
        )
        fileSize <= MEDIUM_THRESHOLD -> ChunkConfig(
            chunkSize = 4 * MB,
            concurrency = 4,
            strategy = UploadStrategyType.CHUNKED,
        )
        fileSize <= LARGE_THRESHOLD -> ChunkConfig(
            chunkSize = 8 * MB,
            concurrency = 6,
            strategy = UploadStrategyType.CHUNKED,
        )
        fileSize <= VERY_LARGE_THRESHOLD -> ChunkConfig(
            chunkSize = 16 * MB,
            concurrency = 8,
            strategy = UploadStrategyType.CHUNKED,
        )
        else -> ChunkConfig(
            chunkSize = 32 * MB,
            concurrency = 8,
            strategy = UploadStrategyType.CHUNKED,
        )
    }
}
