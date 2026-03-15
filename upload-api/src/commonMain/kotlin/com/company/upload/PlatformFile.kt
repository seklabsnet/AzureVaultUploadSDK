package com.company.upload

expect class PlatformFile {
    val name: String
    val size: Long
    val mimeType: String?
}
