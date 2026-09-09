package com.jsonui.testrunner.actions

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-09-09 report is about WIRING, not arithmetic: every pure function
 * involved was already green and stayed green while the step returned with a
 * moving target. [ViewportMargin] and [TargetSettle] can only say what the
 * rule decides; whether the rule RUNS, and whether a settle follows the swipe
 * or precedes it, lives in the order of statements in ActionExecutor.
 *
 * So this reads the source. A source-reading arm cannot tell an
 * implementation from a sentence ABOUT the implementation, and this file's
 * subject is now covered in comments that name `awaitTargetSettled` and
 * `unstickFromTrailingEdge` a dozen times — an arm that matched its own
 * documentation would pass on a tree where the code was deleted. Comments and
 * string literals are therefore stripped before anything is asserted, and
 * [codeOnlyActuallyStrippedBothKinds] is the control that proves the
 * stripping happened rather than being assumed.
 */
class ScrollUntilVisibleWiringTest {

    private val source: String by lazy {
        val rel = "src/main/kotlin/com/jsonui/testrunner/actions/ActionExecutor.kt"
        val candidates = listOf(
            File(rel),
            File("jsonuitestrunner/$rel"),
            File("../jsonuitestrunner/$rel")
        )
        val found = candidates.firstOrNull { it.isFile }
        // Fail, never skip: a fixture this arm cannot find is a broken arm,
        // and a skipped arm reports the same green as a passing one.
        assertTrue(
            "ActionExecutor.kt not found from ${File(".").absolutePath}; tried " +
                candidates.joinToString { it.path },
            found != null
        )
        found!!.readText()
    }

