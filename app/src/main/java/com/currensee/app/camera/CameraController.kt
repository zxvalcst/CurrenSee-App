package com.currensee.app.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.currensee.app.recognition.CaptureDiagnostics
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Dimiliki satu layar. bind/capture/retry/unbind/close dipanggil di main thread. */
class CameraController(private val context: Context) {
    var status by mutableStateOf(CameraStatus.Starting)
        private set
    var captureStatus by mutableStateOf(CaptureStatus.Idle)
        private set
    var isCapturing by mutableStateOf(false)
        private set

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val imageExecutor = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicBoolean(false)
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var owner: LifecycleOwner? = null
    private var view: PreviewView? = null
    private var lifecycleObserver: LifecycleEventObserver? = null
    private var streamObserver: Observer<PreviewView.StreamState>? = null
    private var cameraState: androidx.lifecycle.LiveData<CameraState>? = null
    private var cameraObserver: Observer<CameraState>? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    @Volatile private var generation = 0
    @Volatile private var captureGeneration = 0
    @Volatile private var disposed = false
    private var workerCleanup: () -> Unit = {}

    fun bind(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (disposed) return
        unbind()
        owner = lifecycleOwner
        view = previewView
        status = CameraStatus.Starting
        val binding = generation

        lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> captureGeneration++
                Lifecycle.Event.ON_STOP -> {
                    // Pertahankan pesan gagal/tidak tersedia agar tindakan retry tidak hilang.
                    if (status != CameraStatus.Error && status != CameraStatus.Unavailable) {
                        status = CameraStatus.Paused
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (status != CameraStatus.Unavailable && status != CameraStatus.Error) {
                        status = if (previewView.previewStreamState.value == PreviewView.StreamState.STREAMING) {
                            CameraStatus.Ready
                        } else CameraStatus.Starting
                    }
                }
                else -> Unit
            }
        }.also { lifecycleOwner.lifecycle.addObserver(it) }

