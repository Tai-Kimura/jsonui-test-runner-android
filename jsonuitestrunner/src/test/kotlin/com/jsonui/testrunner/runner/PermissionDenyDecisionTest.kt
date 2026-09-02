package com.jsonui.testrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the deny-assert contract: which declared permissions are
 * unreachable states. The on-device half (the loud failure actually fails
 * the file and the run survives) is PermissionDenyOnDeviceTest.
 */
class PermissionDenyDecisionTest {

    @Test
    fun denyOfAGrantedPermissionIsTheOnlyViolation() {
        val granted = setOf("android.permission.CAMERA")
        val violations = permissionDenyViolations(
            mapOf(
                "camera" to "deny",       // granted -> violation
                "contacts" to "deny",     // not granted -> satisfied
                "microphone" to "allow",  // allow never violates
                "location" to "unset"     // unset never violates
            )
        ) { it in granted }
        assertEquals(listOf("camera" to "android.permission.CAMERA"), violations)
    }

    @Test
    fun grantedAllowAndUnsetNeverViolate() {
        val allGranted = { _: String -> true }
        assertTrue(permissionDenyViolations(mapOf("camera" to "allow"), allGranted).isEmpty())
        assertTrue(permissionDenyViolations(mapOf("camera" to "unset"), allGranted).isEmpty())
        assertTrue(permissionDenyViolations(null, allGranted).isEmpty())
        // An unknown cross-platform name maps to nothing and is skipped,
        // matching the apply loop's behaviour.
        assertTrue(permissionDenyViolations(mapOf("chronoscope" to "deny"), allGranted).isEmpty())
    }
}
