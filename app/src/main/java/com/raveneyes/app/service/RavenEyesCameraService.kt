package com.raveneyes.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.raveneyes.app.BlinkDetector
import com.raveneyes.app.FaceAnalyzer
import com.raveneyes.app.R
import com.raveneyes.app.calibration.CalibrationStore
import com.raveneyes.app.gesture.GestureEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RavenEyesCameraService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var faceAnalyzer: FaceAnalyzer? = null
    private var blinkDetector: BlinkDetector? = null
    private var gestureEngine: GestureEngine? = null
    private var calibrationReady: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _status = MutableStateFlow(ServiceStatus(ServiceState.STOPPED))
    val status: StateFlow<ServiceStatus> = _status.asStateFlow()

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): RavenEyesCameraService = this@RavenEyesCameraService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service onStartCommand")

        val cameraGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!cameraGranted) {
            Log.e(TAG, "Camera permission missing; stopping service")
            _status.value = _status.value.copy(
                state = ServiceState.ERROR,
                errorMessage = "Camera permission required"
            )
            stopSelf()
            return START_NOT_STICKY
        }

        _status.value = _status.value.copy(state = ServiceState.STARTING)

        try {
            startForegroundInternal()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to startForeground", t)
            _status.value = _status.value.copy(
                state = ServiceState.ERROR,
                errorMessage = "Failed to start foreground: ${t.message}"
            )
            stopSelf()
            return START_NOT_STICKY
        }

        // lifecycle -> RESUMED supaya CameraX boleh bind
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        startPipeline()
        return START_NOT_STICKY
    }

    private fun startForegroundInternal() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                else 0
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startPipeline() {
        analysisExecutor = Executors.newSingleThreadExecutor()
        blinkDetector = BlinkDetector()
        gestureEngine = GestureEngine()

        // Baca calibration readiness sekali untuk state awal
        scope.launch {
            try {
                CalibrationStore(applicationContext).calibrationFlow.collect { data ->
                    calibrationReady = data != null && data.isValid()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Calibration observer error", t)
            }
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val exec = analysisExecutor ?: return@addListener
                val detector = blinkDetector ?: return@addListener
                val gesture = gestureEngine ?: return@addListener

                val analyzer = FaceAnalyzer(
                    onResult = { snapshot ->
                        val now = System.currentTimeMillis()
                        val blinkOut = detector.update(
                            leftState = snapshot.leftEye.toBlinkEye(),
                            rightState = snapshot.rightEye.toBlinkEye(),
                            faceCount = snapshot.faceCount,
                            nowMs = now
                        )
                        val gestureResult = gesture.process(
                            output = blinkOut,
                            calibrationReady = calibrationReady,
                            nowMs = now
                        )
                        _status.value = _status.value.copy(
                            state = ServiceState.RUNNING,
                            faceDetected = snapshot.faceCount >= 1,
                            eyesDetected = snapshot.leftEye != FaceAnalyzer.EyeState.UNKNOWN &&
                                    snapshot.rightEye != FaceAnalyzer.EyeState.UNKNOWN,
                            lastAction = gestureResult.lastAction.name,
                            lastActionAtMs = gestureResult.lastActionAtMs
                        )
                    },
                    onError = { t -> Log.e(TAG, "Analyzer error", t) }
                )
                faceAnalyzer = analyzer

                analysis.setAnalyzer(exec, analyzer)
                provider.unbindAll()
                provider.bindToLifecycle(
                    this@RavenEyesCameraService,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis
                )
                Log.i(TAG, "Camera pipeline READY")
                _status.value = _status.value.copy(state = ServiceState.RUNNING)
            } catch (t: Throwable) {
                Log.e(TAG, "Camera pipeline error", t)
                _status.value = _status.value.copy(
                    state = ServiceState.ERROR,
                    errorMessage = "Camera pipeline error: ${t.message}"
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy")
        try {
            cameraProvider?.unbindAll()
        } catch (t: Throwable) {
            Log.w(TAG, "unbindAll error", t)
        }
        cameraProvider = null
        faceAnalyzer?.close()
        faceAnalyzer = null
        blinkDetector = null
        gestureEngine = null
        analysisExecutor?.shutdown()
        analysisExecutor = null

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        scope.cancel()

        _status.value = _status.value.copy(state = ServiceState.STOPPED)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_channel_description)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun FaceAnalyzer.EyeState.toBlinkEye(): BlinkDetector.EyeState = when (this) {
        FaceAnalyzer.EyeState.OPEN -> BlinkDetector.EyeState.OPEN
        FaceAnalyzer.EyeState.CLOSED -> BlinkDetector.EyeState.CLOSED
        FaceAnalyzer.EyeState.UNKNOWN -> BlinkDetector.EyeState.UNKNOWN
    }

    companion object {
        private const val TAG = "RavenEyes.Service"
        private const val CHANNEL_ID = "raven_eyes_camera"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, RavenEyesCameraService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RavenEyesCameraService::class.java)
            context.stopService(intent)
        }
    }
}