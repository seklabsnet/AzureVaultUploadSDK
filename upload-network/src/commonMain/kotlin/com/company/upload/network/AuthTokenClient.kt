package com.company.upload.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TokenRequest(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
)

class AuthTokenClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
) {
    suspend fun getToken(clientId: String, clientSecret: String): TokenResponse {
        val response: ApiResponse<TokenResponse> = httpClient.post("$baseUrl/v1/auth/token") {
            contentType(ContentType.Application.Json)
            setBody(TokenRequest(clientId = clientId, clientSecret = clientSecret))
        }.body()

        if (!response.success || response.data == null) {
            throw ApiException(
                code = response.error?.code ?: "AUTH_FAILED",
                message = response.error?.message ?: "Authentication failed",
            )
        }
        return response.data
    }
}
