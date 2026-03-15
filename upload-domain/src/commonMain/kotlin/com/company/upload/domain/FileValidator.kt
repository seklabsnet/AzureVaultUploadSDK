package com.company.upload.domain

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
) {
    companion object {
        fun success(): ValidationResult = ValidationResult(isValid = true, errors = emptyList())
        fun failure(errors: List<String>): ValidationResult = ValidationResult(isValid = false, errors = errors)
    }
}

object FileValidator {

    private const val MAX_FILE_NAME_LENGTH = 255
    private val PATH_TRAVERSAL_PATTERN = Regex("""(^|[/\\])\.\.[/\\]""")

    // Size limits per media category (in bytes)
    private const val MB = 1_048_576L
    private const val GB = 1_073_741_824L

    private const val MAX_IMAGE_SIZE = 50 * MB        // 50 MB
    private const val MAX_VIDEO_SIZE = 5 * GB          // 5 GB
    private const val MAX_DOCUMENT_SIZE = 500 * MB     // 500 MB
    private const val MAX_ARCHIVE_SIZE = 2 * GB        // 2 GB
    private const val MAX_AUDIO_SIZE = 500 * MB        // 500 MB
    private const val MAX_OTHER_SIZE = 1 * GB          // 1 GB

    private val IMAGE_TYPES = setOf(
        "image/jpeg", "image/png", "image/gif", "image/webp",
        "image/bmp", "image/tiff", "image/svg+xml", "image/heic", "image/heif",
    )
    private val VIDEO_TYPES = setOf(
        "video/mp4", "video/quicktime", "video/x-msvideo", "video/x-matroska",
        "video/webm", "video/mpeg", "video/3gpp",
    )
    private val DOCUMENT_TYPES = setOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain", "text/csv", "text/html",
    )
    private val ARCHIVE_TYPES = setOf(
        "application/zip", "application/x-tar", "application/gzip",
        "application/x-7z-compressed", "application/x-rar-compressed",
    )
    private val AUDIO_TYPES = setOf(
        "audio/mpeg", "audio/mp4", "audio/wav", "audio/ogg",
        "audio/aac", "audio/flac", "audio/webm",
    )

    /**
     * Validates a file before upload.
     *
     * @param name         The file name (not a full path).
     * @param size         The file size in bytes.
     * @param mimeType     The MIME type of the file, or `null` if unknown.
     * @param allowedMimeTypes Optional allowlist. When non-null only these types are accepted.
     */
    fun validate(
        name: String,
        size: Long,
        mimeType: String?,
        allowedMimeTypes: Set<String>? = null,
    ): ValidationResult {
        val errors = mutableListOf<String>()

        // --- file name checks ---
        if (name.isBlank()) {
            errors += "File name must not be empty or blank."
        }
        if (name.length > MAX_FILE_NAME_LENGTH) {
            errors += "File name exceeds maximum length of $MAX_FILE_NAME_LENGTH characters."
        }
        if (PATH_TRAVERSAL_PATTERN.containsMatchIn(name)) {
            errors += "File name contains path-traversal sequences."
        }

        // --- size checks ---
        if (size <= 0) {
            errors += "File size must be greater than zero."
        } else {
            val limit = maxSizeForMimeType(mimeType)
            if (size > limit) {
                errors += "File size (${size} bytes) exceeds maximum allowed (${limit} bytes) for type '${mimeType ?: "unknown"}'."
            }
        }

        // --- MIME-type allowlist ---
        if (allowedMimeTypes != null && mimeType != null && mimeType !in allowedMimeTypes) {
            errors += "MIME type '$mimeType' is not in the allowed list."
        }

        return if (errors.isEmpty()) ValidationResult.success() else ValidationResult.failure(errors)
    }

    private fun maxSizeForMimeType(mimeType: String?): Long = when {
        mimeType == null                -> MAX_OTHER_SIZE
        mimeType in IMAGE_TYPES         -> MAX_IMAGE_SIZE
        mimeType in VIDEO_TYPES         -> MAX_VIDEO_SIZE
        mimeType in DOCUMENT_TYPES      -> MAX_DOCUMENT_SIZE
        mimeType in ARCHIVE_TYPES       -> MAX_ARCHIVE_SIZE
        mimeType in AUDIO_TYPES         -> MAX_AUDIO_SIZE
        mimeType.startsWith("image/")   -> MAX_IMAGE_SIZE
        mimeType.startsWith("video/")   -> MAX_VIDEO_SIZE
        mimeType.startsWith("audio/")   -> MAX_AUDIO_SIZE
        else                            -> MAX_OTHER_SIZE
    }
}
