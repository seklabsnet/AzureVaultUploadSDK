package com.company.upload.domain

expect object UploadLogger {
    fun d(msg: String)
    fun i(msg: String)
    fun w(msg: String)
    fun e(msg: String)
}

object UploadLog {
    fun section(title: String) {
        UploadLogger.i("┌─────────────────────────────────────────────────────")
        UploadLogger.i("│ $title")
        UploadLogger.i("└─────────────────────────────────────────────────────")
    }

    fun block(title: String, vararg lines: String) {
        UploadLogger.i("┌─────────────────────────────────────────────────────")
        UploadLogger.i("│ $title")
        lines.forEach { UploadLogger.i("│   $it") }
        UploadLogger.i("└─────────────────────────────────────────────────────")
    }
}
