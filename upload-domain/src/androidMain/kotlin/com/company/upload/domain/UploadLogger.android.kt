package com.company.upload.domain

import android.util.Log

actual object UploadLogger {
    private const val TAG = "AzureVault"

    actual fun d(msg: String) { Log.d(TAG, msg) }
    actual fun i(msg: String) { Log.i(TAG, msg) }
    actual fun w(msg: String) { Log.w(TAG, msg) }
    actual fun e(msg: String) { Log.e(TAG, msg) }
}
