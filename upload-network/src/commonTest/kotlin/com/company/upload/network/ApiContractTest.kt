package com.company.upload.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests — verify SDK DTOs can parse actual backend responses.
 * These JSON strings mirror the exact format returned by the Azure Functions backend.
 */
class ApiContractTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ── Initiate Upload Response ──

    @Test
    fun parseInitiateResponse_singleShot() {
        val raw = """
            {
                "success": true,
                "data": {
                    "uploadId": "e3c2e3df-f94b-43c0-b59a-d94dcccf6269",
                    "blobUrl": "https://YOUR_STORAGE_ACCOUNT.blob.core.windows.net/uploads-centauri/profile/u123/2026/03/e3c2e3df/test.jpg",
                    "sasToken": "sv=2026-02-06&spr=https&sig=XHXg2UfXhY099cX4q3XF955OfaoKgRjFzNTn9AfMThc%3D",
                    "strategy": "SINGLE_SHOT",
                    "maxBlockSize": 1024,
                    "expiresAt": "2026-03-16T10:27:32.474Z"
                }
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<InitiateUploadResponse>>(raw)

        assertTrue(response.success)
        assertNotNull(response.data)
        assertEquals("e3c2e3df-f94b-43c0-b59a-d94dcccf6269", response.data!!.uploadId)
        assertEquals("SINGLE_SHOT", response.data!!.strategy)
        assertEquals(1024L, response.data!!.maxBlockSize)
        assertNull(response.data!!.chunkCount)
        assertTrue(response.data!!.blobUrl.startsWith("https://"))
        assertTrue(response.data!!.sasToken.contains("sig="))
    }

    @Test
    fun parseInitiateResponse_chunked() {
        val raw = """
            {
                "success": true,
                "data": {
                    "uploadId": "chunk-456",
                    "blobUrl": "https://YOUR_STORAGE_ACCOUNT.blob.core.windows.net/uploads-centauri/video/v1/2026/03/chunk-456/movie.mp4",
                    "sasToken": "sv=2026-02-06&sig=abc",
                    "strategy": "CHUNKED",
                    "maxBlockSize": 8388608,
                    "expiresAt": "2026-03-16T10:27:32.474Z",
                    "chunkCount": 12
                }
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<InitiateUploadResponse>>(raw)

        assertEquals("CHUNKED", response.data!!.strategy)
        assertEquals(12, response.data!!.chunkCount)
        assertEquals(8_388_608L, response.data!!.maxBlockSize)
    }

    // ── Complete Upload Response ──

    @Test
    fun parseCompleteResponse() {
        val raw = """
            {
                "success": true,
                "data": {
                    "fileId": "cd8e706c-6b4f-4439-aacd-0e63dd7ae70b",
                    "downloadUrl": "https://YOUR_STORAGE_ACCOUNT.blob.core.windows.net/uploads-centauri/test.jpg?sv=2026&sig=abc",
                    "metadata": {
                        "fileName": "final-test.jpg",
                        "fileSize": 26,
                        "mimeType": "image/jpeg",
                        "entityType": "avatar",
                        "entityId": "user-final"
                    },
                    "processingStatus": "PENDING",
                    "blurHash": null
                }
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<CompleteUploadResponse>>(raw)

        assertTrue(response.success)
        assertEquals("cd8e706c-6b4f-4439-aacd-0e63dd7ae70b", response.data!!.fileId)
        assertEquals("PENDING", response.data!!.processingStatus)
        assertNull(response.data!!.blurHash)

        // metadata is JsonObject with mixed types (string + number)
        val metadata = response.data!!.metadata
        assertNotNull(metadata)
        assertEquals("final-test.jpg", metadata["fileName"]?.jsonPrimitive?.content)
    }

    // ── Upload Status Response ──

    @Test
    fun parseStatusResponse_completed() {
        val raw = """
            {
                "success": true,
                "data": {
                    "uploadId": "e87123af-7a2c-4b27-b825-8cf8c0bf9f19",
                    "status": "COMPLETED",
                    "progress": 100,
                    "fileId": "cd8e706c-6b4f-4439-aacd-0e63dd7ae70b",
                    "downloadUrl": "https://storage.blob.core.windows.net/uploads/test.jpg?sas=token",
                    "blurHash": "LGF5]+Yk^6#M"
                }
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<UploadStatusResponse>>(raw)

        assertEquals("COMPLETED", response.data!!.status)
        assertEquals(100f, response.data!!.progress)
        assertNotNull(response.data!!.fileId)
        assertNotNull(response.data!!.blurHash)
    }

    @Test
    fun parseStatusResponse_uploading_minimalFields() {
        val raw = """
            {
                "success": true,
                "data": {
                    "uploadId": "abc",
                    "status": "UPLOADING",
                    "progress": 45
                }
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<UploadStatusResponse>>(raw)

        assertEquals("UPLOADING", response.data!!.status)
        assertEquals(45f, response.data!!.progress)
        assertNull(response.data!!.fileId)
        assertNull(response.data!!.downloadUrl)
    }

    // ── Download URL Response ──

    @Test
    fun parseDownloadUrlResponse_private() {
        val raw = """
            {
                "success": true,
                "data": {
                    "downloadUrl": "https://YOUR_STORAGE_ACCOUNT.blob.core.windows.net/uploads-centauri/test.jpg?sv=2026&sig=fresh",
                    "contentType": "image/jpeg",
                    "fileSize": 26,
                    "expiresAt": "2026-03-16T11:27:22.119Z"
                }
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<DownloadUrlResponse>>(raw)

        assertEquals("image/jpeg", response.data!!.contentType)
        assertEquals(26L, response.data!!.fileSize)
        assertNotNull(response.data!!.expiresAt)
    }

    @Test
    fun parseDownloadUrlResponse_public_noExpiry() {
        val raw = """
            {
                "success": true,
                "data": {
                    "downloadUrl": "https://cdn.example.com/file-001",
                    "contentType": "image/png",
                    "fileSize": 50000
                }
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<DownloadUrlResponse>>(raw)

        assertNull(response.data!!.expiresAt)
        assertEquals(50000L, response.data!!.fileSize)
    }

    // ── Error Response ──

    @Test
    fun parseErrorResponse_authError() {
        val raw = """
            {
                "success": false,
                "error": {
                    "code": "AUTH_ERROR",
                    "message": "Token has expired. Request a new one via POST /v1/auth/token"
                }
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<InitiateUploadResponse>>(raw)

        assertEquals(false, response.success)
        assertNull(response.data)
        assertNotNull(response.error)
        assertEquals("AUTH_ERROR", response.error!!.code)
        assertTrue(response.error!!.message.contains("expired"))
    }

    @Test
    fun parseErrorResponse_validationError() {
        val raw = """
            {
                "success": false,
                "error": {
                    "code": "VALIDATION_ERROR",
                    "message": "MIME type \"text/plain\" is not allowed. Allowed types: image/*, video/*"
                }
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<InitiateUploadResponse>>(raw)

        assertEquals("VALIDATION_ERROR", response.error!!.code)
        assertTrue(response.error!!.message.contains("not allowed"))
    }

    @Test
    fun parseErrorResponse_rateLimited() {
        val raw = """
            {
                "success": false,
                "error": {
                    "code": "RATE_LIMITED",
                    "message": "Rate limit exceeded. Retry after 60s"
                }
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<InitiateUploadResponse>>(raw)

        assertEquals("RATE_LIMITED", response.error!!.code)
    }

    // ── Envelope structure ──

    @Test
    fun envelopeAlwaysHasSuccessField() {
        val successRaw = """{"success": true, "data": {"uploadId":"x","status":"OK","progress":0}}"""
        val errorRaw = """{"success": false, "error": {"code": "X", "message": "Y"}}"""

        val s = json.decodeFromString<ApiResponse<UploadStatusResponse>>(successRaw)
        val e = json.decodeFromString<ApiResponse<UploadStatusResponse>>(errorRaw)

        assertTrue(s.success)
        assertEquals(false, e.success)
    }
}
