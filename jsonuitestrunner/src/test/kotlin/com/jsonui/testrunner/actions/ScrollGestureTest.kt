package com.jsonui.testrunner.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM tests for the scroll-swipe geometry (pure function, no device).
 *
 * The first case is the 2026-09-03 consumer incident verbatim: the fallback
 * rect resolved to a 148x63px header node at the left edge, and logcat shows
 * `Swiping from (74, 238) to (74, 194)` followed by
 * `CoreBackPreview: startBackNavigation`. On a 1080px display at density
 * 2.625 the 48dp edge zone is 126px, so x=74 is a back gesture.
 */
class ScrollGestureTest {

    private val display = 1080
    private val edge = 126 // 48dp @ 2.625

    @Test
    fun incidentRectSwipesStartOutsideTheBackGestureZone() {
        // Header node measured in the incident: (0,185)-(148,248).
        val line = scrollSwipe(0, 185, 148, 248, "down", display, edge)
        // Unclamped this is exactly the logcat line: (74, 238) -> (74, 194).
        assertEquals(238, line.startY)
        assertEquals(194, line.endY)
        assertTrue("start x ${line.startX} is inside the ${edge}px back-gesture zone",
            line.startX >= edge)
        assertEquals(edge, line.startX)
    }

    @Test
    fun unclampedGeometryReproducesTheIncidentLogcatLine() {
        // Control for the test above: with no edge zone the same rect yields
        // the incident's coordinates, so the clamp is what moved the start.
        val line = scrollSwipe(0, 185, 148, 248, "down", display, 0)
        assertEquals(SwipeLine(74, 238, 74, 194), line)
    }

    @Test
    fun fullWindowRectIsUntouchedByTheClamp() {
        // Control: the app-window root on the same display. Center x=540 is
        // far from both zones, so the clamp must be an identity here.
        val down = scrollSwipe(0, 0, 1080, 2400, "down", display, edge)
        assertEquals(SwipeLine(540, 1200 + 840, 540, 1200 - 840), down)
        val up = scrollSwipe(0, 0, 1080, 2400, "up", display, edge)
        assertEquals(SwipeLine(540, 1200 - 840, 540, 1200 + 840), up)
    }

    @Test
    fun rightEdgeIsClampedSymmetrically() {
        // Mirror of the incident at the right edge: (932,185)-(1080,248).
        val line = scrollSwipe(932, 185, 1080, 248, "up", display, edge)
        assertEquals(display - edge, line.startX)
    }

    @Test
    fun horizontalSwipesClampOnlyTheStartPoint() {
        // A narrow left-edge rail (0..200): "left" starts at cx-dx = 100-70 = 30.
        val left = scrollSwipe(0, 0, 200, 800, "left", display, edge)
        assertEquals(edge, left.startX)
        assertEquals(170, left.endX) // end point is not the detector's concern
        // "right" on the same rail starts at 170 -> clamped to 126... no:
        // 170 is already inside the safe band, so it stays.
        val right = scrollSwipe(0, 0, 200, 800, "right", display, edge)
        assertEquals(170, right.startX)
        assertEquals(30, right.endX)
    }

    @Test
    fun degenerateDisplayLeavesTheGeometryAlone() {
        // Insets that swallow the whole width: nothing sane to clamp to, so the
        // rect's own center is used (documented identity).
        val line = scrollSwipe(0, 185, 148, 248, "down", 200, 100)
        assertEquals(74, line.startX)
        assertEquals(74, clampToGestureSafeX(74, 200, 100))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDirectionThrows() {
        scrollSwipe(0, 0, 100, 100, "sideways", display, edge)
    }

    /**
     * The trap is a divergent copy: 1.6.5 removed `By.pkg` from the responsive
     * branch and left the scroll fallback with its own. Both now go through
     * AppWindow; no source line may acquire a root via By.pkg again. Comment
     * lines are ignored so the history can stay written down.
     */
    @Test
    fun noSourceLineAcquiresARootViaByPkg() {
        val root = listOf(File("src/main/kotlin"), File("jsonuitestrunner/src/main/kotlin"))
            .first { it.isDirectory }
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    val code = line.trimStart()
                    val isComment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                    if (!isComment && code.contains("By.pkg(")) "${file.path}:${i + 1}: $code" else null
                }
            }.toList()
        assertEquals("By.pkg root acquisition must go through AppWindow", emptyList<String>(), offenders)
    }
}
