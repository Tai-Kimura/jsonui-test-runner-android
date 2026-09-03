package com.jsonui.testrunner.actions

/**
 * Which selector a selectOption step resolves by, per the canonical schema
 * (schemas/actions.schema.json, selectOptionAction): index, then value,
 * then label. A lower selector is ignored when a higher one is present —
 * a free-text note written in 'label' next to an 'index' must not become
 * the option to look for (measured 2026-09-03: iOS read label first and set
 * a picker wheel to the note). All three drivers resolve in this order.
 */
sealed class SelectOptionSelector {
    data class ByIndex(val index: Int) : SelectOptionSelector()
    data class ByValue(val value: String) : SelectOptionSelector()
    data class ByLabel(val label: String) : SelectOptionSelector()

    companion object {
        fun resolve(index: Int?, value: String?, label: String?): SelectOptionSelector? = when {
            index != null -> ByIndex(index)
            value != null -> ByValue(value)
            label != null -> ByLabel(label)
            else -> null
        }
    }
}
