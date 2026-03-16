package com.company.upload.network

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
        val response: ApiResponse<InitiateUploadResponse> = client.post("$baseUrl/uploads/initiate") {
            applyHeaders()
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
        return unwrap(response)
    }

    suspend fun completeUpload(request: CompleteUploadRequest): CompleteUploadResponse {
        val response: ApiResponse<CompleteUploadResponse> = client.post("$baseUrl/uploads/${request.uploadId}/complete") {
            applyHeaders()
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
        return unwrap(response)
    }

    suspend fun getUploadStatus(uploadId: String): UploadStatusResponse {
        val response: ApiResponse<UploadStatusResponse> = client.get("$baseUrl/uploads/$uploadId/status") {
            applyHeaders()
        }.body()
        return unwrap(response)
    }

    suspend fun getDownloadUrl(fileId: String): DownloadUrlResponse {
        val response: ApiResponse<DownloadUrlResponse> = client.get("$baseUrl/uploads/$fileId/download-url") {
            applyHeaders()
        }.body()
        return unwrap(response)
    }

    suspend fun cancelUpload(uploadId: String) {
        val response: ApiResponse<Unit> = client.delete("$baseUrl/uploads/$uploadId") {
            applyHeaders()
        }.body()
        if (!response.success) {
            throw ApiException(response.error?.code ?: "UNKNOWN", response.error?.message ?: "Request failed")
        }
    }

    private fun <T> unwrap(response: ApiResponse<T>): T {
        if (!response.success || response.data == null) {
            throw ApiException(
                code = response.error?.code ?: "UNKNOWN",
                message = response.error?.message ?: "Request failed",
            )
        }
        return response.data
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun io.ktor.client.request.HttpRequestBuilder.applyHeaders() {
        header("Authorization", "Bearer ${authProvider()}")
        header("X-Correlation-Id", Uuid.random().toString())
    }
}

class ApiException(val code: String, override val message: String) : Exception(message)
