package com.raveneyes.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class RavenAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // APK 6: no automation. Do not process events.
    }

    override fun onInterrupt() {
        Log.i(TAG, "Service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.i(TAG, "Service unbound")
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "RavenEyes.Accessibility"
        const val SERVICE_COMPONENT =
            "com.raveneyes.app/com.raveneyes.app.accessibility.RavenAccessibilityService"
    }
}