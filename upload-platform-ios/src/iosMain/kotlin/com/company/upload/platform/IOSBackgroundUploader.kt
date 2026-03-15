package com.company.upload.platform

import platform.Foundation.*

internal class IOSBackgroundUploader {
    private val sessionConfig: NSURLSessionConfiguration by lazy {
        NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier("com.company.upload.bg").apply {
            setDiscretionary(false)
            setSessionSendsLaunchEvents(true)
            setTimeoutIntervalForRequest(30.0)
            setTimeoutIntervalForResource(3600.0)
        }
    }

    private val session: NSURLSession by lazy {
        NSURLSession.sessionWithConfiguration(sessionConfig, delegate = null, delegateQueue = null)
    }

    fun startBackgroundUpload(uploadId: String, fileUrl: NSURL, destinationUrl: String, sasToken: String) {
        val request = NSMutableURLRequest(NSURL(string = "$destinationUrl?$sasToken")!!).apply {
            setHTTPMethod("PUT")
            setValue("BlockBlob", forHTTPHeaderField = "x-ms-blob-type")
            setValue("2023-11-03", forHTTPHeaderField = "x-ms-version")
        }
        val task = session.uploadTaskWithRequest(request, fromFile = fileUrl)
        task.setTaskDescription(uploadId)
        task.resume()
    }

    fun cancelUpload(uploadId: String) {
        session.getTasksWithCompletionHandler { _, uploadTasks, _ ->
            uploadTasks?.forEach { task ->
                val urlTask = task as? NSURLSessionTask
                if (urlTask?.taskDescription == uploadId) {
                    urlTask.cancel()
                }
            }
        }
    }
}
