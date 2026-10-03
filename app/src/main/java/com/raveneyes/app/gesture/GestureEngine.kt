package com.raveneyes.app.gesture

import android.util.Log
import com.raveneyes.app.BlinkDetector

class GestureEngine {

    data class Result(
        val action: GestureAction,
        val lastAction: GestureAction,
        val lastActionAtMs: Long?,
        val cooldown: Boolean,
        val reason: String
    )

    private var lastHandledSequence: Int = 0
    private var sequenceExpired: Boolean = true

    private var longClosureHandled: Boolean = false

    private var lastAction: GestureAction = GestureAction.NONE
    private var lastActionAtMs: Long = 0L
    private var scrollLastAtMs: Long = 0L
    private var likeLastAtMs: Long = 0L

    fun reset() {
        lastHandledSequence = 0
        sequenceExpired = true
        longClosureHandled = false
        lastAction = GestureAction.NONE
        lastActionAtMs = 0L
        scrollLastAtMs = 0L
        likeLastAtMs = 0L
    }

    /** Dipanggil saat Activity pause/stop untuk reset gesture state. */
    fun onLifecyclePause() {
        sequenceExpired = true
        longClosureHandled = false
    }

    fun process(
        output: BlinkDetector.Output,
        calibrationReady: Boolean,
        nowMs: Long
    ): Result {
        var action = GestureAction.NONE
        var reason = ""

        val seq = output.sequenceCount
        val blinkState = output.blinkState

        // ---- 1. Handle sequence (SCROLL) ----
        // Kita hanya peduli sequence yang masih "fresh" — artinya
        // previousBlinkAtMs dan lastBlinkAtMs masih ada di BlinkDetector.
        val hasFreshSequence = seq >= 2 &&
                output.previousBlinkAtMs > 0L &&
                output.lastBlinkAtMs > output.previousBlinkAtMs

        if (hasFreshSequence) {
            if (seq > lastHandledSequence) {
                // sequence baru yang belum diproses
                if (!calibrationReady) {
                    lastHandledSequence = seq
                    reason = "CALIBRATION_NOT_READY"
                    Log.i(TAG, "Ignored=SCROLL reason=CALIBRATION_NOT_READY sequence=$seq")
                } else if (inGlobalCooldown(nowMs) || inScrollCooldown(nowMs)) {
                    lastHandledSequence = seq
                    reason = "COOLDOWN"
                    Log.i(TAG, "Ignored=SCROLL reason=COOLDOWN sequence=$seq")
                } else {
                    action = GestureAction.SCROLL
                    lastHandledSequence = seq
                    lastAction = GestureAction.SCROLL
                    lastActionAtMs = nowMs
                    scrollLastAtMs = nowMs
                    reason = "DOUBLE_OR_TRIPLE_BLINK"
                    Log.i(TAG, "Action=SCROLL reason=$reason sequence=$seq")
                }
            }
        } else {
            // Tidak ada sequence fresh → reset penanda
            if (seq == 0 || seq == 1) {
                lastHandledSequence = 0
            }
        }

        // ---- 2. Handle long closure (LIKE) ----
        val longClosureActive = (blinkState == BlinkDetector.BlinkState.CLOSED) ||
                (output.lastEvent == BlinkDetector.Event.LONG_CLOSURE)

        if (output.lastEvent == BlinkDetector.Event.LONG_CLOSURE) {
            if (!longClosureHandled) {
                longClosureHandled = true
                if (!calibrationReady) {
                    if (action == GestureAction.NONE) reason = "CALIBRATION_NOT_READY"
                    Log.i(TAG, "Ignored=LIKE reason=CALIBRATION_NOT_READY")
                } else if (inGlobalCooldown(nowMs) || inLikeCooldown(nowMs)) {
                    if (action == GestureAction.NONE) reason = "COOLDOWN"
                    Log.i(TAG, "Ignored=LIKE reason=COOLDOWN")
                } else {
                    action = GestureAction.LIKE
                    lastAction = GestureAction.LIKE
                    lastActionAtMs = nowMs
                    likeLastAtMs = nowMs
                    reason = "LONG_CLOSURE"
                    val dur = output.lastBlinkDurationMs ?: output.currentClosureMs ?: 0L
                    Log.i(TAG, "Action=LIKE reason=$reason duration=${dur}ms")
                }
            }
        }

        // Re-arm LIKE saat user benar-benar membuka mata
        if (blinkState == BlinkDetector.BlinkState.OPEN &&
            output.lastEvent != BlinkDetector.Event.LONG_CLOSURE) {
            longClosureHandled = false
        }

        // Jika tidak ada event yang baru diproses, tetap kembalikan last action state
        val cooldown = inGlobalCooldown(nowMs) || inScrollCooldown(nowMs) || inLikeCooldown(nowMs)

        return Result(
            action = action,
            lastAction = lastAction,
            lastActionAtMs = if (lastActionAtMs > 0L) lastActionAtMs else null,
            cooldown = cooldown,
            reason = reason
        )
    }

    fun triggerTestScroll(nowMs: Long) {
        lastAction = GestureAction.SCROLL
        lastActionAtMs = nowMs
        scrollLastAtMs = nowMs
        Log.i(TAG, "Action=SCROLL reason=TEST")
    }

    fun triggerTestLike(nowMs: Long) {
        lastAction = GestureAction.LIKE
        lastActionAtMs = nowMs
        likeLastAtMs = nowMs
        Log.i(TAG, "Action=LIKE reason=TEST")
    }

    private fun inScrollCooldown(nowMs: Long): Boolean =
        scrollLastAtMs > 0L && nowMs - scrollLastAtMs < SCROLL_COOLDOWN_MS

    private fun inLikeCooldown(nowMs: Long): Boolean =
        likeLastAtMs > 0L && nowMs - likeLastAtMs < LIKE_COOLDOWN_MS

    private fun inGlobalCooldown(nowMs: Long): Boolean =
        lastActionAtMs > 0L && nowMs - lastActionAtMs < GLOBAL_COOLDOWN_MS

    companion object {
        private const val TAG = "RavenEyes.Gesture"

        const val SCROLL_COOLDOWN_MS = 1000L
        const val LIKE_COOLDOWN_MS = 1500L
        const val GLOBAL_COOLDOWN_MS = 400L
    }
}