    /**
     * Comments removed, string literals KEPT.
     *
     * 🚨 [codeOnly] removes both, which makes an emitted SPELLING invisible.
     * The obvious repair — assert the literal against the raw file — matched
     * the ARM'S OWN COMMENT explaining the literal, so deleting the prefix
     * from the code left the arm green. Measured: that mutation passed.
     *
     * ⚠️ This is the failure this class documents elsewhere, committed by
     * this class. A source-reading arm cannot tell an implementation from a
     * sentence about it, and prose describing a fix is written in the fix's
     * own vocabulary — so the arm is at its blindest exactly where the change
     * is best explained.
     */
    private fun withoutComments(text: String): String {
        val sb = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                sb.append(c)
                if (c == '\\' && i + 1 < text.length) { sb.append(text[i + 1]); i += 2; continue }
                if (c == quote) quote = null
                i++
                continue
            }
            if (c == '"' || c == '\'') { quote = c; sb.append(c); i++; continue }
            if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                while (i < text.length && text[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                i += 2
                while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                i += 2
                continue
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /** Source with comments and string literals removed. */
    private fun codeOnly(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            when {
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    i++
                    while (i < text.length && text[i] != quote) {
                        if (text[i] == '\\') i++
                        i++
                    }
                    i++
                    sb.append("<lit>")
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    /** Body of [name] from the stripped source, by brace matching. */
    private fun bodyOf(name: String): String {
        val code = codeOnly(source)
        val at = code.indexOf("private fun $name(")
        assertTrue("no declaration of $name in the stripped source", at >= 0)
        val open = code.indexOf('{', at)
        assertTrue("no body for $name", open >= 0)
        var depth = 0
        var i = open
        while (i < code.length) {
            if (code[i] == '{') depth++
            if (code[i] == '}') {
                depth--
                if (depth == 0) return code.substring(open, i + 1)
            }
            i++
        }
        throw AssertionError("unbalanced braces in $name")
    }

    /** Occurrences of each of [tokens] in [body], in the order they appear. */
    private fun sequenceOf(body: String, tokens: List<String>): List<String> =
        tokens.flatMap { t ->
            val hits = mutableListOf<Pair<Int, String>>()
            var from = 0
            while (true) {
                val at = body.indexOf(t, from)
                if (at < 0) break
                hits.add(at to t)
                from = at + 1
            }
            hits
        }.sortedBy { it.first }.map { it.second }

    /**
     * The control for every other arm here. Each half moves a real 1 -> 0: a
     * spelling that exists ONLY in a comment, and one that exists ONLY inside
     * a string literal. Asserting they are present in the raw text first is
     * what keeps this from passing because the file failed to load.
     */
    @Test
    fun codeOnlyActuallyStrippedBothKinds() {
        val onlyInAComment = "MID-FLING"
        val onlyInAString = "flush at "
        assertTrue("comment probe absent from raw source", source.contains(onlyInAComment))
        assertTrue("string probe absent from raw source", source.contains(onlyInAString))

        val code = codeOnly(source)
        assertFalse("comments were not stripped", code.contains(onlyInAComment))
        assertFalse("string literals were not stripped", code.contains(onlyInAString))
        // Positive control: the stripper kept the code it is supposed to keep.
        assertTrue(code.contains("private fun executeScrollUntilVisible"))
        assertTrue(code.contains("private fun unstickFromTrailingEdge"))
    }

    /**
     * 1.14.0 ran the trailing-edge clearance rule on ONE of the three exits —
     * the early return taken when the target was already visible. The two
     * scroll legs return the instant the target appears, which scrolling down
     * is the instant it has entered from the trailing edge: the exits most
     * likely to leave a target flush were the exits the rule never reached.
     *
     * The rule was reviewed per-RULE and shipped with two of its three call
     * sites bare, so the pin is per-CALL-SITE: this asserts the exact
     * sequence, not a count, because a count is satisfied by three unsticks
     * on one exit.
     */
    @Test
    fun everyExitOfScrollUntilVisibleRunsTheClearanceRule() {
        val body = bodyOf("executeScrollUntilVisible")
        val seq = sequenceOf(body, listOf("awaitTargetSettled(", "unstickFromTrailingEdge("))
        assertEquals(
            listOf(
                "awaitTargetSettled(", "unstickFromTrailingEdge(",
                "awaitTargetSettled(", "unstickFromTrailingEdge(",
                "awaitTargetSettled(", "unstickFromTrailingEdge("
            ),
            seq
        )
    }

    /**
     * Exits of a function body. `return@label` leaves a lambda, not this
     * function, and the trailing `throw` is the not-found path which must NOT
     * run the clearance rule — so neither counts.
     */
    private fun exitCount(body: String): Int =
        Regex("\\breturn\\b(?!@)").findAll(body).count()

    private fun ruleCount(body: String): Int =
        Regex("unstickFromTrailingEdge\\(").findAll(body).count()

    /**
     * [everyExitOfScrollUntilVisibleRunsTheClearanceRule] pins the ORDER of the
     * calls that exist. It does not pin how many exits exist — so a FOURTH exit
     * added with no clearance rule leaves it green, which is defect (c) exactly,
     * unclosed in the direction the population grows. Measured by another lane
     * against this very file: a synthetic early `return` reddened nothing.
     *
     * Deleting a call and adding an exit are the same defect seen from the two
     * ends, so they need one arm each: the sequence catches the deletion, this
     * catches the addition.
     */
    @Test
    fun noExitCanBeAddedWithoutTheClearanceRule() {
        val body = bodyOf("executeScrollUntilVisible")
        assertEquals(
            "every return in executeScrollUntilVisible must pass the clearance rule",
            exitCount(body),
            ruleCount(body)
        )
    }

    /**
     * The control the arm above needs. Counting exits is a NEW discriminator,
     * and a discriminator with no control is the thing this whole release is
     * about — so the counter is shown to move on synthetic bodies rather than
     * trusted because the real file happens to agree with it today.
     */
    @Test
    fun theExitCounterItselfMovesOnSyntheticBodies() {
        val three = "{ return unstickFromTrailingEdge( return unstickFromTrailingEdge( " +
            "return unstickFromTrailingEdge( }"
        val fourExitsThreeRules = "{ return " + three.trim('{', '}', ' ') + " }"
        assertEquals(3, exitCount(three))
        assertEquals(3, ruleCount(three))
        assertEquals("a fourth exit must be visible to the counter", 4, exitCount(fourExitsThreeRules))
        assertEquals(3, ruleCount(fourExitsThreeRules))
        // ...and the comparison the arm makes must actually separate them.
        assertTrue(exitCount(three) == ruleCount(three))
        assertFalse(exitCount(fourExitsThreeRules) == ruleCount(fourExitsThreeRules))
        // A lambda escape is not an exit of the enclosing function.
        assertEquals(1, exitCount("{ list.forEach { return@forEach }; return }"))
    }

    /**
     * (b) of the report: this helper is the LAST thing scrollUntilVisible
     * does, so its own swipe was the one motion no settle covered — the next
     * step tapped a sliding target. Both swipes must be followed by a settle.
     *
     * (a) of the report: `keepScrolledPosition` is the whole "never make it
     * worse" guarantee, and it was reading bounds mid-fling — the reporter
     * logged after=1184 for a target that came to rest at ~1157. So the
     * settle must sit BETWEEN the swipe and the re-measure, which an ordering
     * pin states and a pair of presence checks does not.
     */
    @Test
    fun everyMotionTheClearanceRuleMakesIsSettledBeforeItIsMeasured() {
        val body = bodyOf("unstickFromTrailingEdge")
        assertFalse(
            "waitForIdle does not wait out a Compose fling under a bare UiAutomator",
            body.contains("waitForIdle")
        )
        val seq = sequenceOf(
            body,
            listOf("device.swipe(", "awaitTargetSettled(", "keepScrolledPosition(")
        )
        assertEquals(
            listOf(
                "device.swipe(",            // clearance scroll
                "awaitTargetSettled(",      // ...settled BEFORE it is read
                "keepScrolledPosition(",    // ...and the guard reads a resting value
                "device.swipe(",            // rollback, when the guard said revert
                "awaitTargetSettled("       // ...and it is the last motion, so it settles too
            ),
            seq
        )
    }

    // ---------------------------------------------------------------- 1.15.1

    /**
     * Every re-find on this path reads its bounds through the stale-safe
     * helper, and only the helper itself holds a bare read.
     *
     * 🚨 `visibleBounds` reads the node behind the handle `findObject` just
     * returned. When that node is replaced in between, uiautomator raises
     * `StaleObjectException` rather than returning null — and [boundsOf]'s
     * callers all already decide what to do about "it is not there". The
     * settle loop says so in as many words. It only ever saw one spelling.
     *
     * ⚠️ This counts CALL SITES, not the rule: a fix applied to the site
     * someone remembered reaches only that site. 1.15.0 shipped a settle that
     * polls this value up to 20 times per call WHILE THE UI IS MOVING, and a
     * throw from it fails the whole test through [execute]'s retry — an
     * advisory helper that only prints must not be able to do that.
     */
    @Test
    fun `every re-find reads bounds through the stale-safe helper`() {
        val code = codeOnly(source)
        val bare = Regex("""findObject\(By\.res\([A-Za-z]+\)\)\?\.visibleBounds""")
            .findAll(code).count()
        assertEquals(
            "exactly one bare read may remain, and it is the one inside boundsOf",
            1, bare
        )
        assertTrue("boundsOf must exist", code.contains("private fun boundsOf(id: String): Rect?"))
        // Four or more consumers, or the helper was added and not adopted.
        assertTrue(
            "boundsOf is declared but barely used: ${Regex("boundsOf\\(").findAll(code).count()}",
            Regex("boundsOf\\(").findAll(code).count() >= 6
        )
    }

    /**
     * The helper turns the exception into the null its callers handle.
     *
     * ⚠️ Existence of a `try` is not the property — the property is that the
     * caught type is `StaleObjectException` and that the handler yields
     * `null` rather than rethrowing or returning an empty rect. An empty rect
     * would pass `takeIf { !it.isEmpty }` at one site and fail it at another.
     */
    /**
     * A function's text with comments removed and string literals KEPT.
     *
     * 🚨 [bodyOf] runs [codeOnly] first, which removes literals too — so
     * `withoutComments(bodyOf(x))` strips TWICE and an emitted spelling is
     * still gone. Two arms in this class have now been written against text
     * the literals had been removed from: once via [bodyOf], once via that
     * exact double-strip, minutes after the helper for it was added. Having
     * the right tool is not the same as reaching for it, so the window that
     * keeps literals gets its own extractor instead of being composed at the
     * call site.
     */
    private fun literalBodyOf(name: String): String {
        val code = withoutComments(source)
        val at = code.indexOf("private fun $name(")
        assertTrue("declaration of $name not found", at >= 0)
        val next = code.indexOf("\n    private fun ", at + 1)
        return if (next < 0) code.substring(at) else code.substring(at, next)
    }

    /**
     * From a declaration to the start of the next one.
     *
     * ⚠️ [bodyOf] matches braces from the first `{`, which for an
     * EXPRESSION-BODIED function (`= try { … } catch { … }`) closes at the end
     * of the `try` — before the handler this arm is about. A window that stops
     * short of the claim reports absence, and absence here looked exactly like
     * "the catch was never written".
     */
    private fun declOf(name: String): String {
        val code = codeOnly(source)
        val at = code.indexOf("private fun $name(")
        assertTrue("declaration of $name not found", at >= 0)
        val next = code.indexOf("private fun ", at + 1)
        return if (next < 0) code.substring(at) else code.substring(at, next)
    }

    @Test
    fun `boundsOf converts a stale node into the absent case`() {
        val body = declOf("boundsOf")
        val seq = sequenceOf(
            body,
            listOf("device.findObject(", "catch (e: StaleObjectException)", "null")
        )
        assertEquals(
            listOf("device.findObject(", "catch (e: StaleObjectException)", "null"),
            seq
        )
        assertFalse("the handler must not rethrow", body.contains("throw"))
    }

    /**
     * The settle loop — the site 1.15.0 added — is one of the consumers.
     *
     * ⚠️ Named separately from the count above because the count is satisfied
     * by any six sites. This one is the site whose throw reaches [execute],
     * so it is the one that must not regress even if the others do.
     */
    @Test
    fun `the settle loop reads through the helper and breaks on absent`() {
        val body = codeOnly(bodyOf("awaitTargetSettled"))
        assertTrue("the settle must not re-find bare", !body.contains("findObject("))
        assertTrue("the settle must use boundsOf", body.contains("boundsOf(id)"))
        assertTrue("absent still ends the loop", body.contains("?: break"))
    }

    /**
     * The containment gate runs BEFORE the edge test, and it speaks.
     *
     * ⚠️ Order is the property, not presence: placed after
     * `isFlushAgainstTrailingEdge` it would only ever fire for targets that
     * are already flush, which is the subset that happens to be visible at
     * runtime — the mis-specified containers whose target sits mid-screen
     * would still say nothing.
     *
     * ⚠️ And a silent `return` would be the same defect wearing a fix: the
     * wasted swipes go away and the reason they existed becomes unobservable.
     * The arm therefore pins the warning, not just the early exit.
     */
    @Test
    fun `the unstick reports a target outside its surface without skipping it`() {
        // ⚠️ TWO WINDOWS, AND THEY ARE NOT THE SAME SIZE. `codeOnly` strips
        // string literals, so an emitted SPELLING is invisible in it. The
        // first draft asserted the literal against stripped text; the second
        // called it `raw` while `bodyOf` strips internally — a rename, not a
        // window. Structure is checked on the stripped BODY; the spelling on
        // the unstripped FILE, which is the only raw text this class holds.
        //
        // ⚠️ So the spelling arm does not prove the literal is in THIS
        // function — a weaker window than the structure arms, said out loud
        // rather than implied by the variable's name.
        val body = codeOnly(bodyOf("unstickFromTrailingEdge"))
        val seq = sequenceOf(
            body,
            listOf("isOutsideTrailingEdge(", "warningHandler",
                   "isFlushAgainstTrailingEdge(")
        )
        assertEquals(
            "the outside test must run, report, and come before the edge test",
            listOf("isOutsideTrailingEdge(", "warningHandler",
                   "isFlushAgainstTrailingEdge("),
            seq
        )
        // ⚠️ And it must be the ONE-SIDED predicate. `Rect.contains` tests four
        // edges for a claim about one, and `visibleBounds` is clipped to the
        // screen rather than to the container — so a target inside a
        // horizontally scrollable container would be dropped for a reason a
        // vertical swipe never had anything to do with.
        assertFalse("the four-sided test must not come back",
            body.contains("surface.contains("))
        // 🚨 WARN-ONLY: the judgment must not suppress the swipe in 1.15.1.
        // A false positive that also skips leaves the target invisible and
        // reddens a LATER step, so the warning and the real defect become
        // indistinguishable. Pinned as the ABSENCE of an exit between the
        // report and the edge test — presence of the warning cannot say it.
        val between = body.substring(
            body.indexOf("isOutsideTrailingEdge("),
            body.indexOf("isFlushAgainstTrailingEdge("))
        assertFalse("the outside branch must not return in 1.15.1",
            Regex("\\breturn\\b").containsMatchIn(between))
        // The line has to be countable by the readers that count it.
        assertTrue("the warning needs a greppable severity prefix",
            withoutComments(source).contains("WARN [scrollUntilVisible]"))
        assertTrue("and it must reach System.out, not only the result object",
            between.contains("println("))
    }

    /**
     * The notice must not contain any fixed fragment of the lines it is about.
     *
     * 🚨 REPORTED FROM FIVE CAPTURES ACROSS TWO FACES: the notice spelled the
     * data line's prefix, so `grep -c` for that prefix counted the notice —
     * exactly +1 per run. That count is a denominator AND an invariant, and
     * one face read `unstick 13 / after-unstick 12`, a violation the emit
     * order makes impossible, then closed it with a plausible explanation
     * until another face shot it from the ordering.
     *
     * ⚠️ THE FORBIDDEN TOKENS ARE DERIVED FROM THE DATA LINE, NOT LISTED HERE.
     * A hand-written list is a second declaration: rename a fragment in the
     * `println` and the list keeps checking the old one, silently. This takes
     * the fixed (non-interpolated) fragments of the emitted line and requires
     * the notice to carry none of them.
     *
     * ⚠️ AND IT COMPARES EMITTED BYTES, NOT SOURCE. The first repair split the
     * token as `"un" + "stick"`, which changes the source and not one byte of
     * the output; the second named the replacement predicate inside the notice
     * and recreated the collision against it. Both passed a check that grepped
     * the source. Consumers grep the log.
     */
    @Test
    fun `the unstick notice shares no fixed fragment with the line it describes`() {
        val raw = source
        val noticeAt = raw.indexOf("RunNotices.once(")
        val lineAt = raw.indexOf("println(\"[ActionExecutor] unstick", noticeAt)
        assertTrue("both sites must exist", noticeAt in 0 until lineAt)

        // Emitted text = the string literals with comments removed FIRST, so a
        // comment about a token is never mistaken for the token.
        fun emitted(block: String, dropFirst: Int): String {
            val noComments = block.replace(Regex("//[^\n]*"), "")
            return Regex(""""((?:[^"\\]|\\.)*)"""")
                .findAll(noComments).map { it.groupValues[1] }.drop(dropFirst)
                .joinToString("")
        }
        // drop 1: the notice's first literal is its key, not its text.
        val notice = emitted(raw.substring(noticeAt, lineAt), 1)
        val data = emitted(raw.substring(lineAt, raw.indexOf("\n    }", lineAt)), 0)
            .split(Regex("\\$\\{[^}]*\\}|\\$[A-Za-z_]+")).joinToString(" ")

        assertTrue("the arm read nothing: notice=${notice.length} data=${data.length}",
            notice.length > 40 && data.length > 40)

        var longest = ""
        for (i in data.indices) {
            var j = i + longest.length + 1
            while (j <= data.length && notice.contains(data.substring(i, j))) {
                longest = data.substring(i, j); j++
            }
        }
        assertTrue(
            "the notice reproduces ${longest.length} characters of the line it " +
                "describes: '$longest'. Describe the line; do not spell it.",
            longest.length < MAX_SHARED_RUN
        )
    }

    /**
     * The diagnostic line carries both quantities the open report needs.
     *
     * A filed report establishes that `CLEARANCE_FRACTION` is applied to two
     * bases in one flow — the shorter side for the clearance, the scroll axis
     * for the swipe step — and deliberately stops short of calling the swipe
     * wrong, because that needs a device the reporting lane does not have.
     * Neither `surface` nor `step` was printed, so no face could settle it
     * from captures it already keeps.
     *
     * ⚠️ This arm pins the INPUTS to that question, not an answer to it. If
     * the two bases are later aligned, this stays green — which is correct:
     * the line should carry both numbers either way.
     */
    @Test
    fun `the unstick line reports the surface and the swipe step`() {
        // ⚠️ `withoutComments`, NOT `codeOnly`. codeOnly strips string
        // literals as well, so an emitted SPELLING is invisible in it — the
        // first draft of this arm failed for exactly that reason, which is
        // the third time this class has been written against text the
        // literals had been removed from. The helper for it already existed
        // on the line above; having the tool is not the same as reaching for
        // it.
        val body = literalBodyOf("unstickFromTrailingEdge")
        // ⚠️ PIN THE EMITTED FIELD NAMES, NOT THE EXPRESSIONS THAT FEED THEM.
        // The first draft asserted `surface.width()` and `surface.height()`
        // appear — and they do, inside the `clearanceFor(...)` call that was
        // already there. Deleting the whole `surface=` field left that arm
        // green. A mutation found it; reading did not.
        assertTrue("the swipe step must be reported", body.contains("step=\$step"))
        assertTrue("the surface it was derived from must be reported too",
            body.contains("surface=\${surface.width()}x\${surface.height()}"))
        // The clearance was already there; without it the other two say
        // nothing, because the report is about their RATIO.
        assertTrue("clearance must stay", body.contains("clearanceFor("))
    }

    /**
     * Both consuming faces' predicates match the line this file emits.
     *
     * 🚨 THEY DO NOT READ IT THE SAME WAY, and one placement satisfied only
     * one of them. `surface=` and `step=` were first inserted between
     * `clearance=` and `after=`. The face whose regex is
     * `flush at (\d+) of (\d+)` reviewed that and called it safe — its own
     * parser was unaffected. The other face matches all seven groups through
     * to `kept=`, which requires `clearance=` to be immediately followed by
     * `after=`; for it the line would have stopped parsing entirely, and
     * every number derived from it — firing counts, no-op rate, margins —
     * would have come back zero without an error.
     *
     * ⚠️ ONE CONSUMER'S REVIEW IS NOT THE CONTRACT. It was caught by the
     * second face reading the proposal, not by anything here. So both
     * predicates live in this arm, verbatim, and a new field cannot be
     * positioned by anyone's judgement about where "the end" is.
     *
     * ⚠️ The line is assembled from the source with placeholders substituted,
     * because the property is about the EMITTED text: an arm that read the
     * Kotlin would pass on interpolation syntax the faces never see.
     */
    @Test
    fun `both consuming predicates match the emitted unstick line`() {
        val body = literalBodyOf("unstickFromTrailingEdge")
        val at = body.indexOf("println(\"[ActionExecutor] unstick")
        assertTrue("the emit site must exist", at >= 0)
        val raw = Regex(""""((?:[^"\\]|\\.)*)"""")
            .findAll(body.substring(at)).map { it.groupValues[1] }
            .takeWhile { !it.contains("RunNotices") }
            .joinToString("")
        val line = raw
            .replace("\${before.bottom}", "2295")
            .replace("\${surface.bottom}", "2127")
            .replace("\${ViewportMargin.clearanceFor(surface.height(), surface.width())}", "129")
            .replace("\${surface.width()}", "1080")
            .replace("\${surface.height()}", "2400")
            .replace("\${after?.bottom}", "2295")
            .replace("\$step", "288").replace("\$keep", "true")
            .replace("\$id", "save_button").replace("\$via", "entry")
        assertFalse(
            "placeholders survived substitution — the arm is checking Kotlin, " +
                "not the emitted line: $line",
            line.contains("\$")
        )

        // Verbatim from the two faces, 2026-09-09.
        val loose = Regex("""flush at (\d+) of (\d+)""")
        val strict = Regex(
            """unstick '([^']+)' \[(\w+)\]: flush at (\d+) of (\d+), """ +
                """clearance=(\d+), after=(\S+), kept=(\w+)"""
        )
        assertTrue("the loose consumer's predicate no longer matches: $line",
            loose.containsMatchIn(line))
        assertTrue("the seven-group consumer's predicate no longer matches: $line",
            strict.containsMatchIn(line))
    }

    private companion object {
        /**
         * How much of the data line the notice may echo before it is a
         * collision rather than a coincidence.
         *
         * Measured 2026-09-09, three versions of the same notice:
         * ```
         *   current, describes only        'ction'                      5
         *   v1, spelled the prefix         '[ActionExecutor] unstick '  25   <- the reported defect
         *   v2, named the new predicate    ' flush at'                   9   <- this lane, minutes later
         * ```
         * ⚠️ 8 sits three below the passing case and ONE above v2. The margin
         * on the failing side is a single character, and it is written down
         * because the next person to add a sentence to that notice needs to
         * know how little room there is — not to raise the number.
         */
        const val MAX_SHARED_RUN = 8
    }
}
