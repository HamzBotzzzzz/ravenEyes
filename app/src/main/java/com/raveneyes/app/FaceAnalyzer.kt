package com.raveneyes.app

import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

@ExperimentalGetImage
class FaceAnalyzer(
    private val onResult: (FaceSnapshot) -> Unit,
    private val onError: (Throwable) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
    )

    private var lastCount: Int = -1

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)

        detector.process(input)
            .addOnSuccessListener { faces ->
                val snapshot = buildSnapshot(faces)
                if (snapshot.faceCount != lastCount) {
                    lastCount = snapshot.faceCount
                    Log.i(TAG, "Face count: ${snapshot.faceCount}")
                }
                onResult(snapshot)
            }
            .addOnFailureListener { t ->
                Log.e(TAG, "Face detector error", t)
                onError(t)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun buildSnapshot(faces: List<Face>): FaceSnapshot {
        if (faces.size != 1) {
            return FaceSnapshot(
                faceCount = faces.size,
                leftEye = EyeState.UNKNOWN,
                rightEye = EyeState.UNKNOWN,
                leftProbability = null,
                rightProbability = null
            )
        }

        val face = faces[0]
        val leftProb = face.leftEyeOpenProbability
        val rightProb = face.rightEyeOpenProbability

        return FaceSnapshot(
            faceCount = 1,
            leftEye = classify(leftProb),
            rightEye = classify(rightProb),
            leftProbability = leftProb,
            rightProbability = rightProb
        )
    }

    private fun classify(probability: Float?): EyeState {
        if (probability == null) return EyeState.UNKNOWN
        return if (probability >= OPEN_THRESHOLD) EyeState.OPEN else EyeState.CLOSED
    }

    fun close() {
        detector.close()
    }

    data class FaceSnapshot(
        val faceCount: Int,
        val leftEye: EyeState,
        val rightEye: EyeState,
        val leftProbability: Float?,
        val rightProbability: Float?
    )

    enum class EyeState { OPEN, CLOSED, UNKNOWN }

    companion object {
        private const val TAG = "RavenEyes.Face"

        // Observational threshold only, NOT the final calibration threshold.
        // Calibration replaces this in APK 5.
        private const val OPEN_THRESHOLD = 0.5f
    }
}