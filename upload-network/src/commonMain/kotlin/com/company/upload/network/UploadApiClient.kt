package com.company.upload.network

import com.company.upload.domain.UploadLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class UploadApiClient(
    private val baseUrl: String,
    private val authProvider: suspend () -> String,
    httpClient: HttpClient? = null,
) {
    private val client: HttpClient = httpClient ?: HttpClient(createHttpEngine()) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    isLenient = true
                }
            )
        }
    }

    suspend fun initiateUpload(request: InitiateUploadRequest): InitiateUploadResponse {
        UploadLogger.d("🌐 API → POST /uploads/initiate")
        UploadLogger.d("   Body: file=${request.fileName} size=${request.fileSize} mime=${request.mimeType} entity=${request.entityType}/${request.entityId}")
        val response: ApiResponse<InitiateUploadResponse> = client.post("$baseUrl/uploads/initiate") {
            applyHeaders()
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
        val data = unwrap(response)
        UploadLogger.d("🌐 API ← 200 OK — uploadId=${data.uploadId}")
        return data
    }

    suspend fun completeUpload(request: CompleteUploadRequest): CompleteUploadResponse {
        UploadLogger.d("🌐 API → POST /uploads/${request.uploadId}/complete")
        UploadLogger.d("   BlockIds: ${request.blockIds.size} blocks")
        val response: ApiResponse<CompleteUploadResponse> = client.post("$baseUrl/uploads/${request.uploadId}/complete") {
            applyHeaders()
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
        val data = unwrap(response)
        UploadLogger.d("🌐 API ← 200 OK — fileId=${data.fileId}")
        return data
    }

    suspend fun getUploadStatus(uploadId: String): UploadStatusResponse {
        UploadLogger.d("🌐 API → GET /uploads/$uploadId/status")
        val response: ApiResponse<UploadStatusResponse> = client.get("$baseUrl/uploads/$uploadId/status") {
            applyHeaders()
        }.body()
        return unwrap(response)
    }

    suspend fun getDownloadUrl(fileId: String): DownloadUrlResponse {
        UploadLogger.d("🌐 API → GET /uploads/$fileId/download-url")
        val response: ApiResponse<DownloadUrlResponse> = client.get("$baseUrl/uploads/$fileId/download-url") {
            applyHeaders()
        }.body()
        return unwrap(response)
    }

    suspend fun cancelUpload(uploadId: String) {
        UploadLogger.d("🌐 API → DELETE /uploads/$uploadId")
        val response: ApiResponse<Unit> = client.delete("$baseUrl/uploads/$uploadId") {
            applyHeaders()
        }.body()
        if (!response.success) {
            throw ApiException(response.error?.code ?: "UNKNOWN", response.error?.message ?: "Request failed")
        }
    }

    private fun <T> unwrap(response: ApiResponse<T>): T {
        if (!response.success || response.data == null) {
            val errMsg = response.error?.message ?: "Request failed"
            UploadLogger.e("🌐 API ← ERROR: ${response.error?.code} — $errMsg")
            throw ApiException(
                code = response.error?.code ?: "UNKNOWN",
                message = errMsg,
            )
        }
        return response.data
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun io.ktor.client.request.HttpRequestBuilder.applyHeaders() {
        val correlationId = Uuid.random().toString()
        header("Authorization", "Bearer ${authProvider()}")
        header("X-Correlation-Id", correlationId)
    }
}

class ApiException(val code: String, override val message: String) : Exception(message)
