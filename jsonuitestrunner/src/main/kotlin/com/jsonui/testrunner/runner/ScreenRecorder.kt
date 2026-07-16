package com.jsonui.testrunner.runner

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Drives the platform `screenrecord` binary through
 * [UiAutomation.executeShellCommand] (shell uid), so the .mp4 lands in a
 * location `adb pull` can reach without run-as (the app's external files dir
 * is shell-writable — measured on API 35: the dir is setgid `ext_data_rw`).
 *
 * Stop contract (measured on API 35, and encoded in AOSP screenrecord):
 * send SIGINT exactly ONCE, then wait for the command pfd to reach EOF.
 * screenrecord restores the default SIGINT disposition after the first
 * signal, so a second SIGINT kills it before the moov atom is written and
 * the file is unplayable. Never poll-and-resignal.
 */
internal class ScreenRecorder(private val automation: UiAutomation) {
    private var thread: Thread? = null
    private var output: File? = null

    val isRecording: Boolean get() = thread != null

    /** Start recording into [outputFile]; no-op when a recording is running. */
    fun start(outputFile: File, timeLimitSeconds: Int = 180) {
        if (thread != null) return
        // mkdir through the shell so the directory exists for the shell-uid
        // writer even when the app hasn't touched it yet.
        drain(automation.executeShellCommand("mkdir -p ${outputFile.parent}"))
        output = outputFile
        thread = Thread {
            // Draining until EOF == screenrecord exited (finalized or died).
            drain(
                automation.executeShellCommand(
                    "screenrecord --time-limit $timeLimitSeconds ${outputFile.absolutePath}"
                )
            )
        }.also { it.start() }
    }

    /**
     * Stop the recording gracefully.
     * @return the finalized file, or null when nothing was being recorded or
     *   screenrecord did not exit within [timeoutMs] (file likely truncated).
     */
    fun stop(timeoutMs: Long = 10_000): File? {
        val t = thread ?: return null
        drain(automation.executeShellCommand("pkill -2 screenrecord")) // exactly once
        t.join(timeoutMs)
        val finished = !t.isAlive
        thread = null
        val file = output
        output = null
        return if (finished) file else null
    }

    /** Delete a recording (shell-owned file → delete through the shell). */
    fun discard(file: File) {
        drain(automation.executeShellCommand("rm -f ${file.absolutePath}"))
    }

    private fun drain(pfd: ParcelFileDescriptor) {
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            val buf = ByteArray(8192)
            @Suppress("ControlFlowWithEmptyBody")
            while (input.read(buf) >= 0) {
                // drain to EOF
            }
        }
    }
}
