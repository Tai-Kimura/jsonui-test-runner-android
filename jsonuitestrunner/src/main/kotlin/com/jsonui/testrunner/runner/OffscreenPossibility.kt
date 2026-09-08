package com.jsonui.testrunner.runner

/**
 * Whether an id the projection does not hold could still EXIST, off-screen.
 *
 * Reported 2026-09-08 by a consumer face: `STILL_MISSING` renders
 * "the app side never projected it", and that sentence is a claim about the
 * APP. Two different situations produce the identical census:
 *
 *   (A) it appeared, but below the viewport -> off-screen Compose nodes are
 *       not projected -> absent
 *   (B) it was never created                -> absent
 *
 * In case (A) the sentence is simply FALSE, and it is false in the direction
 * that costs the most: it points the reader at the app's rendering while the
 * actual cause is where the previous step stopped scrolling. The face that
 * reported it had to add a probe step and run the suite again to tell the two
 * apart — one run that the message could have saved.
 *
 * ⚠️ This does NOT try to answer which one happened. It reports what is
 * cheaply knowable at the failure site — whether a scroll container is
 * present and still has somewhere to go — and lets the message say only what
 * that supports. A verdict invented here would be the same overclaim in a
 * new place.
 */
enum class OffscreenPossibility {
    /** No container was resolved, so nothing is known about room below. */
    UNKNOWN,

    /** A scroll container is present and NOT at the end of its content. */
    ROOM_LEFT,

    /** A scroll container is present and reports no further room. */
    NO_ROOM_LEFT;

    companion object {
        /**
         * [ROOM_LEFT] when *scrollable* is true and *atEnd* is false.
         *
         * ⚠️ `scrollable == false` is [UNKNOWN], never [NO_ROOM_LEFT]: a
         * container that does not report itself scrollable has told us
         * nothing about the content below it, and folding that into
         * "no room" would restore the confident sentence this exists to
         * remove — for the very case where the container is unusual.
         */
        @JvmStatic
        fun of(scrollable: Boolean, atEnd: Boolean): OffscreenPossibility = when {
            !scrollable -> UNKNOWN
            atEnd -> NO_ROOM_LEFT
            else -> ROOM_LEFT
        }
    }
}
