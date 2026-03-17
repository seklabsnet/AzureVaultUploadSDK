package com.company.upload.domain

import platform.Foundation.NSLog

actual object UploadLogger {
    actual fun d(msg: String) { NSLog("[AzureVault] %@", msg) }
    actual fun i(msg: String) { NSLog("[AzureVault] %@", msg) }
    actual fun w(msg: String) { NSLog("[AzureVault] ⚠️ %@", msg) }
    actual fun e(msg: String) { NSLog("[AzureVault] ❌ %@", msg) }
}
