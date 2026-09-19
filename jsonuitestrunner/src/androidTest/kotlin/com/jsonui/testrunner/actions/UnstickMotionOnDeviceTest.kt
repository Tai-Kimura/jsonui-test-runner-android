package com.jsonui.testrunner.actions

import android.content.Intent
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the clearance rule's corrective motion DOES to a scroll offset — the
 * one fact about [UnstickMotion] no JVM arm can see, read in-process from a
 * ScrollView's `scrollY` ([ScrollProbeActivity]).
 *
 * Reported 2026-09-19 (a phone lane, 1 run in 4): scrollUntilVisible found
 * its target, then returned it off-screen at the far end of the search
 * direction. The corrective swipe was `2 * 12% of the surface height` in 20
 * steps — 492px nominal on a 1080x2400 phone — released at fling velocity,
 * and the rollback was the same fling backwards. Measured here on that
 * phone before the change (scratch, 2026-09-20, 3+4 runs):
 *
 *     old swipe(20)  492 nominal → 499–669px, 200–350ms tail; rollback 520–645px
 *     drag  travel=129      → 105–133px;  rollback 126–129px
 *     drag  travel=258      → 267–312px;  rollback 292–314px
 *     drag  travel=387      → 486–495px;  rollback 491–494px
 *
 * So on a Views ScrollView the shipped drag still carries a tail that grows
 * with the travel (the hold does not zero the tracked velocity entirely —
 * the injected stationary moves appear to be coalesced), but it is bounded,
 * and forward and rollback are symmetric within ~10px, which is the
 * property the defect needed: a rollback that puts the target back where
 * the search found it.
 *
 * ⚠️ Views, not Compose: the driver has no Compose dependency. The fling
 * physics is the platform's spline in both, but the velocity tracking is
 * not byte-identical — on the reporting Compose page the same 387px drag
 * moved 375–386px in 12 firings (unstick lines, 8 green runs of 8), i.e.
 * no tail at all. So the consumer-tree acceptance is the measurement of
 * record; this arm keeps the SHAPE from regressing to a fling on the device
 * it was measured on, with bounds wide enough for the Views tail.
 *
 * No CI lane runs androidTest here; execute locally against an emulator:
 *   ./gradlew :jsonuitestrunner:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.jsonui.testrunner.actions.UnstickMotionOnDeviceTest
 */
@RunWith(AndroidJUnit4::class)
class UnstickMotionOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    private fun launch(): Rect {
        val ctx = instrumentation.context
        ctx.startActivity(
            Intent(ctx, ScrollProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        val deadline = System.currentTimeMillis() + 10_000
        while (ScrollProbeActivity.scrollView == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        device.waitForIdle(2000)
        val r = Rect()
        instrumentation.runOnMainSync { ScrollProbeActivity.scrollView!!.getGlobalVisibleRect(r) }
        return r
    }

    private fun scrollY(): Int {
        var y = -1
        instrumentation.runOnMainSync { y = ScrollProbeActivity.scrollView!!.scrollY }
        return y
    }

    private fun setScrollY(y: Int) {
        instrumentation.runOnMainSync { ScrollProbeActivity.scrollView!!.scrollTo(0, y) }
        Thread.sleep(300)
    }

    /** Poll until scrollY is unchanged for 300ms; returns (rest, msToRest). */
    private fun rest(): Pair<Int, Long> {
        val t0 = System.currentTimeMillis()
        var last = scrollY(); var stableSince = t0
        while (System.currentTimeMillis() - t0 < 5000) {
            Thread.sleep(50)
            val y = scrollY()
            if (y != last) { last = y; stableSince = System.currentTimeMillis() }
            else if (System.currentTimeMillis() - stableSince >= 300) break
        }
        return last to (stableSince - t0)
    }

    /** Runs [gesture] from a mid-content offset; returns px moved (signed). */
    private fun moved(label: String, gesture: () -> Unit): Int {
        setScrollY(START)
        val before = scrollY()
        gesture()
        val (after, ms) = rest()
        println("[UnstickMotion] $label: before=$before after=$after moved=${after - before}px rest=${ms}ms")
        return after - before
    }

    @Test
    fun theShippedDragIsBoundedAndItsRollbackCancelsIt() {
        val surface = launch()
        val cx = surface.centerX(); val cy = surface.centerY()
        val clearance = ViewportMargin.clearanceFor(surface.height(), surface.width())
        println("[UnstickMotion] surface=${surface.toShortString()} clearance=$clearance " +
            "STEPS=${UnstickMotion.STEPS} HOLD=${UnstickMotion.HOLD_SEGMENTS}")
        repeat(3) { i ->
            for (travel in listOf(clearance, 2 * clearance, 3 * clearance)) {
                val fwd = moved("forward travel=$travel #$i") {
                    device.swipe(UnstickMotion.path(cx, cy + travel / 2, cy - travel / 2), UnstickMotion.STEPS)
                }
                val back = moved("rollback travel=$travel #$i") {
                    device.swipe(UnstickMotion.path(cx, cy - travel / 2, cy + travel / 2), UnstickMotion.STEPS)
                }
                // Bounded: never more than a third beyond the finger's travel
                // (the old form was +25–36% of 492 — a different order).
                assertTrue("forward $fwd for travel $travel", fwd in (travel * 3 / 4)..(travel * 4 / 3))
                assertTrue("rollback $back for travel $travel", -back in (travel * 3 / 4)..(travel * 4 / 3))
                // Symmetric: forward + rollback nets to less than a quarter
                // clearance — the rollback puts the target back where it was.
                assertTrue("net ${fwd + back} for travel $travel", kotlin.math.abs(fwd + back) <= clearance / 4)
            }
        }
    }

    private companion object {
        const val START = 4000
    }
}