        streamObserver = Observer<PreviewView.StreamState> { stream ->
            if (binding == generation && status !in listOf(CameraStatus.Error, CameraStatus.Unavailable)) {
                status = when {
                    !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) -> CameraStatus.Paused
                    stream == PreviewView.StreamState.STREAMING -> CameraStatus.Ready
                    else -> CameraStatus.Starting
                }
            }
        }.also { previewView.previewStreamState.observe(lifecycleOwner, it) }

        // Inisialisasi juga bisa gagal sebelum Future tersedia.
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (disposed || binding != generation) return@addListener
                try {
                    checkPermission()
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        status = CameraStatus.Unavailable
                        return@addListener
                    }
                    val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
                    val cameraPreview = Preview.Builder().setTargetRotation(rotation).build()
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(rotation)
                        .build()
                    preview = cameraPreview
                    imageCapture = capture
                    cameraPreview.setSurfaceProvider(previewView.surfaceProvider)
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, capture
                    )
                    cameraState = camera.cameraInfo.cameraState
                    cameraObserver = Observer<CameraState> { state ->
                        if (binding == generation && state.error != null) {
                            status = CameraStatus.Error
                            Log.e(TAG, "Camera error: ${state.error?.code}", state.error?.cause)
                        }
                    }.also { cameraState?.observe(lifecycleOwner, it) }
                    displayListener = object : DisplayManager.DisplayListener {
                        override fun onDisplayAdded(displayId: Int) = Unit
                        override fun onDisplayRemoved(displayId: Int) = Unit
                        override fun onDisplayChanged(displayId: Int) {
                            if (displayId == previewView.display?.displayId) updateRotation()
                        }
                    }.also { displayManager.registerDisplayListener(it, Handler(Looper.getMainLooper())) }
                } catch (error: Exception) {
                    Log.e(TAG, "Cannot bind camera", error)
                    if (binding == generation && !disposed) {
                        releaseUseCases()
                        status = CameraStatus.Error
                    }
                }
            }, mainExecutor)
        } catch (error: Exception) {
            Log.e(TAG, "Cannot initialize CameraX", error)
            status = CameraStatus.Error
        }
    }

    fun retry() {
        val currentOwner = owner ?: return
        val currentView = view ?: return
        bind(currentOwner, currentView)
    }

    /** Consumer sinkron berjalan di worker; setiap ImageProxy ditutup, termasuk saat consumer gagal. */
    fun capture(
        onProcessing: () -> Unit = {},
        onFinished: (CaptureStatus) -> Unit = {},
        onImageCaptured: (ImageProxy) -> Unit
    ): Boolean {
        val captureId = CaptureDiagnostics.newId()
        CaptureDiagnostics.event(captureId) { "CAPTURE requested cameraStatus=$status" }
        val capture = imageCapture
        if (disposed || status != CameraStatus.Ready || capture == null || isCapturing ||
            owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true
        ) {
            CaptureDiagnostics.event(captureId) { "DECISION REJECTED reason=camera_not_ready_or_capture_busy_or_lifecycle_inactive" }
            if (!disposed && !isCapturing) captureStatus = CaptureStatus.Error
            return false
        }
        if (!inFlight.compareAndSet(false, true)) {
            CaptureDiagnostics.event(captureId) { "DECISION REJECTED reason=capture_in_flight" }
            return false
        }
        val binding = generation
        val request = captureGeneration
        isCapturing = true
        captureStatus = CaptureStatus.Capturing
        try {
            checkPermission()
            updateRotation()
            capture.takePicture(imageExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    var result = CaptureStatus.Error
                    try {
                        CaptureDiagnostics.withCapture(captureId) {
                            consumeCapturedImage(image) {
                                if (!disposed && binding == generation && request == captureGeneration) {
                                    mainExecutor.execute {
                                        if (isCurrentCapture(binding, request)) onProcessing()
                                    }
                                    onImageCaptured(it)
                                    result = CaptureStatus.Captured
                                } else {
                                    CaptureDiagnostics.image(it)
                                    CaptureDiagnostics.event { "DECISION DISCARDED reason=lifecycle_or_binding_changed_before_inference" }
                                }
                            }
                        }
                    } catch (error: Exception) {
                        CaptureDiagnostics.event(captureId) { "DECISION FAILURE reason=image_consumer errorType=${error.javaClass.simpleName}" }
                    } finally {
                        finishCapture(binding, request, result, onFinished, captureId)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    CaptureDiagnostics.event(captureId) { "DECISION FAILURE reason=camera_capture errorCode=${exception.imageCaptureError} models=not_run" }
                    finishCapture(binding, request, CaptureStatus.Error, onFinished, captureId)
                }
            })
        } catch (error: Exception) {
            CaptureDiagnostics.event(captureId) { "DECISION FAILURE reason=start_capture errorType=${error.javaClass.simpleName} models=not_run" }
            finishCapture(binding, request, CaptureStatus.Error, onFinished, captureId)
        }
        return true
    }

    private fun isCurrentCapture(binding: Int, request: Int): Boolean =
        !disposed && binding == generation && request == captureGeneration &&
            owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true

    private fun finishCapture(
        binding: Int, request: Int, result: CaptureStatus, onFinished: (CaptureStatus) -> Unit,
        captureId: String?
    ) {
        mainExecutor.execute {
            inFlight.set(false)
            CaptureDiagnostics.event(captureId) {
                "DELIVERY captureStatus=$result current=${isCurrentCapture(binding, request)} " +
                    "reason=${if (isCurrentCapture(binding, request)) "callback_current" else "lifecycle_or_binding_changed_result_discarded"}"
            }
            if (!disposed) {
                isCapturing = false
                val current = isCurrentCapture(binding, request)
                captureStatus = if (current) result else CaptureStatus.Idle
                if (current) onFinished(result) // Hanya main thread dan layar/lifecycle yang sama.
            }
            // Tunggu callback capture selesai sebelum menutup executor, agar frame tetap ditutup.
            if (disposed) shutdownWorker()
        }
    }

    private fun shutdownWorker() {
        if (!imageExecutor.isShutdown) {
            imageExecutor.execute {
                try { workerCleanup() } catch (error: Exception) {
                    Log.e(TAG, "Worker cleanup gagal", error)
                }
            }
            imageExecutor.shutdown()
        }
    }

    private fun updateRotation() {
        val rotation = view?.display?.rotation ?: Surface.ROTATION_0
        imageCapture?.targetRotation = rotation
        preview?.targetRotation = rotation
    }

    private fun checkPermission() {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "Camera permission is missing"
        }
    }

    private fun releaseUseCases() {
        val useCases = listOfNotNull(preview, imageCapture).toTypedArray()
        if (useCases.isNotEmpty()) runCatching { provider?.unbind(*useCases) }
        preview = null
        imageCapture = null
    }

    fun unbind() {
        generation++ // Abaikan hasil inisialisasi/capture milik view yang sudah dilepas.
        captureGeneration++
        lifecycleObserver?.let { owner?.lifecycle?.removeObserver(it) }
        streamObserver?.let { view?.previewStreamState?.removeObserver(it) }
        cameraObserver?.let { cameraState?.removeObserver(it) }
        displayListener?.let { displayManager.unregisterDisplayListener(it) }
        lifecycleObserver = null
        streamObserver = null
        cameraObserver = null
        displayListener = null
        cameraState = null
        releaseUseCases()
        owner = null
        view = null
        if (!disposed) status = CameraStatus.Paused
    }

    fun close(onWorkerClosed: () -> Unit = {}) {
        if (disposed) return
        disposed = true
        workerCleanup = onWorkerClosed
        unbind()
        if (!inFlight.get()) shutdownWorker()
    }

    private companion object { const val TAG = "CurrenSeeCamera" }
}
