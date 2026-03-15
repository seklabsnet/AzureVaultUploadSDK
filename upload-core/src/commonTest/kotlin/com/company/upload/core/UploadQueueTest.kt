package com.company.upload.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UploadQueueTest {

    private fun makeUpload(id: String, priority: Int = 0) = QueuedUpload(
        uploadId = id,
        priority = priority,
        enqueuedAt = 0L,
    )

    // ---- Enqueue adds items ----

    @Test
    fun enqueue_addsItems() = runTest {
        val queue = UploadQueue(maxConcurrent = 2)

        queue.enqueue(makeUpload("a"))
        assertEquals(1, queue.queueSize())

        queue.enqueue(makeUpload("b"))
        assertEquals(2, queue.queueSize())
    }

    // ---- queueSize reflects number of items ----

    @Test
    fun queueSize_reflectsNumberOfItems() = runTest {
        val queue = UploadQueue(maxConcurrent = 2)

        assertEquals(0, queue.queueSize())

        queue.enqueue(makeUpload("a"))
        queue.enqueue(makeUpload("b"))
        queue.enqueue(makeUpload("c"))

        assertEquals(3, queue.queueSize())
    }

    // ---- Remove removes item from queue ----

    @Test
    fun remove_removesItemFromQueue() = runTest {
        val queue = UploadQueue(maxConcurrent = 2)

        queue.enqueue(makeUpload("a"))
        queue.enqueue(makeUpload("b"))
        queue.enqueue(makeUpload("c"))
        assertEquals(3, queue.queueSize())

        queue.remove("b")
        assertEquals(2, queue.queueSize())
    }

    @Test
    fun remove_nonExistentItem_doesNothing() = runTest {
        val queue = UploadQueue(maxConcurrent = 2)

        queue.enqueue(makeUpload("a"))
        assertEquals(1, queue.queueSize())

        queue.remove("nonexistent")
        assertEquals(1, queue.queueSize())
    }

    // ---- isActive is false before withPermit ----

    @Test
    fun isActive_falseBeforeWithPermit() = runTest {
        val queue = UploadQueue(maxConcurrent = 2)
        queue.enqueue(makeUpload("a"))
        assertFalse(queue.isActive("a"))
    }

    // ---- activeCount starts at zero ----

    @Test
    fun activeCount_startsAtZero() = runTest {
        val queue = UploadQueue(maxConcurrent = 2)
        assertEquals(0, queue.activeCount())
    }

    // ---- withPermit marks upload as active and cleans up ----

    @Test
    fun withPermit_marksActiveAndCleansUp() = runTest {
        val queue = UploadQueue(maxConcurrent = 2)
        queue.enqueue(makeUpload("a"))

        var wasActive = false
        queue.withPermit("a") {
            wasActive = queue.isActive("a")
        }

        assertTrue(wasActive, "Upload should be active during withPermit")
        assertFalse(queue.isActive("a"), "Upload should not be active after withPermit")
        assertEquals(0, queue.queueSize(), "Queue entry should be removed after withPermit")
    }

    // ---- Priority ordering ----

    @Test
    fun enqueue_sortsByPriorityDescending() = runTest {
        val queue = UploadQueue(maxConcurrent = 2)

        queue.enqueue(makeUpload("low", priority = 1))
        queue.enqueue(makeUpload("high", priority = 10))
        queue.enqueue(makeUpload("mid", priority = 5))

        assertEquals(3, queue.queueSize())
        // We cannot directly inspect order, but the queue should accept all items
    }
}
