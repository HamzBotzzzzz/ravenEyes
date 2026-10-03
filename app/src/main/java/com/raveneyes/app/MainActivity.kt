package com.raveneyes.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import android.provider.Settings
import com.raveneyes.app.accessibility.AccessibilityStatus
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.raveneyes.app.calibration.CalibrationStore
import com.raveneyes.app.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var analysisExecutor: ExecutorService? = null
    private var faceAnalyzer: FaceAnalyzer? = null
    private lateinit var blinkDetector: BlinkDetector
    private lateinit var calibrationStore: CalibrationStore

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.i(TAG, "Camera permission granted")
            showState(State.GRANTED)
            startCamera()
        } else {
            Log.w(TAG, "Camera permission denied")
            val canAskAgain = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            showState(if (canAskAgain) State.DENIED else State.PERMANENTLY_DENIED)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        analysisExecutor = Executors.newSingleThreadExecutor()
        blinkDetector = BlinkDetector()
        calibrationStore = CalibrationStore(applicationContext)

        faceAnalyzer = FaceAnalyzer(
            onResult = { snapshot ->
                val now = System.currentTimeMillis()
                val left = snapshot.leftEye.toBlinkEye()
                val right = snapshot.rightEye.toBlinkEye()
                val output = blinkDetector.update(
                    leftState = left,
                    rightState = right,
                    faceCount = snapshot.faceCount,
                    nowMs = now
                )
                runOnUiThread {
                    updateFaceAndEyeStatus(snapshot)
                    updateBlinkStatus(output)
                }
            },
            onError = { runOnUiThread {
                updateFaceError()
                updateBlinkStatus(blinkDetector.update(
                    leftState = BlinkDetector.EyeState.UNKNOWN,
                    rightState = BlinkDetector.EyeState.UNKNOWN,
                    faceCount = 0,
                    nowMs = System.currentTimeMillis()
                ))
            } }
        )

        binding.buttonResetBlink.setOnClickListener {
            blinkDetector.reset()
            updateBlinkStatus(blinkDetector.update(
                leftState = BlinkDetector.EyeState.UNKNOWN,
                rightState = BlinkDetector.EyeState.UNKNOWN,
                faceCount = 0,
                nowMs = System.currentTimeMillis()
            ))
        }

        binding.buttonCalibration.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        binding.buttonResetCalibration.setOnClickListener {
            lifecycleScope.launch {
                calibrationStore.clear()
                Log.i(TAG, "Calibration cleared")
            }
        }
        
        binding.buttonAccessibility.setOnClickListener {
    if (AccessibilityStatus.isServiceEnabled(this)) {
        // already enabled: buka Settings juga (opsional, sesuai instruksi tetap bisa buka)
        openAccessibilitySettings()
    } else {
        openAccessibilitySettings()
    }
}

refreshAccessibilityStatus()

        binding.buttonCameraAction.setOnClickListener {
            when (currentState) {
                State.NOT_REQUESTED, State.DENIED ->
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                State.PERMANENTLY_DENIED -> openAppSettings()
                State.GRANTED -> startCamera()
                State.ERROR -> startCamera()
            }
        }

        observeCalibration()
        evaluateInitialState()
    }

    override fun onResume() {
    super.onResume()
    refreshAccessibilityStatus()
    if (currentState == State.GRANTED) {
        startCamera()
    } else {
        evaluateInitialState()
    }
}

    override fun onStop() {
        super.onStop()
        blinkDetector.onFaceLost()
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        faceAnalyzer?.close()
        faceAnalyzer = null
        cameraExecutor?.shutdown()
        analysisExecutor?.shutdown()
        cameraExecutor = null
        analysisExecutor = null
        super.onDestroy()
    }

    private fun observeCalibration() {
        lifecycleScope.launch {
            calibrationStore.calibrationFlow.collectLatest { data ->
                if (data != null && data.isValid()) {
                    binding.textCalibrationStatus.text = getString(R.string.calibration_status_ready)
                } else {
                    binding.textCalibrationStatus.text = getString(R.string.calibration_status_required)
                }
            }
        }
    }

    private fun evaluateInitialState() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            showState(State.GRANTED)
            startCamera()
        } else {
            showState(State.NOT_REQUESTED)
        }
    }

    private fun openAppSettings() {
        val intent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", packageName, null)
        )
        startActivity(intent)
    }

    private fun startCamera() {
        Log.i(TAG, "Camera starting")
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
                    .also { ia ->
                        val exec = analysisExecutor
                        val analyzer = faceAnalyzer
                        if (exec != null && analyzer != null) {
                            ia.setAnalyzer(exec, analyzer)
                        }
                    }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )

                Log.i(TAG, "Camera started")
                showState(State.GRANTED)
            } catch (t: Throwable) {
                Log.e(TAG, "Camera error", t)
                showState(State.ERROR)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateFaceAndEyeStatus(snapshot: FaceAnalyzer.FaceSnapshot) {
        val count = snapshot.faceCount
        when {
            count == 0 -> {
                binding.textFaceStatus.text = getString(R.string.face_status_not_detected)
                binding.textFaceCount.text = getString(R.string.face_count_format, 0)
            }
            count == 1 -> {
                binding.textFaceStatus.text = getString(R.string.face_status_detected)
                binding.textFaceCount.text = getString(R.string.face_count_format, 1)
            }
            else -> {
                binding.textFaceStatus.text = getString(R.string.face_status_multiple)
                binding.textFaceCount.text = getString(R.string.face_count_format, count)
            }
        }

        when {
            count == 0 -> {
                binding.textLeftEye.text = getString(R.string.eye_label_unknown)
                binding.textRightEye.text = getString(R.string.eye_label_unknown)
                binding.textLeftProb.text = ""
                binding.textRightProb.text = ""
            }
            count >= 2 -> {
                binding.textLeftEye.text = getString(R.string.eye_label_multiple)
                binding.textRightEye.text = ""
                binding.textLeftProb.text = ""
                binding.textRightProb.text = ""
            }
            else -> {
                binding.textLeftEye.text = formatEye(getString(R.string.eye_label_left), snapshot.leftEye)
                binding.textRightEye.text = formatEye(getString(R.string.eye_label_right), snapshot.rightEye)
                binding.textLeftProb.text = formatProb(
                    getString(R.string.eye_prob_left_format),
                    snapshot.leftProbability
                )
                binding.textRightProb.text = formatProb(
                    getString(R.string.eye_prob_right_format),
                    snapshot.rightProbability
                )
            }
        }
    }

    private fun updateBlinkStatus(output: BlinkDetector.Output) {
        val stateText = when (output.blinkState) {
            BlinkDetector.BlinkState.OPEN -> getString(R.string.blink_state_open)
            BlinkDetector.BlinkState.CLOSED -> getString(R.string.blink_state_closed)
            BlinkDetector.BlinkState.UNKNOWN -> getString(R.string.blink_state_unknown)
        }
        binding.textBlinkState.text = getString(R.string.blink_state_format, stateText)
        binding.textBlinkCount.text = getString(R.string.blink_count_format, output.blinkCount)

        binding.textLastBlink.text = output.lastBlinkDurationMs?.let {
            getString(R.string.blink_last_format, it)
        } ?: ""

        binding.textClosureOrSequence.text = when {
            output.currentClosureMs != null ->
                getString(R.string.blink_current_closure_format, output.currentClosureMs)
            output.sequenceCount > 0 ->
                getString(R.string.blink_sequence_format, output.sequenceCount)
            else -> ""
        }
    }

    private fun formatEye(label: String, state: FaceAnalyzer.EyeState): String {
        val stateText = when (state) {
            FaceAnalyzer.EyeState.OPEN -> getString(R.string.eye_state_open)
            FaceAnalyzer.EyeState.CLOSED -> getString(R.string.eye_state_closed)
            FaceAnalyzer.EyeState.UNKNOWN -> getString(R.string.eye_state_unknown)
        }
        return "$label: $stateText"
    }

    private fun formatProb(format: String, value: Float?): String {
        return if (value == null) "" else String.format(format, value)
    }

    private fun updateFaceError() {
        binding.textFaceStatus.text = getString(R.string.face_status_error)
        binding.textFaceCount.text = ""
        binding.textLeftEye.text = getString(R.string.eye_label_unknown)
        binding.textRightEye.text = getString(R.string.eye_label_unknown)
        binding.textLeftProb.text = ""
        binding.textRightProb.text = ""
    }

    private fun resetEyeViews() {
        binding.textLeftEye.text = getString(R.string.eye_label_unknown)
        binding.textRightEye.text = getString(R.string.eye_label_unknown)
        binding.textLeftProb.text = ""
        binding.textRightProb.text = ""
    }

    private fun showState(state: State) {
        currentState = state
        binding.buttonCameraAction.visibility = View.VISIBLE

        when (state) {
            State.NOT_REQUESTED -> {
                binding.textCameraStatus.text = getString(R.string.camera_status_off)
                binding.textCameraMessage.text = getString(R.string.camera_permission_required)
                binding.buttonCameraAction.text = getString(R.string.camera_action_enable)
                binding.textFaceStatus.text = getString(R.string.face_status_waiting)
                binding.textFaceCount.text = ""
                resetEyeViews()
            }
            State.GRANTED -> {
                binding.textCameraStatus.text = getString(R.string.camera_status_ready)
                binding.textCameraMessage.text = getString(R.string.camera_message_preview_active)
                binding.buttonCameraAction.text = getString(R.string.camera_action_restart)
                binding.textFaceStatus.text = getString(R.string.face_status_waiting)
            }
            State.DENIED -> {
                binding.textCameraStatus.text = getString(R.string.camera_status_off)
                binding.textCameraMessage.text = getString(R.string.camera_permission_denied)
                binding.buttonCameraAction.text = getString(R.string.camera_action_try_again)
                binding.textFaceStatus.text = getString(R.string.face_status_waiting)
                binding.textFaceCount.text = ""
                resetEyeViews()
            }
            State.PERMANENTLY_DENIED -> {
                binding.textCameraStatus.text = getString(R.string.camera_status_off)
                binding.textCameraMessage.text = getString(R.string.camera_permission_disabled)
                binding.buttonCameraAction.text = getString(R.string.camera_action_open_settings)
                binding.textFaceStatus.text = getString(R.string.face_status_waiting)
                binding.textFaceCount.text = ""
                resetEyeViews()
            }
            State.ERROR -> {
                binding.textCameraStatus.text = getString(R.string.camera_status_error)
                binding.textCameraMessage.text = getString(R.string.camera_message_error)
                binding.buttonCameraAction.text = getString(R.string.camera_action_try_again)
                binding.textFaceStatus.text = getString(R.string.face_status_error)
                binding.textFaceCount.text = ""
                resetEyeViews()
            }
        }
    }

    private fun FaceAnalyzer.EyeState.toBlinkEye(): BlinkDetector.EyeState = when (this) {
        FaceAnalyzer.EyeState.OPEN -> BlinkDetector.EyeState.OPEN
        FaceAnalyzer.EyeState.CLOSED -> BlinkDetector.EyeState.CLOSED
        FaceAnalyzer.EyeState.UNKNOWN -> BlinkDetector.EyeState.UNKNOWN
    }
    
    private fun openAccessibilitySettings() {
    try {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to open accessibility settings", t)
    }
}

private fun refreshAccessibilityStatus() {
    val enabled = AccessibilityStatus.isServiceEnabled(this)
    if (enabled) {
        binding.textAccessibilityStatus.text =
            getString(R.string.accessibility_status_enabled)
        binding.buttonAccessibility.text =
            getString(R.string.accessibility_action_open_settings)
        binding.textAccessibilityHint.visibility = View.GONE
    } else {
        binding.textAccessibilityStatus.text =
            getString(R.string.accessibility_status_disabled)
        binding.buttonAccessibility.text =
            getString(R.string.accessibility_action_enable)
        binding.textAccessibilityHint.visibility = View.VISIBLE
    }
    Log.i(TAG, "Accessibility enabled=$enabled")
}

    private enum class State {
        NOT_REQUESTED,
        GRANTED,
        DENIED,
        PERMANENTLY_DENIED,
        ERROR
    }

    private var currentState: State = State.NOT_REQUESTED

    companion object {
        private const val TAG = "RavenEyes.Camera"
    }
}
