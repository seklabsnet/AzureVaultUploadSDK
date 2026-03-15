package com.company.upload.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UploadStateMachineTest {

    // ---- Valid transitions ----

    @Test
    fun idleToValidating_isValid() {
        val sm = UploadStateMachine()
        assertTrue(sm.transition(UploadStatus.VALIDATING))
        assertEquals(UploadStatus.VALIDATING, sm.status.value)
    }

    @Test
    fun validatingToRequestingToken_isValid() {
        val sm = UploadStateMachine(UploadStatus.VALIDATING)
        assertTrue(sm.transition(UploadStatus.REQUESTING_TOKEN))
        assertEquals(UploadStatus.REQUESTING_TOKEN, sm.status.value)
    }

    @Test
    fun validatingToFailed_isValid() {
        val sm = UploadStateMachine(UploadStatus.VALIDATING)
        assertTrue(sm.transition(UploadStatus.FAILED))
        assertEquals(UploadStatus.FAILED, sm.status.value)
    }

    @Test
    fun requestingTokenToUploading_isValid() {
        val sm = UploadStateMachine(UploadStatus.REQUESTING_TOKEN)
        assertTrue(sm.transition(UploadStatus.UPLOADING))
        assertEquals(UploadStatus.UPLOADING, sm.status.value)
    }

    @Test
    fun uploadingToCommitting_isValid() {
        val sm = UploadStateMachine(UploadStatus.UPLOADING)
        assertTrue(sm.transition(UploadStatus.COMMITTING))
        assertEquals(UploadStatus.COMMITTING, sm.status.value)
    }

    @Test
    fun uploadingToPaused_isValid() {
        val sm = UploadStateMachine(UploadStatus.UPLOADING)
        assertTrue(sm.transition(UploadStatus.PAUSED))
        assertEquals(UploadStatus.PAUSED, sm.status.value)
    }

    @Test
    fun uploadingToFailed_isValid() {
        val sm = UploadStateMachine(UploadStatus.UPLOADING)
        assertTrue(sm.transition(UploadStatus.FAILED))
        assertEquals(UploadStatus.FAILED, sm.status.value)
    }

    @Test
    fun uploadingToCancelled_isValid() {
        val sm = UploadStateMachine(UploadStatus.UPLOADING)
        assertTrue(sm.transition(UploadStatus.CANCELLED))
        assertEquals(UploadStatus.CANCELLED, sm.status.value)
    }

    @Test
    fun pausedToUploading_isValid() {
        val sm = UploadStateMachine(UploadStatus.PAUSED)
        assertTrue(sm.transition(UploadStatus.UPLOADING))
        assertEquals(UploadStatus.UPLOADING, sm.status.value)
    }

    @Test
    fun pausedToCancelled_isValid() {
        val sm = UploadStateMachine(UploadStatus.PAUSED)
        assertTrue(sm.transition(UploadStatus.CANCELLED))
        assertEquals(UploadStatus.CANCELLED, sm.status.value)
    }

    @Test
    fun committingToProcessing_isValid() {
        val sm = UploadStateMachine(UploadStatus.COMMITTING)
        assertTrue(sm.transition(UploadStatus.PROCESSING))
        assertEquals(UploadStatus.PROCESSING, sm.status.value)
    }

    @Test
    fun committingToCompleted_isValid() {
        val sm = UploadStateMachine(UploadStatus.COMMITTING)
        assertTrue(sm.transition(UploadStatus.COMPLETED))
        assertEquals(UploadStatus.COMPLETED, sm.status.value)
    }

    @Test
    fun processingToCompleted_isValid() {
        val sm = UploadStateMachine(UploadStatus.PROCESSING)
        assertTrue(sm.transition(UploadStatus.COMPLETED))
        assertEquals(UploadStatus.COMPLETED, sm.status.value)
    }

    @Test
    fun failedToUploading_isValid_manualRetry() {
        val sm = UploadStateMachine(UploadStatus.FAILED)
        assertTrue(sm.transition(UploadStatus.UPLOADING))
        assertEquals(UploadStatus.UPLOADING, sm.status.value)
    }

    // ---- Invalid transitions ----

    @Test
    fun idleToUploading_isInvalid() {
        val sm = UploadStateMachine()
        assertFalse(sm.transition(UploadStatus.UPLOADING))
        assertEquals(UploadStatus.IDLE, sm.status.value)
    }

    @Test
    fun idleToCompleted_isInvalid() {
        val sm = UploadStateMachine()
        assertFalse(sm.transition(UploadStatus.COMPLETED))
        assertEquals(UploadStatus.IDLE, sm.status.value)
    }

    @Test
    fun completedToUploading_isInvalid_terminalState() {
        val sm = UploadStateMachine(UploadStatus.COMPLETED)
        assertFalse(sm.transition(UploadStatus.UPLOADING))
        assertEquals(UploadStatus.COMPLETED, sm.status.value)
    }

    @Test
    fun cancelledToUploading_isInvalid_terminalState() {
        val sm = UploadStateMachine(UploadStatus.CANCELLED)
        assertFalse(sm.transition(UploadStatus.UPLOADING))
        assertEquals(UploadStatus.CANCELLED, sm.status.value)
    }

    @Test
    fun validatingToUploading_isInvalid_mustGoThroughRequestingToken() {
        val sm = UploadStateMachine(UploadStatus.VALIDATING)
        assertFalse(sm.transition(UploadStatus.UPLOADING))
        assertEquals(UploadStatus.VALIDATING, sm.status.value)
    }

    // ---- StateFlow reflects current state ----

    @Test
    fun statusFlowReflectsCurrentState() {
        val sm = UploadStateMachine()
        assertEquals(UploadStatus.IDLE, sm.status.value)

        sm.transition(UploadStatus.VALIDATING)
        assertEquals(UploadStatus.VALIDATING, sm.status.value)

        sm.transition(UploadStatus.REQUESTING_TOKEN)
        assertEquals(UploadStatus.REQUESTING_TOKEN, sm.status.value)

        sm.transition(UploadStatus.UPLOADING)
        assertEquals(UploadStatus.UPLOADING, sm.status.value)
    }

    @Test
    fun invalidTransitionDoesNotChangeState() {
        val sm = UploadStateMachine()
        assertFalse(sm.transition(UploadStatus.COMPLETED))
        assertEquals(UploadStatus.IDLE, sm.status.value)
        assertFalse(sm.transition(UploadStatus.UPLOADING))
        assertEquals(UploadStatus.IDLE, sm.status.value)
    }
}
