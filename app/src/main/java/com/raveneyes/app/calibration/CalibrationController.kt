package com.raveneyes.app.calibration

import android.util.Log
import com.raveneyes.app.BlinkDetector
import kotlin.math.max

class CalibrationController {

    enum class Step {
        IDLE,
        PRECHECK,
        OPEN_EYES,
        BLINK,
        LONG_CLOSURE,
        DOUBLE_BLINK,
        VALIDATION,
        COMPLETE
    }

    enum class RejectReason {
        FACE_NOT_DETECTED,
        MULTIPLE_FACES,
        EYES_NOT_VALID,
        NOT_ENOUGH_SAMPLES,
        OUT_OF_RANGE
    }

    data class Status(
        val step: Step,
        val progress: Float,
        val message: String,
        val reject: RejectReason?,
        val validSamples: Int,
        val requiredSamples: Int
    )

    private val openEyeLeftSamples = ArrayList<Float>(64)
    private val openEyeRightSamples = ArrayList<Float>(64)
    private val closedEyeLeftSamples = ArrayList<Float>(8)
    private val closedEyeRightSamples = ArrayList<Float>(8)
    private val blinkDurations = ArrayList<Long>(4)
    private var longClosureMs: Long = 0L
    private var interBlinkMs: Long = 0L

    private var step: Step = Step.IDLE
    private var stepStartMs: Long = 0L
    private var lastBlinkSeen: Long = 0L
    private var lastSeenSequence: Int = 0
    private var lastLoggedStep: Step = Step.IDLE

    fun begin(nowMs: Long): Status {
        reset()
        step = Step.PRECHECK
        stepStartMs = nowMs
        return status(nowMs, "Preparing. Look at the camera.", null)
    }

    fun cancel() { reset() }

