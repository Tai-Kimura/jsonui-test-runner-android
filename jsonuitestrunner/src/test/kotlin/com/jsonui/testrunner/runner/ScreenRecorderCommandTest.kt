package com.jsonui.testrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class ScreenRecorderCommandTest {

    @Test
    fun stopSignalsOnlyTheScreenrecordThatWritesOurFile() {
        // The former `pkill -2 screenrecord` reached every screenrecord on the
        // device; on a shared emulator that was the other app's recording.
        val out = File("/sdcard/Android/data/com.example.app/files/jsonui-artifacts/Suite/case_1/recording.mp4")
        assertEquals("pkill -2 -f ${out.absolutePath}", ScreenRecorder.stopCommand(out))
    }

    @Test
    fun stopCommandNeverDegradesToTheBareProcessName() {
        val a = ScreenRecorder.stopCommand(File("/x/a/recording.mp4"))
        val b = ScreenRecorder.stopCommand(File("/x/b/recording.mp4"))
        assertFalse(a == b)
        assertFalse(a.endsWith(" screenrecord"))
    }
}
