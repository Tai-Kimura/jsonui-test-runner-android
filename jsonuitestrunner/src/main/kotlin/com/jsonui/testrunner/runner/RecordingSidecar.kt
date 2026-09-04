package com.jsonui.testrunner.runner

/**
 * `recording.json` written beside every kept per-case recording.
 *
 * A recording's duration and frame count answer different questions from
 * "does this cover the case?", and a consumer read both the wrong way
 * (2026-09-04): `screenrecord` emits a frame only when the display changes,
 * so a case that spends 10 s waiting on a static screen produces no frames
 * for those 10 s — a 2-frame / 0.03 s file is what a static wait looks like,
 * not a broken recorder — while a file whose `stop` never finalised (moov
 * atom missing) has neither a duration nor frames. The sidecar carries the
 * quantities the file itself cannot: the case's wall time, how long the
 * recorder took to actually start capturing, and whether `stop` finalised.
 */
object RecordingSidecar {
    const val FILE_NAME = "recording.json"

    const val TRUNCATED_FILE_NAME = "recording.truncated.mp4"

    private const val NOTE =
        "screenrecord emits a frame only when the display changes; " +
            "a static wait produces no frames, so duration and frame count do not measure case coverage"

    /**
     * The sidecar body. [fileName] is the recording as it sits on disk (the
     * truncated name when [finalized] is false), [startLatencyMs] is -1 when
     * the output file never appeared within the readiness budget.
     */
    fun json(fileName: String, caseDurationMs: Long, startLatencyMs: Long, finalized: Boolean): String =
        """
        {
          "file": "${escape(fileName)}",
          "caseDurationMs": $caseDurationMs,
          "recorderStartLatencyMs": $startLatencyMs,
          "finalized": $finalized,
          "note": "$NOTE"
        }
        """.trimIndent() + "\n"

    /** The name a recording is kept under: the truncated name when stop did not finalise it. */
    fun keptName(finalized: Boolean, originalName: String): String =
        if (finalized) originalName else TRUNCATED_FILE_NAME

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