    fun onFrame(
        faceCount: Int,
        leftProb: Float?,
        rightProb: Float?,
        blinkOutput: BlinkDetector.Output,
        nowMs: Long
    ): Status {
        if (step == Step.IDLE || step == Step.COMPLETE) return status(nowMs, "", null)

        if (step != lastLoggedStep) {
            lastLoggedStep = step
            Log.i(TAG, "Step -> $step")
        }

        val faceValid = faceCount == 1
        val eyesValid = faceValid && leftProb != null && rightProb != null &&
                leftProb.isFinite() && rightProb.isFinite() &&
                leftProb in 0f..1f && rightProb in 0f..1f

        val reject: RejectReason? = when {
            faceCount == 0 -> RejectReason.FACE_NOT_DETECTED
            faceCount > 1 -> RejectReason.MULTIPLE_FACES
            !eyesValid -> RejectReason.EYES_NOT_VALID
            else -> null
        }

        when (step) {
            Step.PRECHECK -> {
                if (faceValid && eyesValid) {
                    if (nowMs - stepStartMs >= PRECHECK_MS) {
                        step = Step.OPEN_EYES
                        stepStartMs = nowMs
                    }
                } else {
                    stepStartMs = nowMs
                }
            }

            Step.OPEN_EYES -> {
                if (faceValid && eyesValid) {
                    openEyeLeftSamples.add(leftProb!!)
                    openEyeRightSamples.add(rightProb!!)
                }
                if (nowMs - stepStartMs >= OPEN_EYES_MS) {
                    if (openEyeLeftSamples.size >= MIN_OPEN_SAMPLES) {
                        step = Step.BLINK
                        stepStartMs = nowMs
                    } else {
                        openEyeLeftSamples.clear()
                        openEyeRightSamples.clear()
                        stepStartMs = nowMs
                        return status(nowMs, "Not enough samples. Keep your eyes open.", RejectReason.NOT_ENOUGH_SAMPLES)
                    }
                }
            }

            Step.BLINK -> {
                if (blinkOutput.lastEvent == BlinkDetector.Event.BLINK) {
                    val dur = blinkOutput.lastBlinkDurationMs ?: 0L
                    if (dur in 50L..800L) blinkDurations.add(dur)
                }
                if (blinkDurations.size >= 3) {
                    step = Step.LONG_CLOSURE
                    stepStartMs = nowMs
                } else if (nowMs - stepStartMs > STEP_TIMEOUT_MS) {
                    blinkDurations.clear()
                    stepStartMs = nowMs
                    return status(nowMs, "Please blink three times, naturally.", RejectReason.NOT_ENOUGH_SAMPLES)
                }
            }

            Step.LONG_CLOSURE -> {
                if (blinkOutput.blinkState == BlinkDetector.BlinkState.CLOSED && eyesValid) {
                    closedEyeLeftSamples.add(leftProb!!)
                    closedEyeRightSamples.add(rightProb!!)
                    if (closedEyeLeftSamples.size > 8) {
                        closedEyeLeftSamples.removeAt(0)
                        closedEyeRightSamples.removeAt(0)
                    }
                }
                if (blinkOutput.lastEvent == BlinkDetector.Event.LONG_CLOSURE) {
                    val closureDur = blinkOutput.lastBlinkDurationMs ?: 0L
                    if (closureDur in 700L..2500L) {
                        longClosureMs = closureDur
                        step = Step.DOUBLE_BLINK
                        stepStartMs = nowMs
                    } else {
                        stepStartMs = nowMs
                        return status(nowMs, "Close your eyes about 1 second, then open.", RejectReason.OUT_OF_RANGE)
                    }
                } else if (nowMs - stepStartMs > STEP_TIMEOUT_MS) {
                    stepStartMs = nowMs
                    return status(nowMs, "Close your eyes about 1 second, then open.", RejectReason.NOT_ENOUGH_SAMPLES)
                }
            }

            Step.DOUBLE_BLINK -> {
                val seq = blinkOutput.sequenceCount
                val prev = blinkOutput.previousBlinkAtMs
                val last = blinkOutput.lastBlinkAtMs

                if (seq != lastSeenSequence) {
                    lastSeenSequence = seq
                    Log.i(TAG, "Sequence advanced: count=$seq, last=$last, prev=$prev")
                }

                if (seq >= 2 && prev > 0L && last > prev) {
                    val interval = last - prev
                    if (interval in 100L..1500L) {
                        interBlinkMs = interval
                        Log.i(TAG, "Double blink accepted: interval=$interval ms, seq=$seq")
                        step = Step.VALIDATION
                        stepStartMs = nowMs
                        return status(nowMs, "Validating...", null)
                    } else {
                        Log.w(TAG, "Interval out of range: $interval ms")
                        return status(nowMs, "Blink twice, quickly. Try again.", RejectReason.OUT_OF_RANGE)
                    }
                }

                if (nowMs - stepStartMs > DOUBLE_BLINK_TIMEOUT_MS) {
                    stepStartMs = nowMs
                    lastSeenSequence = 0
                    Log.w(TAG, "Double blink timeout, retrying step")
                    return status(nowMs, "Double blink not detected. Please try again.", RejectReason.NOT_ENOUGH_SAMPLES)
                }
            }

            Step.VALIDATION -> {
                if (nowMs - stepStartMs >= VALIDATION_MS) {
                    step = Step.COMPLETE
                }
            }

            else -> {}
        }

        return status(nowMs, messageFor(step), reject)
    }

