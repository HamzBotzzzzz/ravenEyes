package com.raveneyes.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.raveneyes.app.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null

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
            if (canAskAgain) {
                showState(State.DENIED)
            } else {
                showState(State.PERMANENTLY_DENIED)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.buttonCameraAction.setOnClickListener {
            when (currentState) {
                State.NOT_REQUESTED, State.DENIED ->
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                State.PERMANENTLY_DENIED ->
                    openAppSettings()
                State.GRANTED ->
                    startCamera()
            }
        }

        evaluateInitialState()
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate when returning from Settings or background
        if (currentState == State.GRANTED) {
            startCamera()
        } else {
            evaluateInitialState()
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        cameraExecutor?.shutdown()
        cameraExecutor = null
        super.onDestroy()
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
        val intent = android.content.Intent(
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

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview
                )

                Log.i(TAG, "Camera started")
                showState(State.GRANTED)
            } catch (t: Throwable) {
                Log.e(TAG, "Camera error", t)
                showState(State.ERROR)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showState(state: State) {
        currentState = state
        binding.buttonCameraAction.visibility = View.VISIBLE

        when (state) {
            State.NOT_REQUESTED -> {
                binding.textCameraStatus.text = getString(R.string.camera_status_off)
                binding.textCameraMessage.text = getString(R.string.camera_permission_required)
                binding.buttonCameraAction.text = getString(R.string.camera_action_enable)
            }
            State.GRANTED -> {
                binding.textCameraStatus.text = getString(R.string.camera_status_ready)
                binding.textCameraMessage.text = getString(R.string.camera_message_preview_active)
                binding.buttonCameraAction.text = getString(R.string.camera_action_restart)
            }
            State.DENIED -> {
                binding.textCameraStatus.text = getString(R.string.camera_status_off)
                binding.textCameraMessage.text = getString(R.string.camera_permission_denied)
                binding.buttonCameraAction.text = getString(R.string.camera_action_try_again)
            }
            State.PERMANENTLY_DENIED -> {
                binding.textCameraStatus.text = getString(R.string.camera_status_off)
                binding.textCameraMessage.text = getString(R.string.camera_permission_disabled)
                binding.buttonCameraAction.text = getString(R.string.camera_action_open_settings)
            }
            State.ERROR -> {
                binding.textCameraStatus.text = getString(R.string.camera_status_error)
                binding.textCameraMessage.text = getString(R.string.camera_message_error)
                binding.buttonCameraAction.text = getString(R.string.camera_action_try_again)
            }
        }
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