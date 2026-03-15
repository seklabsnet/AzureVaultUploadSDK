package com.company.upload.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.time.TimeSource

internal class BandwidthEstimator {
    private val mutex = Mutex()
    private val samples = mutableListOf<SpeedSample>()
    private val maxSamples = 20

    data class SpeedSample(
        val bytesTransferred: Long,
        val durationMs: Long,
        val timestampMs: Long,
    )

    suspend fun recordSample(bytesTransferred: Long, durationMs: Long) {
        if (durationMs <= 0) return
        mutex.withLock {
            samples.add(
                SpeedSample(
                    bytesTransferred = bytesTransferred,
                    durationMs = durationMs,
                    timestampMs = currentTimeMs(),
                )
            )
            if (samples.size > maxSamples) {
                samples.removeAt(0)
            }
        }
    }

    suspend fun estimateBytesPerSecond(): Long = mutex.withLock {
        if (samples.isEmpty()) return@withLock 0L

        // Weighted moving average — recent samples matter more
        var weightedBytes = 0.0
        var weightedTime = 0.0
        samples.forEachIndexed { index, sample ->
            val weight = (index + 1).toDouble()
            weightedBytes += sample.bytesTransferred * weight
            weightedTime += sample.durationMs * weight
        }

        if (weightedTime <= 0) return@withLock 0L
        (weightedBytes / weightedTime * 1000).toLong()
    }

    suspend fun estimateTimeRemainingMs(remainingBytes: Long): Long {
        val bps = estimateBytesPerSecond()
        if (bps <= 0) return -1L
        return max(0L, (remainingBytes.toDouble() / bps * 1000).toLong())
    }

    suspend fun reset() = mutex.withLock { samples.clear() }

    private val timeSource = TimeSource.Monotonic
    private val startMark = timeSource.markNow()
    private fun currentTimeMs(): Long = startMark.elapsedNow().inWholeMilliseconds
}
