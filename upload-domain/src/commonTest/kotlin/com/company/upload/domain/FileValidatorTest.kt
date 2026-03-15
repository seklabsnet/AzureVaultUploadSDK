package com.company.upload.domain

import kotlin.test.*

class FileValidatorTest {

    private val MB = 1_048_576L
    private val GB = 1_073_741_824L

    @Test
    fun validFilePassesValidation() {
        val result = FileValidator.validate(
            name = "report.pdf",
            size = 1024L,
            mimeType = "application/pdf",
        )
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun emptyFileNameFails() {
        val result = FileValidator.validate(
            name = "",
            size = 1024L,
            mimeType = "text/plain",
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "empty" in it || "blank" in it })
    }

    @Test
    fun blankFileNameFails() {
        val result = FileValidator.validate(
            name = "   ",
            size = 1024L,
            mimeType = "text/plain",
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "empty" in it || "blank" in it })
    }

    @Test
    fun fileNameTooLongFails() {
        val longName = "a".repeat(256) + ".txt"
        val result = FileValidator.validate(
            name = longName,
            size = 1024L,
            mimeType = "text/plain",
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "maximum length" in it })
    }

    @Test
    fun fileNameExactly255CharsIsValid() {
        val name = "a".repeat(251) + ".txt" // 255 chars total
        val result = FileValidator.validate(
            name = name,
            size = 1024L,
            mimeType = "text/plain",
        )
        // Name length is fine; no name-related errors expected
        assertFalse(result.errors.any { "maximum length" in it })
    }

    @Test
    fun pathTraversalInNameFails() {
        val result = FileValidator.validate(
            name = "../etc/passwd",
            size = 1024L,
            mimeType = "text/plain",
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "path-traversal" in it })
    }

    @Test
    fun pathTraversalWithBackslashFails() {
        val result = FileValidator.validate(
            name = "..\\windows\\system32",
            size = 1024L,
            mimeType = "text/plain",
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "path-traversal" in it })
    }

    @Test
    fun imageExceedingSizeLimitFails() {
        val result = FileValidator.validate(
            name = "photo.jpg",
            size = 50 * MB + 1,
            mimeType = "image/jpeg",
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "exceeds maximum" in it })
    }

    @Test
    fun imageAtExactLimitPasses() {
        val result = FileValidator.validate(
            name = "photo.jpg",
            size = 50 * MB,
            mimeType = "image/jpeg",
        )
        assertTrue(result.isValid)
    }

    @Test
    fun videoExceedingSizeLimitFails() {
        val result = FileValidator.validate(
            name = "movie.mp4",
            size = 5 * GB + 1,
            mimeType = "video/mp4",
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "exceeds maximum" in it })
    }

    @Test
    fun documentExceedingSizeLimitFails() {
        val result = FileValidator.validate(
            name = "report.pdf",
            size = 500 * MB + 1,
            mimeType = "application/pdf",
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "exceeds maximum" in it })
    }

    @Test
    fun unsupportedMimeTypeFailsWhenAllowlistUsed() {
        val result = FileValidator.validate(
            name = "file.bin",
            size = 1024L,
            mimeType = "application/octet-stream",
            allowedMimeTypes = setOf("image/jpeg", "image/png"),
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "not in the allowed list" in it })
    }

    @Test
    fun allowedMimeTypePassesWithAllowlist() {
        val result = FileValidator.validate(
            name = "photo.png",
            size = 1024L,
            mimeType = "image/png",
            allowedMimeTypes = setOf("image/jpeg", "image/png"),
        )
        assertTrue(result.isValid)
    }

    @Test
    fun nullMimeTypeIsAccepted() {
        val result = FileValidator.validate(
            name = "unknown_file",
            size = 1024L,
            mimeType = null,
        )
        assertTrue(result.isValid)
    }

    @Test
    fun nullMimeTypeWithAllowlistIsAccepted() {
        // When mimeType is null, the allowlist check is skipped per implementation
        val result = FileValidator.validate(
            name = "unknown_file",
            size = 1024L,
            mimeType = null,
            allowedMimeTypes = setOf("image/jpeg"),
        )
        assertTrue(result.isValid)
    }

    @Test
    fun zeroSizeFileFails() {
        val result = FileValidator.validate(
            name = "empty.txt",
            size = 0L,
            mimeType = "text/plain",
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "greater than zero" in it })
    }

    @Test
    fun negativeSizeFileFails() {
        val result = FileValidator.validate(
            name = "negative.txt",
            size = -1L,
            mimeType = "text/plain",
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "greater than zero" in it })
    }
}
