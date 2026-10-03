package com.raveneyes.app

import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

@ExperimentalGetImage
class FaceAnalyzer(
    private val onResult: (faceCount: Int) -> Unit,
    private val onError: (Throwable) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
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
                val count = faces.size
                if (count != lastCount) {
                    lastCount = count
                    if (count == 0) {
                        Log.i(TAG, "No face")
                    } else {
                        Log.i(TAG, "Face count: $count")
                    }
                }
                onResult(count)
            }
            .addOnFailureListener { t ->
                Log.e(TAG, "Face detector error", t)
                onError(t)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    fun close() {
        detector.close()
    }

    companion object {
        private const val TAG = "RavenEyes.Face"
    }
}