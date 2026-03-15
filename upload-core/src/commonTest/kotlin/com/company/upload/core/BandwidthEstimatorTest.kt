package com.company.upload.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BandwidthEstimatorTest {

    // ---- Empty estimator returns 0 bps ----

    @Test
    fun emptyEstimator_returnsZeroBps() = runTest {
        val estimator = BandwidthEstimator()
        assertEquals(0L, estimator.estimateBytesPerSecond())
    }

    // ---- Single sample gives correct speed ----

    @Test
    fun singleSample_givesCorrectSpeed() = runTest {
        val estimator = BandwidthEstimator()

        // 1,000,000 bytes in 1000 ms = 1,000,000 bytes/sec
        estimator.recordSample(bytesTransferred = 1_000_000L, durationMs = 1000L)

        val bps = estimator.estimateBytesPerSecond()
        assertEquals(1_000_000L, bps)
    }

    // ---- Multiple samples with weighted average ----

    @Test
    fun multipleSamples_weightedAverage() = runTest {
        val estimator = BandwidthEstimator()

        // Sample 0: 1,000,000 bytes in 1000 ms -> 1,000,000 B/s (weight 1)
        estimator.recordSample(bytesTransferred = 1_000_000L, durationMs = 1000L)
        // Sample 1: 2,000,000 bytes in 1000 ms -> 2,000,000 B/s (weight 2)
        estimator.recordSample(bytesTransferred = 2_000_000L, durationMs = 1000L)

        // Weighted: (1_000_000 * 1 + 2_000_000 * 2) / (1000 * 1 + 1000 * 2) * 1000
        //         = (1_000_000 + 4_000_000) / (1000 + 2000) * 1000
        //         = 5_000_000 / 3000 * 1000
        //         = 1_666_666 (approx)
        val bps = estimator.estimateBytesPerSecond()
        assertTrue(bps in 1_666_000L..1_667_000L, "Expected ~1,666,666 but got $bps")
    }

    // ---- estimateTimeRemainingMs with known speed ----

    @Test
    fun estimateTimeRemainingMs_withKnownSpeed() = runTest {
        val estimator = BandwidthEstimator()

        // 1,000,000 bytes in 1000 ms = 1,000,000 bytes/sec
        estimator.recordSample(bytesTransferred = 1_000_000L, durationMs = 1000L)

        // 5,000,000 bytes remaining at 1,000,000 B/s = 5000 ms
        val remaining = estimator.estimateTimeRemainingMs(remainingBytes = 5_000_000L)
        assertEquals(5000L, remaining)
    }

    @Test
    fun estimateTimeRemainingMs_noSamples_returnsNegativeOne() = runTest {
        val estimator = BandwidthEstimator()
        val remaining = estimator.estimateTimeRemainingMs(remainingBytes = 1_000_000L)
        assertEquals(-1L, remaining)
    }

    // ---- Reset clears all samples ----

    @Test
    fun reset_clearsAllSamples() = runTest {
        val estimator = BandwidthEstimator()
        estimator.recordSample(bytesTransferred = 1_000_000L, durationMs = 1000L)
        assertEquals(1_000_000L, estimator.estimateBytesPerSecond())

        estimator.reset()

        assertEquals(0L, estimator.estimateBytesPerSecond())
    }

    // ---- Zero-duration sample is ignored ----

    @Test
    fun zeroDurationSample_isIgnored() = runTest {
        val estimator = BandwidthEstimator()
        estimator.recordSample(bytesTransferred = 1_000_000L, durationMs = 0L)
        assertEquals(0L, estimator.estimateBytesPerSecond())
    }

    // ---- Negative-duration sample is ignored ----

    @Test
    fun negativeDurationSample_isIgnored() = runTest {
        val estimator = BandwidthEstimator()
        estimator.recordSample(bytesTransferred = 1_000_000L, durationMs = -100L)
        assertEquals(0L, estimator.estimateBytesPerSecond())
    }
}
