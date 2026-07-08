package com.jsonui.testrunner.runner

/**
 * Provides read access to ViewModel state for `state` assertions and
 * `state` conditions (in `when` / `repeat.while`).
 *
 * Inject an implementation into [JsonUITestRunner.stateProvider]. The test
 * harness owns the mapping from a dot-notation path (e.g. "user.isPremium")
 * to the actual ViewModel property value.
 */
interface ViewModelStateProvider {
    /**
     * Resolve the value at a dot-notation path, or null when the path
     * does not resolve.
     */
    fun getValue(path: String): Any?
}
