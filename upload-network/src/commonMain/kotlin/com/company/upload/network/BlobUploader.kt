package com.company.upload.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class BlobUploader(
    private val httpClient: HttpClient,
) {
    companion object {
        private const val AZURE_API_VERSION = "2023-11-03"
    }

    /**
     * Uploads the entire file in a single PUT request (for small files).
     */
    suspend fun uploadSingleShot(
        blobUrl: String,
        sasToken: String,
        data: ByteArray,
        contentType: String?,
    ) {
        httpClient.put(buildUrl(blobUrl, sasToken)) {
            header("x-ms-blob-type", "BlockBlob")
            header("x-ms-version", AZURE_API_VERSION)
            contentType?.let { header("Content-Type", it) }
            setBody(data)
        }
    }

    /**
     * Uploads a single block as part of a chunked upload.
     * The [blockId] is Base64-encoded before being sent as a query parameter.
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun uploadBlock(
        blobUrl: String,
        sasToken: String,
        blockId: String,
        data: ByteArray,
    ) {
        val encodedBlockId = Base64.encode(blockId.encodeToByteArray())
        httpClient.put(buildUrl(blobUrl, sasToken)) {
            parameter("comp", "block")
            parameter("blockid", encodedBlockId)
            header("x-ms-version", AZURE_API_VERSION)
            contentType(ContentType.Application.OctetStream)
            setBody(data)
        }
    }

    /**
     * Commits a list of previously uploaded blocks, finalizing the blob.
     * Sends an XML body containing the ordered block ID list.
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun commitBlockList(
        blobUrl: String,
        sasToken: String,
        blockIds: List<String>,
    ) {
        val xmlBody = buildBlockListXml(blockIds)
        httpClient.put(buildUrl(blobUrl, sasToken)) {
            parameter("comp", "blocklist")
            header("x-ms-version", AZURE_API_VERSION)
            contentType(ContentType.Application.Xml)
            setBody(xmlBody)
        }
    }

    private fun buildUrl(blobUrl: String, sasToken: String): String {
        val separator = if ('?' in blobUrl) '&' else '?'
        return "$blobUrl${separator}$sasToken"
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun buildBlockListXml(blockIds: List<String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<BlockList>")
        for (id in blockIds) {
            val encoded = Base64.encode(id.encodeToByteArray())
            append("<Latest>$encoded</Latest>")
        }
        append("</BlockList>")
    }
}
