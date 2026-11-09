package com.jsonui.testrunner.composeprobe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp

/**
 * Material3 Sliders as kjui emits them (Slider(value, onValueChange,
 * valueRange, steps, onValueChangeFinished) with a testTag the driver reads
 * as a resource id), for SetSliderValueOnDeviceTest. The values and how often
 * each callback ran are kept in [values] / [changes] / [finishes], read
 * in-process by the test. Started explicitly; not a launcher target.
 */
class SliderProbeActivity : ComponentActivity() {
    data class Spec(val id: String, val min: Float, val max: Float, val steps: Int, val initial: Float)

    companion object {
        val SPECS = listOf(
            Spec("s1to10", 1f, 10f, 0, 5f),
            Spec("s1to10steps", 1f, 10f, 8, 5f),
            Spec("s0to1", 0f, 1f, 0, 0f),
            Spec("sm5to5", -5f, 5f, 0, 0f),
            Spec("s0to1000", 0f, 1000f, 0, 0f),
        )
        val values = mutableMapOf<String, androidx.compose.runtime.MutableFloatState>()
        val changes = mutableMapOf<String, Int>()
        val finishes = mutableMapOf<String, Int>()
        @Volatile var resumed = false

        fun reset() {
            resumed = false
            values.clear(); changes.clear(); finishes.clear()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SPECS.forEach { values[it.id] = mutableFloatStateOf(it.initial); changes[it.id] = 0; finishes[it.id] = 0 }
        setContent {
            MaterialTheme {
                Column(Modifier.semantics { testTagsAsResourceId = true }.padding(16.dp)) {
                    SPECS.forEach { spec ->
                        val state = values.getValue(spec.id)
                        Slider(
                            value = state.floatValue,
                            onValueChange = { state.floatValue = it; changes[spec.id] = changes.getValue(spec.id) + 1 },
                            onValueChangeFinished = { finishes[spec.id] = finishes.getValue(spec.id) + 1 },
                            valueRange = spec.min..spec.max,
                            steps = spec.steps,
                            modifier = Modifier.testTag(spec.id)
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }
}
