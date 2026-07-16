package com.jsonui.testrunner.runner

import android.app.UiAutomation
import android.os.ParcelFileDescriptor

/**
 * Minimal shell-uid command execution via [UiAutomation.executeShellCommand].
 * No shell operators (pipes/redirects/quoting) — one plain argv string per
 * call, output drained to EOF so the command is guaranteed to complete.
 */
internal object Shell {
    fun exec(automation: UiAutomation, command: String): String {
        val pfd = automation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            return input.readBytes().toString(Charsets.UTF_8)
        }
    }
}
