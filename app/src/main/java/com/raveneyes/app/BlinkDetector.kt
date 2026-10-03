package com.raveneyes.app

import android.util.Log

class BlinkDetector {

    enum class EyeState { OPEN, CLOSED, UNKNOWN }

    enum class BlinkState { OPEN, CLOSED, UNKNOWN }

    data class Output(
    val blinkState: BlinkState,
    val blinkCount: Int,
    val lastBlinkDurationMs: Long?,
    val currentClosureMs: Long?,
    val sequenceCount: Int,
    val lastEvent: Event?,
    val lastBlinkAtMs: Long,
    val previousBlinkAtMs: Long
)

    enum class Event {
        BLINK,
        DOUBLE_BLINK,
        TRIPLE_BLINK,
        LONG_CLOSURE
    }

    private var state: BlinkState = BlinkState.UNKNOWN
    private var closedSinceMs: Long = 0L
    private var lastTransitionToOpenMs: Long = 0L

    private var blinkCount: Int = 0
    private var lastBlinkDurationMs: Long? = null

    private var sequenceCount: Int = 0
    private var lastBlinkAtMs: Long = 0L

    fun reset() {
        state = BlinkState.UNKNOWN
        closedSinceMs = 0L
        lastTransitionToOpenMs = 0L
        blinkCount = 0
        lastBlinkDurationMs = null
        sequenceCount = 0
        lastBlinkAtMs = 0L
    }

    fun onFaceLost() {
        state = BlinkState.UNKNOWN
        closedSinceMs = 0L
    }

    /**
     * @param leftState  hasil klasifikasi ML Kit untuk mata kiri
     * @param rightState hasil klasifikasi ML Kit untuk mata kanan
     * @param faceCount  jumlah wajah di frame
     * @param nowMs      System.currentTimeMillis()
     */
    fun update(
        leftState: EyeState,
        rightState: EyeState,
        faceCount: Int,
        nowMs: Long
    ): Output {
        // Reset semua bila wajah hilang / multiple / data mata tidak jelas
        if (faceCount != 1 || leftState == EyeState.UNKNOWN || rightState == EyeState.UNKNOWN) {
            state = BlinkState.UNKNOWN
            closedSinceMs = 0L
            return output()
        }

        // Wajah ada satu, data mata valid
        val bothClosed = leftState == EyeState.CLOSED && rightState == EyeState.CLOSED
        val bothOpen = leftState == EyeState.OPEN && rightState == EyeState.OPEN
        val asymmetric = !bothClosed && !bothOpen

        // Wink / asymmetric: jangan memicu state machine
        if (asymmetric) {
            // tetap di state sekarang, tapi jangan update timer
            return output()
        }

        var event: Event? = null

        when (state) {
            BlinkState.UNKNOWN, BlinkState.OPEN -> {
                if (bothClosed) {
                    // mulai kandidat closed
                    if (closedSinceMs == 0L) {
                        closedSinceMs = nowMs
                    }
                    val sinceOpen = nowMs - lastTransitionToOpenMs
                    if (sinceOpen >= MIN_OPEN_MS || lastTransitionToOpenMs == 0L) {
                        val candidateClosedDuration = nowMs - closedSinceMs
                        if (candidateClosedDuration >= MIN_CLOSED_MS) {
                            state = BlinkState.CLOSED
                        }
                    }
                } else {
                    // bothOpen
                    closedSinceMs = 0L
                    if (state != BlinkState.OPEN) {
                        state = BlinkState.OPEN
                        lastTransitionToOpenMs = nowMs
                    }
                }
            }

            BlinkState.CLOSED -> {
                if (bothClosed) {
                    // tetap tertutup, tidak ada event
                } else {
                    // transisi ke OPEN
                    val durationMs = nowMs - closedSinceMs
                    closedSinceMs = 0L
                    state = BlinkState.OPEN
                    lastTransitionToOpenMs = nowMs

                    when {
                        durationMs in MIN_CLOSED_MS..MAX_BLINK_MS -> {
    blinkCount++
    lastBlinkDurationMs = durationMs
    previousBlinkAtMs = lastBlinkAtMs
    lastBlinkAtMs = nowMs
    
                            if (lastBlinkAtMs == 0L || nowMs - lastBlinkAtMs > SEQUENCE_WINDOW_MS) {
                                sequenceCount = 1
                            } else {
                                sequenceCount++
                            }
                            lastBlinkAtMs = nowMs

                            event = when (sequenceCount) {
                                1 -> Event.BLINK
                                2 -> Event.DOUBLE_BLINK
                                else -> Event.TRIPLE_BLINK
                            }

                            if (event == Event.DOUBLE_BLINK) {
                                Log.i(TAG, "Double Blink detected")
                            } else if (event == Event.TRIPLE_BLINK) {
                                Log.i(TAG, "Triple Blink detected")
                            } else {
                                Log.i(TAG, "Blink detected, duration=$durationMs ms")
                            }
                        }
                        durationMs >= LONG_CLOSURE_MS -> {
    event = Event.LONG_CLOSURE
    lastBlinkDurationMs = durationMs     // <— tambahan
    Log.i(TAG, "Long Closure detected, duration=$durationMs ms")
    sequenceCount = 0
    lastBlinkAtMs = 0L
}
                        // di antara MAX_BLINK_MS dan LONG_CLOSURE_MS: diabaikan
                    }
                }
            }
        }

        // reset sequence jika terlalu lama
        if (lastBlinkAtMs > 0L && nowMs - lastBlinkAtMs > SEQUENCE_RESET_MS) {
            sequenceCount = 0
            lastBlinkAtMs = 0L
        }

        return output(event)
    }

    private fun output(event: Event? = null): Output {
        val currentClosure = if (state == BlinkState.CLOSED && closedSinceMs > 0L) {
            System.currentTimeMillis() - closedSinceMs
        } else null

        return Output(
            blinkState = state,
            blinkCount = blinkCount,
            lastBlinkDurationMs = lastBlinkDurationMs,
            currentClosureMs = currentClosure,
            sequenceCount = sequenceCount,
            lastEvent = event
        )
    }

    companion object {
        private const val TAG = "RavenEyes.Blink"

        // Heuristic awal, BUKAN threshold final.
        // Akan diganti oleh calibration di APK 5.
        const val MIN_CLOSED_MS = 80L
        const val MAX_BLINK_MS = 500L
        const val MIN_OPEN_MS = 60L
        const val LONG_CLOSURE_MS = 800L
        const val SEQUENCE_WINDOW_MS = 2000L
        const val SEQUENCE_RESET_MS = 2000L
    }
}