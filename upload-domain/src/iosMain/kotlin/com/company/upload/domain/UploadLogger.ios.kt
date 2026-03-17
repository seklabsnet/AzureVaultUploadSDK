package com.company.upload.domain

actual object UploadLogger {
    actual fun d(msg: String) { println("[AzureVault] $msg") }
    actual fun i(msg: String) { println("[AzureVault] $msg") }
    actual fun w(msg: String) { println("[AzureVault] ⚠️ $msg") }
    actual fun e(msg: String) { println("[AzureVault] ❌ $msg") }
}
