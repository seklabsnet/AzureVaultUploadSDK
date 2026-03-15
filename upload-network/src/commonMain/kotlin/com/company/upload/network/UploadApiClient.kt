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
        return client.post("$baseUrl/uploads/initiate") {
            applyHeaders()
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    suspend fun completeUpload(request: CompleteUploadRequest): CompleteUploadResponse {
        return client.post("$baseUrl/uploads/complete") {
            applyHeaders()
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    suspend fun getUploadStatus(uploadId: String): UploadStatusResponse {
        return client.get("$baseUrl/uploads/$uploadId/status") {
            applyHeaders()
        }.body()
    }

    suspend fun getDownloadUrl(fileId: String): DownloadUrlResponse {
        return client.get("$baseUrl/files/$fileId/download-url") {
            applyHeaders()
        }.body()
    }

    suspend fun cancelUpload(uploadId: String) {
        client.delete("$baseUrl/uploads/$uploadId") {
            applyHeaders()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun io.ktor.client.request.HttpRequestBuilder.applyHeaders() {
        header("Authorization", "Bearer ${authProvider()}")
        header("X-Correlation-Id", Uuid.random().toString())
    }
}
