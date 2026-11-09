package com.jsonui.testrunner.actions

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

/**
 * What `setSliderValue` and the `value` assertion decide before and after
 * anything moves, kept off the device so it can be tested as it is.
 *
 * Measured 2026-11-09 (Material3 Slider, API 35): ACTION_SET_PROGRESS puts
 * the slider at the value exactly, but a value outside the range is clamped
 * to the bound in silence (0 on 1...10 lands at 1, and the action answers
 * true), and a slider with `steps` snaps to the nearest step (9.4 lands at
 * 9). The step therefore refuses those before it acts, naming the bound or
 * the grid's neighbours, and checks what it reads back.
 */
object SliderPlan {
    /** Relative slack for comparing two numbers that should be the same one. */
    private const val EPS = 1e-6

    fun same(a: Double, b: Double, scale: Double = 1.0): Boolean =
        abs(a - b) <= EPS * max(1.0, abs(scale))

    /**
     * Why [value] cannot be put on a slider whose own bounds are [min]...[max],
     * or null when it can. [declaredRange] and [step] are the step's own
     * `range` and `step`; a range that differs from the slider's is refused
     * first, since every other judgement would be made against the wrong one.
     */
    fun refusal(
        id: String,
        value: Double,
        min: Double,
        max: Double,
        declaredRange: List<Double>? = null,
        step: Double? = null
    ): String? {
        val span = max - min
        if (declaredRange != null) {
            if (declaredRange.size != 2) return "setSliderValue '$id': range must be [minimum, maximum], got $declaredRange"
            if (!same(declaredRange[0], min, span) || !same(declaredRange[1], max, span)) {
                return "setSliderValue '$id': the step declares range [${fmt(declaredRange[0])}, ${fmt(declaredRange[1])}]" +
                    " but the slider's own is [${fmt(min)}, ${fmt(max)}]"
            }
        }
        if (value < min && !same(value, min, span)) {
            return "setSliderValue '$id': ${fmt(value)} is below the slider's range [${fmt(min)}, ${fmt(max)}];" +
                " the nearest value it can take is ${fmt(min)}"
        }
        if (value > max && !same(value, max, span)) {
            return "setSliderValue '$id': ${fmt(value)} is above the slider's range [${fmt(min)}, ${fmt(max)}];" +
                " the nearest value it can take is ${fmt(max)}"
        }
        if (step != null) {
            if (step <= 0) return "setSliderValue '$id': step must be greater than 0, got ${fmt(step)}"
            val k = (value - min) / step
            if (!same(k, round(k))) {
                val below = min + floor(k) * step
                val above = minOf(below + step, max)
                return "setSliderValue '$id': ${fmt(value)} is not on the slider's steps" +
                    " (${fmt(min)} + k * ${fmt(step)}); the nearest values are ${fmt(below)} and ${fmt(above)}"
            }
        }
        return null
    }

    /** The failure for a read-back that is not the value written, or null. */
    fun landedRefusal(id: String, value: Double, landed: Double, min: Double, max: Double): String? {
        if (same(value, landed, max - min)) return null
        return "setSliderValue '$id': the slider is at ${fmt(landed)}, not ${fmt(value)}" +
            " — its steps put the value on the nearest step; write a value on the slider's steps"
    }

    /** A number as a test author writes it: 9, 9.5, 0.123456, -2.5. */
    fun fmt(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return v.toString()
        val r = round(v)
        if (abs(v - r) < 1e-9) return r.toLong().toString()
        return java.math.BigDecimal(v).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
    }
}