    fun buildDataOrNull(): CalibrationData? {
        if (step != Step.COMPLETE) return null
        if (blinkDurations.size < 3) return null
        if (longClosureMs <= 0) return null
        if (interBlinkMs <= 0) return null
        if (openEyeLeftSamples.isEmpty()) return null
        if (closedEyeLeftSamples.isEmpty()) return null

        val openL = median(openEyeLeftSamples)
        val openR = median(openEyeRightSamples)
        val closedL = median(closedEyeLeftSamples)
        val closedR = median(closedEyeRightSamples)

        if (openL == null || openR == null || closedL == null || closedR == null) return null
        if (openL <= closedL || openR <= closedR) return null

        val rawThreshold = (openL + closedL + openR + closedR) / 4f
        val openThreshold = rawThreshold.coerceIn(0.05f, 0.95f)
        val closeThreshold = openThreshold

        val medianBlink = medianLong(blinkDurations) ?: return null
        val normalBlinkMax = (medianBlink + 200L).coerceIn(400L, 700L)
        val longClosureThreshold = max(medianBlink * 4L, 800L).coerceAtMost(1500L)
        val doubleBlinkWindow = (interBlinkMs + 800L).coerceIn(1200L, 2500L)

        return CalibrationData(
            calibrationVersion = CalibrationData.CURRENT_VERSION,
            calibratedAt = System.currentTimeMillis(),
            openEyeLeft = openL,
            openEyeRight = openR,
            closedEyeLeft = closedL,
            closedEyeRight = closedR,
            openThreshold = openThreshold,
            closeThreshold = closeThreshold,
            normalBlinkDurationMs = medianBlink,
            normalBlinkMaxMs = normalBlinkMax,
            longClosureDurationMs = longClosureMs,
            longClosureThresholdMs = longClosureThreshold,
            interBlinkIntervalMs = interBlinkMs,
            doubleBlinkWindowMs = doubleBlinkWindow
        )
    }

    private fun status(nowMs: Long, message: String, reject: RejectReason?): Status {
        val (valid, req) = when (step) {
            Step.OPEN_EYES -> openEyeLeftSamples.size to MIN_OPEN_SAMPLES
            Step.BLINK -> blinkDurations.size to 3
            Step.LONG_CLOSURE -> (if (longClosureMs > 0) 1 else 0) to 1
            Step.DOUBLE_BLINK -> (if (interBlinkMs > 0) 1 else 0) to 1
            else -> 0 to 0
        }
        val progress = when (step) {
            Step.PRECHECK -> ((nowMs - stepStartMs).toFloat() / PRECHECK_MS).coerceIn(0f, 1f)
            Step.OPEN_EYES -> ((nowMs - stepStartMs).toFloat() / OPEN_EYES_MS).coerceIn(0f, 1f)
            Step.VALIDATION -> ((nowMs - stepStartMs).toFloat() / VALIDATION_MS).coerceIn(0f, 1f)
            else -> if (req == 0) 0f else (valid.toFloat() / req).coerceIn(0f, 1f)
        }
        return Status(step, progress, message, reject, valid, req)
    }

    private fun messageFor(step: Step): String = when (step) {
        Step.PRECHECK -> "Preparing. Look at the camera."
        Step.OPEN_EYES -> "Keep your eyes open normally."
        Step.BLINK -> "Blink normally three times."
        Step.LONG_CLOSURE -> "Close your eyes about 1 second, then open."
        Step.DOUBLE_BLINK -> "Blink twice, quickly."
        Step.VALIDATION -> "Validating..."
        Step.COMPLETE -> "Calibration complete."
        Step.IDLE -> ""
    }

    private fun reset() {
        step = Step.IDLE
        stepStartMs = 0L
        lastBlinkSeen = 0L
        lastSeenSequence = 0
        openEyeLeftSamples.clear()
        openEyeRightSamples.clear()
        closedEyeLeftSamples.clear()
        closedEyeRightSamples.clear()
        blinkDurations.clear()
        longClosureMs = 0L
        interBlinkMs = 0L
    }

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2]
        else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }

    private fun medianLong(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2]
        else (sorted[n / 2 - 1] + sorted[n / 2]) / 2L
    }

    companion object {
        private const val TAG = "RavenEyes.Calibration"

        const val PRECHECK_MS = 1000L
        const val OPEN_EYES_MS = 3000L
        const val VALIDATION_MS = 3000L
        const val STEP_TIMEOUT_MS = 30000L
        const val DOUBLE_BLINK_TIMEOUT_MS = 15000L
        const val MIN_OPEN_SAMPLES = 15
    }
}
