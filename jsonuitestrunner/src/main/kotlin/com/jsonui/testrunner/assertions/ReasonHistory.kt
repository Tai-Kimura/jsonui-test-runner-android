package com.jsonui.testrunner.assertions

/**
 * The reasons an auto-waiting assertion gave while it waited, in order.
 *
 * pollUntil used to rethrow only the last failure. When the reason changed
 * while waiting — exists but drawn 0x0 for two seconds, then not found once
 * the app removed it — the failure named only the second, and the reader
 * went looking for an element that had been there the whole time.
 *
 * The block is the same on the web and iOS drivers; the first line (the
 * last failure, as before) is each driver's own.
 */
class ReasonHistory {
    /** [reason] is the first line of the failure: what consecutive looks fold on. */
    data class Segment(val reason: String, val startMs: Long)

    private val segments = mutableListOf<Segment>()

    val all: List<Segment> get() = segments

    /** Record one failed look. Folds into the current segment when the reason
     * repeats the previous look's; A → B → A stays three segments. */
    fun observe(reason: String, atMs: Long) {
        val key = reason.substringBefore('\n')
        if (segments.lastOrNull()?.reason == key) return
        segments += Segment(key, atMs)
    }

    /**
     * What pollUntil throws at timeout. One reason: [last] itself, the same
     * instance, so the message and type are exactly what they were. More:
     * an AssertionError whose message is [last]'s, followed by the block,
     * and whose cause is [last] — the failure that actually ended the wait
     * is still the one in the trace.
     */
    fun failure(last: Throwable, endMs: Long): Throwable =
        if (segments.size < 2) last
        else AssertionError("${headOf(last)}\n${renderBlock(segments, endMs)}", last)

    companion object {
        /** Above this many segments the middle is elided. */
        const val MAX_SEGMENTS = 8
        private const val HEAD = 3
        private const val TAIL = 4

        /** The reason a failed look gives. A transient type that is not an
         * assertion is named, so it reads as what it was. */
        fun reasonOf(t: Throwable): String =
            if (t is AssertionError) t.message ?: "assertion failed"
            else t.message?.let { "${t.javaClass.simpleName}: $it" } ?: t.javaClass.simpleName

        private fun headOf(t: Throwable): String = reasonOf(t)

        fun renderBlock(segments: List<Segment>, endMs: Long): String {
            val rows = segments.mapIndexed { i, s ->
                val end = segments.getOrNull(i + 1)?.startMs ?: endMs
                "    ${seconds(s.startMs)}–${seconds(end)}s  ${withoutSubject(s.reason)}"
            }
            val shown = if (rows.size > MAX_SEGMENTS) {
                rows.take(HEAD) + "    … ${rows.size - HEAD - TAIL} more" + rows.takeLast(TAIL)
            } else rows
            return (listOf("  waited ${seconds(endMs)}s; the reason changed while waiting:") + shown)
                .joinToString("\n")
        }

        /** The first line already names the element; the rows do not repeat it. */
        private fun withoutSubject(reason: String): String = reason.replace(Regex("^Element '[^']*' "), "")

        private fun seconds(ms: Long): String =
            String.format(java.util.Locale.ROOT, "%.1f", maxOf(0L, ms) / 1000.0)
    }
}
