package com.raveneyes.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.raveneyes.app.calibration.CalibrationController
import com.raveneyes.app.calibration.CalibrationStore
import com.raveneyes.app.databinding.ActivityCalibrationBinding
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var faceAnalyzer: FaceAnalyzer? = null
    private var blinkDetector: BlinkDetector? = null
    private val controller = CalibrationController()
    private lateinit var store: CalibrationStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = CalibrationStore(applicationContext)
        analysisExecutor = Executors.newSingleThreadExecutor()
        blinkDetector = BlinkDetector()

        binding.buttonCancel.setOnClickListener {
            controller.cancel()
            finish()
        }

        binding.buttonStart.setOnClickListener {
            binding.buttonStart.isEnabled = false
            controller.begin(System.currentTimeMillis())
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        faceAnalyzer?.close()
        analysisExecutor?.shutdown()
        faceAnalyzer = null
        analysisExecutor = null
        super.onDestroy()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val exec = analysisExecutor ?: return@addListener
                val detector = blinkDetector ?: return@addListener
                val analyzer = FaceAnalyzer(
                    onResult = { snapshot ->
                        val now = System.currentTimeMillis()
                        val out = detector.update(
                            leftState = snapshot.leftEye.toBlink(),
                            rightState = snapshot.rightEye.toBlink(),
                            faceCount = snapshot.faceCount,
                            nowMs = now
                        )
                        val status = controller.onFrame(
                            faceCount = snapshot.faceCount,
                            leftProb = snapshot.leftProbability,
                            rightProb = snapshot.rightProbability,
                            blinkOutput = out,
                            nowMs = now
                        )
                        runOnUiThread { render(status) }
                    },
                    onError = { t -> Log.e(TAG, "Analyzer error", t) }
                )
                faceAnalyzer = analyzer

                analysis.setAnalyzer(exec, analyzer)
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Camera error", t)
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun render(status: CalibrationController.Status) {
        binding.textStep.text = status.step.name
        binding.textMessage.text = status.message
        binding.progressBar.progress = (status.progress * 100).toInt()
        binding.textSamples.text = if (status.requiredSamples > 0)
            "${status.validSamples} / ${status.requiredSamples}" else ""

        if (status.reject != null) {
            binding.textReject.visibility = View.VISIBLE
            binding.textReject.text = when (status.reject) {
                CalibrationController.RejectReason.FACE_NOT_DETECTED -> "Face not detected."
                CalibrationController.RejectReason.MULTIPLE_FACES -> "Multiple faces detected."
                CalibrationController.RejectReason.EYES_NOT_VALID -> "Eyes not detected."
                CalibrationController.RejectReason.NOT_ENOUGH_SAMPLES -> "Not enough samples. Please repeat."
                CalibrationController.RejectReason.OUT_OF_RANGE -> "Out of range. Please repeat."
            }
        } else {
            binding.textReject.visibility = View.GONE
        }

if (status.step == CalibrationController.Step.COMPLETE) {
    val data = controller.buildDataOrNull()

    if (data == null) {
        Log.e(TAG, "buildDataOrNull() returned null")
        binding.textReject.visibility = View.VISIBLE
        binding.textReject.text = "Calibration data incomplete. Please repeat."
        binding.buttonStart.isEnabled = true
        return
    }

    if (!data.isValid()) {
        Log.e(TAG, "CalibrationData.isValid() = false")
        Log.e(TAG, "version=${data.calibrationVersion}")
        Log.e(TAG, "openThreshold=${data.openThreshold}, closeThreshold=${data.closeThreshold}")
        Log.e(TAG, "openEyeL=${data.openEyeLeft}, openEyeR=${data.openEyeRight}")
        Log.e(TAG, "closedEyeL=${data.closedEyeLeft}, closedEyeR=${data.closedEyeRight}")
        Log.e(TAG, "normalBlink=${data.normalBlinkDurationMs}, longClosure=${data.longClosureDurationMs}, interBlink=${data.interBlinkIntervalMs}")
        binding.textReject.visibility = View.VISIBLE
        binding.textReject.text = "Calibration data invalid. Please repeat."
        binding.buttonStart.isEnabled = true
        return
    }

    Log.i(TAG, "CalibrationData.isValid() = true, saving...")
    lifecycleScope.launch {
        try {
            store.save(data)
            Log.i(TAG, "Calibration saved OK")
            finish()
        } catch (t: Throwable) {
            Log.e(TAG, "Calibration save error", t)
            binding.textReject.visibility = View.VISIBLE
            binding.textReject.text = "Failed to save calibration. Please try again."
            binding.buttonStart.isEnabled = true
        }
    }
}
}
    private fun FaceAnalyzer.EyeState.toBlink(): BlinkDetector.EyeState = when (this) {
        FaceAnalyzer.EyeState.OPEN -> BlinkDetector.EyeState.OPEN
        FaceAnalyzer.EyeState.CLOSED -> BlinkDetector.EyeState.CLOSED
        FaceAnalyzer.EyeState.UNKNOWN -> BlinkDetector.EyeState.UNKNOWN
    }

    companion object {
        private const val TAG = "RavenEyes.CalibrationActivity"
    }
}
