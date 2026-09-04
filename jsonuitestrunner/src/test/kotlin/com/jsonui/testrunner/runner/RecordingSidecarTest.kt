package com.jsonui.testrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSidecarTest {

    @Test
    fun sidecarCarriesTheQuantitiesTheFileCannot() {
        val json = RecordingSidecar.json("recording.mp4", caseDurationMs = 27_431, startLatencyMs = 612, finalized = true)
        assertTrue(json, json.contains("\"file\": \"recording.mp4\""))
        assertTrue(json, json.contains("\"caseDurationMs\": 27431"))
        assertTrue(json, json.contains("\"recorderStartLatencyMs\": 612"))
        assertTrue(json, json.contains("\"finalized\": true"))
        // the note is the one sentence a reader needs before trusting duration or frames
        assertTrue(json, json.contains("emits a frame only when the display changes"))
    }

    @Test
    fun unfinalizedRecordingIsKeptUnderTheTruncatedName() {
        // The consumer's 3,232-byte file with no moov atom sat there with the
        // same face as a good recording; the name is what says otherwise.
        assertEquals("recording.truncated.mp4", RecordingSidecar.keptName(finalized = false, originalName = "recording.mp4"))
        assertEquals("recording.mp4", RecordingSidecar.keptName(finalized = true, originalName = "recording.mp4"))
        val json = RecordingSidecar.json("recording.truncated.mp4", 12_000, -1, finalized = false)
        assertTrue(json, json.contains("\"finalized\": false"))
        assertTrue(json, json.contains("\"recorderStartLatencyMs\": -1"))
    }

    @Test
    fun fileNameIsEscapedForJson() {
        val json = RecordingSidecar.json("odd\"name.mp4", 1, 1, true)
        assertTrue(json, json.contains("\"file\": \"odd\\\"name.mp4\""))
    }
}
