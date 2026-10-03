package com.raveneyes.app.calibration

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "raven_eyes_calibration")

class CalibrationStore(private val context: Context) {

    private object Keys {
        val VERSION = intPreferencesKey("calibration_version")
        val CALIBRATED_AT = longPreferencesKey("calibrated_at")
        val OPEN_EYE_L = floatPreferencesKey("open_eye_left")
        val OPEN_EYE_R = floatPreferencesKey("open_eye_right")
        val CLOSED_EYE_L = floatPreferencesKey("closed_eye_left")
        val CLOSED_EYE_R = floatPreferencesKey("closed_eye_right")
        val OPEN_THRESHOLD = floatPreferencesKey("open_threshold")
        val CLOSE_THRESHOLD = floatPreferencesKey("close_threshold")
        val NORMAL_BLINK_MS = longPreferencesKey("normal_blink_ms")
        val NORMAL_BLINK_MAX_MS = longPreferencesKey("normal_blink_max_ms")
        val LONG_CLOSURE_MS = longPreferencesKey("long_closure_ms")
        val LONG_CLOSURE_THRESHOLD_MS = longPreferencesKey("long_closure_threshold_ms")
        val INTER_BLINK_MS = longPreferencesKey("inter_blink_ms")
        val DOUBLE_BLINK_WINDOW_MS = longPreferencesKey("double_blink_window_ms")
    }

    val calibrationFlow: Flow<CalibrationData?> = context.dataStore.data.map { prefs ->
        val version = prefs[Keys.VERSION] ?: return@map null
        val calibratedAt = prefs[Keys.CALIBRATED_AT] ?: return@map null
        val openEyeLeft = prefs[Keys.OPEN_EYE_L] ?: return@map null
        val openEyeRight = prefs[Keys.OPEN_EYE_R] ?: return@map null
        val closedEyeLeft = prefs[Keys.CLOSED_EYE_L] ?: return@map null
        val closedEyeRight = prefs[Keys.CLOSED_EYE_R] ?: return@map null
        val openThreshold = prefs[Keys.OPEN_THRESHOLD] ?: return@map null
        val closeThreshold = prefs[Keys.CLOSE_THRESHOLD] ?: return@map null
        val normalBlinkMs = prefs[Keys.NORMAL_BLINK_MS] ?: return@map null
        val normalBlinkMaxMs = prefs[Keys.NORMAL_BLINK_MAX_MS] ?: return@map null
        val longClosureMs = prefs[Keys.LONG_CLOSURE_MS] ?: return@map null
        val longClosureThresholdMs = prefs[Keys.LONG_CLOSURE_THRESHOLD_MS] ?: return@map null
        val interBlinkMs = prefs[Keys.INTER_BLINK_MS] ?: return@map null
        val doubleBlinkWindowMs = prefs[Keys.DOUBLE_BLINK_WINDOW_MS] ?: return@map null

        val data = CalibrationData(
            calibrationVersion = version,
            calibratedAt = calibratedAt,
            openEyeLeft = openEyeLeft,
            openEyeRight = openEyeRight,
            closedEyeLeft = closedEyeLeft,
            closedEyeRight = closedEyeRight,
            openThreshold = openThreshold,
            closeThreshold = closeThreshold,
            normalBlinkDurationMs = normalBlinkMs,
            normalBlinkMaxMs = normalBlinkMaxMs,
            longClosureDurationMs = longClosureMs,
            longClosureThresholdMs = longClosureThresholdMs,
            interBlinkIntervalMs = interBlinkMs,
            doubleBlinkWindowMs = doubleBlinkWindowMs
        )
        if (data.isValid()) data else null
    }

    suspend fun save(data: CalibrationData) {
        require(data.isValid()) { "Refusing to save invalid calibration" }
        context.dataStore.edit { prefs ->
            prefs[Keys.VERSION] = data.calibrationVersion
            prefs[Keys.CALIBRATED_AT] = data.calibratedAt
            prefs[Keys.OPEN_EYE_L] = data.openEyeLeft
            prefs[Keys.OPEN_EYE_R] = data.openEyeRight
            prefs[Keys.CLOSED_EYE_L] = data.closedEyeLeft
            prefs[Keys.CLOSED_EYE_R] = data.closedEyeRight
            prefs[Keys.OPEN_THRESHOLD] = data.openThreshold
            prefs[Keys.CLOSE_THRESHOLD] = data.closeThreshold
            prefs[Keys.NORMAL_BLINK_MS] = data.normalBlinkDurationMs
            prefs[Keys.NORMAL_BLINK_MAX_MS] = data.normalBlinkMaxMs
            prefs[Keys.LONG_CLOSURE_MS] = data.longClosureDurationMs
            prefs[Keys.LONG_CLOSURE_THRESHOLD_MS] = data.longClosureThresholdMs
            prefs[Keys.INTER_BLINK_MS] = data.interBlinkIntervalMs
            prefs[Keys.DOUBLE_BLINK_WINDOW_MS] = data.doubleBlinkWindowMs
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}