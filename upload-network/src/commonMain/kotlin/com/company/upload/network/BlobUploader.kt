package com.company.upload.network

import com.company.upload.domain.UploadLogger
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

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${kb.toInt()} KB"
        val mb = kb / 1024.0
        return "${(mb * 10).toInt() / 10.0} MB"
    }

    suspend fun uploadSingleShot(
        blobUrl: String,
        sasToken: String,
        data: ByteArray,
        contentType: String?,
    ) {
        UploadLogger.d("☁️ BLOB → PUT single-shot (${formatSize(data.size.toLong())})")
        httpClient.put(buildUrl(blobUrl, sasToken)) {
            header("x-ms-blob-type", "BlockBlob")
            header("x-ms-version", AZURE_API_VERSION)
            contentType?.let { header("Content-Type", it) }
            setBody(data)
        }
        UploadLogger.d("☁️ BLOB ← single-shot OK")
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun uploadBlock(
        blobUrl: String,
        sasToken: String,
        blockId: String,
        data: ByteArray,
    ) {
        // blockId is already Base64-encoded from ChunkManager — use directly
        UploadLogger.d("☁️ BLOB → PUT block id=$blockId (${formatSize(data.size.toLong())})")
        httpClient.put(buildUrl(blobUrl, sasToken)) {
            parameter("comp", "block")
            parameter("blockid", blockId)
            header("x-ms-version", AZURE_API_VERSION)
            contentType(ContentType.Application.OctetStream)
            setBody(data)
        }
        UploadLogger.d("☁️ BLOB ← block OK id=$blockId")
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun commitBlockList(
        blobUrl: String,
        sasToken: String,
        blockIds: List<String>,
    ) {
        UploadLogger.d("☁️ BLOB → PUT blocklist (${blockIds.size} blocks)")
        val xmlBody = buildBlockListXml(blockIds)
        httpClient.put(buildUrl(blobUrl, sasToken)) {
            parameter("comp", "blocklist")
            header("x-ms-version", AZURE_API_VERSION)
            contentType(ContentType.Application.Xml)
            setBody(xmlBody)
        }
        UploadLogger.d("☁️ BLOB ← blocklist committed OK")
    }

    private fun buildUrl(blobUrl: String, sasToken: String): String {
        val separator = if ('?' in blobUrl) '&' else '?'
        return "$blobUrl${separator}$sasToken"
    }

    private fun buildBlockListXml(blockIds: List<String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<BlockList>")
        for (id in blockIds) {
            // blockIds are already Base64-encoded from ChunkManager — use directly
            append("<Latest>$id</Latest>")
        }
        append("</BlockList>")
    }
}
