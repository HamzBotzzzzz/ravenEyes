package com.raveneyes.app.accessibility

import android.content.Context
import android.provider.Settings

object AccessibilityStatus {

    fun isServiceEnabled(context: Context): Boolean {
        val expected = RavenAccessibilityService.SERVICE_COMPONENT
        val enabled = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (t: Throwable) {
            null
        } ?: return false

        // Format: "pkg/cls:pkg/cls:..." — cek exact match, bukan contains substring
        // yang bisa salah cocokkan nama paket lain.
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}