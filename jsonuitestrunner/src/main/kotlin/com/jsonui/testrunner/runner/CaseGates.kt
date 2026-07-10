package com.jsonui.testrunner.runner

/**
 * Skip decision for a screen-test case. [reason] maps to the results
 * contract's `skipReason` ("platform" | "responsive"); null reason means the
 * case was skipped by its explicit `skip: true` flag (no gate involved).
 */
data class CaseSkip(val reason: String?)

/**
 * Resolve whether (and why) a case is skipped, given its gates.
 *
 * Deterministic skip-reason precedence: `skip` flag, then platform, then
 * responsive. When a case carries both `platform` and `responsive` and both
 * are unmet, the reason is "platform" — the static gate wins over the
 * window-size-dependent one, keeping reports stable across device sizes
 * (parity with the web driver).
 *
 * @param responsiveMet result of evaluating the case's `responsive` gate
 *   against the current window size, or null when the case has no gate (or
 *   the gate was not evaluated because an earlier gate already failed).
 */
fun resolveCaseSkip(
    skipFlag: Boolean,
    platformMet: Boolean,
    responsiveMet: Boolean?
): CaseSkip? = when {
    skipFlag -> CaseSkip(reason = null)
    !platformMet -> CaseSkip(reason = "platform")
    responsiveMet == false -> CaseSkip(reason = "responsive")
    else -> null
}
