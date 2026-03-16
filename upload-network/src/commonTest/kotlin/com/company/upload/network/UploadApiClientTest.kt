package com.company.upload.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UploadApiClientTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun mockClient(content: String, status: HttpStatusCode = HttpStatusCode.OK): UploadApiClient {
        val engine = MockEngine {
            respond(content = content, status = status, headers = jsonHeaders)
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true })
            }
        }
        return UploadApiClient(baseUrl = "https://api.test.com/v1", authProvider = { "test-token" }, httpClient = http)
    }

    private fun request(): InitiateUploadRequest =
        InitiateUploadRequest("test.jpg", 1024, "image/jpeg", entityType = "profile", entityId = "u1")

    // ── initiateUpload ──

    @Test
    fun initiateUpload_success() = runTest {
        val client = mockClient("""
            {"success":true,"data":{"uploadId":"abc-123","blobUrl":"https://blob/test.jpg","sasToken":"sv=2024&sig=xyz","strategy":"SINGLE_SHOT","maxBlockSize":1024,"expiresAt":"2026-03-16T12:00:00Z"}}
        """.trimIndent())

        val r = client.initiateUpload(request())
        assertEquals("abc-123", r.uploadId)
        assertEquals("SINGLE_SHOT", r.strategy)
        assertEquals(1024L, r.maxBlockSize)
    }

    @Test
    fun initiateUpload_chunked() = runTest {
        val client = mockClient("""
            {"success":true,"data":{"uploadId":"c-456","blobUrl":"https://blob/big.zip","sasToken":"sig=abc","strategy":"CHUNKED","maxBlockSize":4194304,"expiresAt":"2026-03-16T12:00:00Z","chunkCount":25}}
        """.trimIndent())

        val r = client.initiateUpload(request())
        assertEquals("CHUNKED", r.strategy)
        assertEquals(25, r.chunkCount)
    }

    @Test
    fun initiateUpload_notRegistered_throws() = runTest {
        val client = mockClient(
            """{"success":false,"error":{"code":"NOT_FOUND","message":"App not registered"}}""",
            HttpStatusCode.NotFound,
        )
        val ex = assertFailsWith<ApiException> { client.initiateUpload(request()) }
        assertEquals("NOT_FOUND", ex.code)
    }

    // ── completeUpload ──

    @Test
    fun completeUpload_success() = runTest {
        val client = mockClient("""
            {"success":true,"data":{"fileId":"f-001","downloadUrl":"https://blob/test.jpg?sas","metadata":{"fileName":"test.jpg","fileSize":26},"processingStatus":"PENDING","blurHash":null}}
        """.trimIndent())

        val r = client.completeUpload(CompleteUploadRequest("up-789", emptyList()))
        assertEquals("f-001", r.fileId)
        assertEquals("PENDING", r.processingStatus)
    }

    // ── getUploadStatus ──

    @Test
    fun getUploadStatus_completed() = runTest {
        val client = mockClient("""
            {"success":true,"data":{"uploadId":"up-789","status":"COMPLETED","progress":100,"fileId":"f-001","downloadUrl":"https://x","blurHash":"LGF5]+"}}
        """.trimIndent())

        val r = client.getUploadStatus("up-789")
        assertEquals("COMPLETED", r.status)
        assertEquals(100f, r.progress)
        assertNotNull(r.fileId)
    }

    @Test
    fun getUploadStatus_uploading() = runTest {
        val client = mockClient("""
            {"success":true,"data":{"uploadId":"up-789","status":"UPLOADING","progress":45}}
        """.trimIndent())

        val r = client.getUploadStatus("up-789")
        assertEquals("UPLOADING", r.status)
        assertEquals(45f, r.progress)
    }

    // ── getDownloadUrl ──

    @Test
    fun getDownloadUrl_success() = runTest {
        val client = mockClient("""
            {"success":true,"data":{"downloadUrl":"https://blob/test.jpg?sas=fresh","expiresAt":"2026-03-16T13:00:00Z","contentType":"image/jpeg","fileSize":1024}}
        """.trimIndent())

        val r = client.getDownloadUrl("f-001")
        assertTrue(r.downloadUrl.contains("sas=fresh"))
        assertEquals("image/jpeg", r.contentType)
    }

    @Test
    fun getDownloadUrl_public_noExpiry() = runTest {
        val client = mockClient("""
            {"success":true,"data":{"downloadUrl":"https://cdn/f-001","contentType":"image/png","fileSize":50000}}
        """.trimIndent())

        val r = client.getDownloadUrl("f-001")
        assertEquals(null, r.expiresAt)
    }

    // ── cancelUpload ──

    @Test
    fun cancelUpload_success() = runTest {
        val client = mockClient("""{"success":true,"data":null}""")
        client.cancelUpload("up-789") // should not throw
    }

    @Test
    fun cancelUpload_notFound_throws() = runTest {
        val client = mockClient(
            """{"success":false,"error":{"code":"NOT_FOUND","message":"Upload not found"}}""",
            HttpStatusCode.NotFound,
        )
        val ex = assertFailsWith<ApiException> { client.cancelUpload("none") }
        assertEquals("NOT_FOUND", ex.code)
    }

    // ── Error handling ──

    @Test
    fun serverError_throwsApiException() = runTest {
        val client = mockClient(
            """{"success":false,"error":{"code":"INTERNAL_ERROR","message":"Boom"}}""",
            HttpStatusCode.InternalServerError,
        )
        val ex = assertFailsWith<ApiException> { client.initiateUpload(request()) }
        assertEquals("INTERNAL_ERROR", ex.code)
    }

    @Test
    fun authError_throwsApiException() = runTest {
        val client = mockClient(
            """{"success":false,"error":{"code":"AUTH_ERROR","message":"Token expired"}}""",
            HttpStatusCode.Unauthorized,
        )
        val ex = assertFailsWith<ApiException> { client.initiateUpload(request()) }
        assertEquals("AUTH_ERROR", ex.code)
        assertTrue(ex.message.contains("expired"))
    }

    @Test
    fun rateLimitError_throwsApiException() = runTest {
        val client = mockClient(
            """{"success":false,"error":{"code":"RATE_LIMITED","message":"Retry after 60s"}}""",
            HttpStatusCode.TooManyRequests,
        )
        val ex = assertFailsWith<ApiException> { client.initiateUpload(request()) }
        assertEquals("RATE_LIMITED", ex.code)
    }
}
