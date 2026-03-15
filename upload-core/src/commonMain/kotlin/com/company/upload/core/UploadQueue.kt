package com.company.upload.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

internal data class QueuedUpload(
    val uploadId: String,
    val priority: Int = 0, // higher = more priority
    val enqueuedAt: Long,
)

internal class UploadQueue(maxConcurrent: Int) {

    private val semaphore = Semaphore(maxConcurrent)
    private val mutex = Mutex()
    private val queue = mutableListOf<QueuedUpload>()
    private val activeUploads = mutableSetOf<String>()

    suspend fun enqueue(upload: QueuedUpload) {
        mutex.withLock {
            queue.add(upload)
            queue.sortByDescending { it.priority }
        }
    }

    suspend fun <T> withPermit(uploadId: String, block: suspend () -> T): T {
        mutex.withLock { activeUploads.add(uploadId) }
        semaphore.acquire()
        return try {
            block()
        } finally {
            semaphore.release()
            mutex.withLock {
                activeUploads.remove(uploadId)
                queue.removeAll { it.uploadId == uploadId }
            }
        }
    }

    suspend fun remove(uploadId: String) {
        mutex.withLock {
            queue.removeAll { it.uploadId == uploadId }
        }
    }

    suspend fun isActive(uploadId: String): Boolean = mutex.withLock {
        uploadId in activeUploads
    }

    suspend fun queueSize(): Int = mutex.withLock { queue.size }
    suspend fun activeCount(): Int = mutex.withLock { activeUploads.size }
}
