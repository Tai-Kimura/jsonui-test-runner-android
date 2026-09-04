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
 *
 * What a recording is NOT: coverage of the case's wall time. screenrecord
 * emits a frame only when the display changes, so a case that waits on a
 * static screen produces no frames for that wait — a 2-frame / 0.03 s file
 * is what a static wait looks like, not a broken recorder. The sidecar the
 * runner writes beside each kept recording ([RecordingSidecar]) carries the
 * quantities the file cannot: case wall time, [startLatencyMs], finalised.
 */
internal class ScreenRecorder(private val automation: UiAutomation) {
    private var thread: Thread? = null
    private var output: File? = null

    /**
     * Milliseconds from [start] until the output file existed on the device —
     * i.e. until screenrecord had its encoder and virtual display up and was
     * actually capturing. -1 when it never appeared within the readiness
     * budget. The case's first moments are not on the recording; this says
     * how many.
     */
    var startLatencyMs: Long = -1
        private set

    val isRecording: Boolean get() = thread != null

    /**
     * Start recording into [outputFile]; no-op when a recording is running.
     * Returns once screenrecord is actually capturing (bounded by
     * [readyTimeoutMs] — a recorder that never starts must not hold the case).
     */
    fun start(outputFile: File, timeLimitSeconds: Int = 180, readyTimeoutMs: Long = 2_000) {
        if (thread != null) return
        // mkdir through the shell so the directory exists for the shell-uid
        // writer even when the app hasn't touched it yet.
        drain(automation.executeShellCommand("mkdir -p ${outputFile.parent}"))
        // A leftover file from an earlier run would satisfy the readiness poll
        // below before this recording has written anything.
        drain(automation.executeShellCommand("rm -f ${outputFile.absolutePath}"))
        output = outputFile
        startLatencyMs = -1
        val startedAt = System.currentTimeMillis()
        thread = Thread {
            // Draining until EOF == screenrecord exited (finalized or died).
            drain(
                automation.executeShellCommand(
                    "screenrecord --time-limit $timeLimitSeconds ${outputFile.absolutePath}"
                )
            )
        }.also { it.start() }
        // screenrecord opens its output only after the encoder and the virtual
        // display are set up; until the file exists nothing is being captured
        // (the case's first several hundred ms were missing from recordings
        // when start returned immediately).
        while (System.currentTimeMillis() - startedAt < readyTimeoutMs) {
            if (exists(outputFile)) {
                startLatencyMs = System.currentTimeMillis() - startedAt
                break
            }
            Thread.sleep(50)
        }
    }

    /** What [stop] returns: the file, and whether screenrecord finalised it in time. */
    class Stopped(val file: File, val finalized: Boolean)

    /**
     * Stop the recording gracefully.
     * @return the file and whether it was finalised (false: screenrecord did
     *   not exit within [timeoutMs], the file is likely truncated and the
     *   caller should not keep it under a healthy-looking name), or null when
     *   nothing was being recorded.
     */
    fun stop(timeoutMs: Long = 10_000): Stopped? {
        val t = thread ?: return null
        val file = output ?: return null
        drain(automation.executeShellCommand(stopCommand(file))) // exactly once
        t.join(timeoutMs)
        val finished = !t.isAlive
        thread = null
        output = null
        return Stopped(file, finished)
    }

    /** Delete a recording (shell-owned file → delete through the shell). */
    fun discard(file: File) {
        drain(automation.executeShellCommand("rm -f ${file.absolutePath}"))
    }

    /** Rename a recording within its directory (shell-owned file → rename through the shell). */
    fun rename(file: File, newName: String): File {
        val target = File(file.parentFile, newName)
        drain(automation.executeShellCommand("mv ${file.absolutePath} ${target.absolutePath}"))
        return target
    }

    /** `ls <path>` prints the path on success and nothing on stdout otherwise. */
    private fun exists(file: File): Boolean =
        readAll(automation.executeShellCommand("ls ${file.absolutePath}")).trim() == file.absolutePath

    private fun readAll(pfd: ParcelFileDescriptor): String =
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun drain(pfd: ParcelFileDescriptor) {
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            val buf = ByteArray(8192)
            @Suppress("ControlFlowWithEmptyBody")
            while (input.read(buf) >= 0) {
                // drain to EOF
            }
        }
    }

    companion object {
        /**
         * The stop signal, scoped to OUR screenrecord by its command line:
         * `-f` matches the full command line, and the output path is unique
         * per app and case. The former `pkill -2 screenrecord` signalled every
         * screenrecord on the device — on a shared emulator that was the
         * other app's recording, which then finalised early (a short but
         * valid file) or, on a second signal, died before its moov atom was
         * written (unplayable). The second "no app dimension on a shared
         * device" defect of 2026-09-04, after the artifact mirror.
         */
        fun stopCommand(outputFile: File): String = "pkill -2 -f ${outputFile.absolutePath}"
    }
}
