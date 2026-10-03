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
import android.graphics.SurfaceTexture
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class RavenEyesCameraService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var faceAnalyzer: FaceAnalyzer? = null
    private var blinkDetector: BlinkDetector? = null
    private var gestureEngine: GestureEngine? = null
    private var calibrationReady: Boolean = false

    // Offscreen preview resources
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null

    private val frameCounter = AtomicLong(0L)
    private var lastLoggedFrameCount: Long = 0L

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
        Log.i(TAG, "SERVICE_CREATED")
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "SERVICE_STARTED")

        val cameraGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!cameraGranted) {
            Log.e(TAG, "CAMERA_PIPELINE_ERROR: camera permission missing")
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
            Log.e(TAG, "CAMERA_PIPELINE_ERROR: startForeground failed", t)
            _status.value = _status.value.copy(
                state = ServiceState.ERROR,
                errorMessage = "Failed to start foreground: ${t.message}"
            )
            stopSelf()
            return START_NOT_STICKY
        }

        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        startPipeline()
        return START_NOT_STICKY
    }

    private fun startForegroundInternal() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startPipeline() {
        Log.i(TAG, "CAMERA_PIPELINE_STARTING")

        analysisExecutor = Executors.newSingleThreadExecutor()
        blinkDetector = BlinkDetector()
        gestureEngine = GestureEngine()

        scope.launch {
            try {
                CalibrationStore(applicationContext).calibrationFlow.collect { data ->
                    calibrationReady = data != null && data.isValid()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "CAMERA_PIPELINE_ERROR: calibration observer", t)
            }
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                // ---- Preview offscreen ----
                val st = SurfaceTexture(0).apply {
                    setDefaultBufferSize(PREVIEW_DUMMY_WIDTH, PREVIEW_DUMMY_HEIGHT)
                }
                val surface = Surface(st)
                previewSurfaceTexture = st
                previewSurface = surface

                val preview = Preview.Builder()
                    .setTargetResolution(android.util.Size(PREVIEW_DUMMY_WIDTH, PREVIEW_DUMMY_HEIGHT))
                    .build()
                preview.setSurfaceProvider { request ->
                    try {
                        request.provideSurface(
                            surface,
                            ContextCompat.getMainExecutor(this@RavenEyesCameraService)
                        ) {
                            // Callback ketika surface dirilis oleh CameraX.
                            // Kita tidak menutup surface di sini karena kita
                            // menutupnya di onDestroy untuk mencegah double-close.
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "CAMERA_PIPELINE_ERROR: provideSurface", t)
                    }
                }

                // ---- ImageAnalysis ----
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val exec = analysisExecutor ?: run {
                    Log.e(TAG, "CAMERA_PIPELINE_ERROR: executor null")
                    return@addListener
                }
                val detector = blinkDetector ?: run {
                    Log.e(TAG, "CAMERA_PIPELINE_ERROR: blinkDetector null")
                    return@addListener
                }
                val gesture = gestureEngine ?: run {
                    Log.e(TAG, "CAMERA_PIPELINE_ERROR: gestureEngine null")
                    return@addListener
                }

                val realAnalyzer = FaceAnalyzer(
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
                    onError = { t -> Log.e(TAG, "CAMERA_PIPELINE_ERROR: analyzer", t) }
                )
                faceAnalyzer = realAnalyzer

                // Frame-counting wrapper
                val countingAnalyzer = ImageAnalysis.Analyzer { imageProxy: ImageProxy ->
                    try {
                        val count = frameCounter.incrementAndGet()
                        if (count - lastLoggedFrameCount >= FRAME_LOG_INTERVAL) {
                            lastLoggedFrameCount = count
                            Log.i(TAG, "ANALYSIS_FRAME count=$count")
                        }
                        realAnalyzer.analyze(imageProxy)
                    } catch (t: Throwable) {
                        Log.e(TAG, "CAMERA_PIPELINE_ERROR: analyze wrapper", t)
                        try {
                            imageProxy.close()
                        } catch (ignore: Throwable) {
                        }
                    }
                }

                analysis.setAnalyzer(exec, countingAnalyzer)

                provider.unbindAll()
                provider.bindToLifecycle(
                    this@RavenEyesCameraService,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )

                Log.i(TAG, "CAMERA_PIPELINE_READY (preview+analysis bound)")
                _status.value = _status.value.copy(state = ServiceState.RUNNING)

            } catch (t: Throwable) {
                Log.e(TAG, "CAMERA_PIPELINE_ERROR", t)
                _status.value = _status.value.copy(
                    state = ServiceState.ERROR,
                    errorMessage = "Camera pipeline error: ${t.message}"
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        Log.i(TAG, "SERVICE_DESTROYED")

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

        try {
            previewSurface?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "surface release error", t)
        }
        previewSurface = null

        try {
            previewSurfaceTexture?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "surfaceTexture release error", t)
        }
        previewSurfaceTexture = null

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
        private const val PREVIEW_DUMMY_WIDTH = 320
        private const val PREVIEW_DUMMY_HEIGHT = 240
        private const val FRAME_LOG_INTERVAL = 60L  // ~1 detik pada 60fps efektif

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