package com.jsonui.testrunner.runner

/**
 * Where execution is, so a failure can say which step it was.
 *
 * A failure message used to carry the exception's own text and nothing else.
 * A flow of dozens of steps that died on an exception with a null message
 * reported one word -- the class name -- and the reader had to pull
 * `failure.png` and grep `hierarchy.xml` to work out where it stopped.
 * The step identity was never lost by accident: it simply never travelled
 * with the throw, because every catch site sits above the loop that knows
 * the index.
 *
 * Kept out of the runner so it can be exercised without a device: the runner
 * needs a `UiDevice`, this needs nothing.
 */
internal class StepTrail {

    /** Innermost last. */
    private val frames = ArrayDeque<String>()

    /**
     * A stack rather than a single field so that a block, or a case pulled in
     * by a file reference, names its own position AND the position of the
     * step that entered it. Popped in `finally`, so a step that throws does
     * not leave its frame behind for the next one to inherit.
     */
    inline fun <T> inFrame(frame: String, body: () -> T): T {
        push(frame)
        try {
            return body()
        } finally {
            pop()
        }
    }

    fun push(frame: String) {
        frames.addLast(frame)
    }

    fun pop() {
        if (frames.isNotEmpty()) frames.removeLast()
    }

    fun depth(): Int = frames.size

    /**
     * The failure text: where it happened, then what was thrown.
     *
     * `message ?: toString()` survives as the tail -- a message-less
     * exception still degrades to its class name -- but the class name is no
     * longer the whole report. With an empty trail (a failure before any step
     * ran, e.g. mock setup) this returns what the old expression returned,
     * except that a null message now falls back to `toString()` rather than
     * to null.
     */
    fun describe(t: Throwable): String {
        val cause = t.message?.takeIf { it.isNotBlank() } ?: t.toString()
        if (frames.isEmpty()) return cause
        return frames.joinToString(" > ") + " -- " + cause
    }

    companion object {
        /**
         * `step 32/48 "pick an option" (action=tap, id=choice_label)`.
         *
         * The index is 1-based and carries its total, because "step 32" alone
         * does not say whether the flow was 33 steps or 48 -- and the reader
         * who has to open the artifacts needs to know how far in it got.
         */
        fun frame(kind: String, index: Int, total: Int, label: String?, detail: String): String {
            val head = "$kind ${index + 1}/$total"
            val named = label?.takeIf { it.isNotBlank() }?.let { "$head \"$it\"" } ?: head
            return "$named ($detail)"
        }
    }
}
