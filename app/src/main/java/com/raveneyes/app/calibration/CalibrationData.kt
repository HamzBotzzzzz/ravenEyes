package com.raveneyes.app.calibration

data class CalibrationData(
    val calibrationVersion: Int,
    val calibratedAt: Long,
    val openEyeLeft: Float,
    val openEyeRight: Float,
    val closedEyeLeft: Float,
    val closedEyeRight: Float,
    val openThreshold: Float,
    val closeThreshold: Float,
    val normalBlinkDurationMs: Long,
    val normalBlinkMaxMs: Long,
    val longClosureDurationMs: Long,
    val longClosureThresholdMs: Long,
    val interBlinkIntervalMs: Long,
    val doubleBlinkWindowMs: Long
) {
    fun isValid(): Boolean {
        if (calibrationVersion != CURRENT_VERSION) return false
        if (!openEyeLeft.isFinite() || openEyeLeft !in 0f..1f) return false
        if (!openEyeRight.isFinite() || openEyeRight !in 0f..1f) return false
        if (!closedEyeLeft.isFinite() || closedEyeLeft !in 0f..1f) return false
        if (!closedEyeRight.isFinite() || closedEyeRight !in 0f..1f) return false
        if (!openThreshold.isFinite() || openThreshold !in 0f..1f) return false
        if (!closeThreshold.isFinite() || closeThreshold !in 0f..1f) return false
        if (openThreshold <= closeThreshold) return false
        if (normalBlinkDurationMs <= 0) return false
        if (normalBlinkMaxMs <= normalBlinkDurationMs) return false
        if (longClosureDurationMs <= 0) return false
        if (longClosureThresholdMs <= 0) return false
        if (interBlinkIntervalMs <= 0) return false
        if (doubleBlinkWindowMs <= 0) return false
        return true
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}