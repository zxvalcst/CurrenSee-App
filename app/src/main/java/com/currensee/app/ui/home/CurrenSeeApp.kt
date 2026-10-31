package com.currensee.app.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.room.Room
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.currensee.app.R
import com.currensee.app.camera.CameraController
import com.currensee.app.camera.CameraPreview
import com.currensee.app.camera.CameraStatus
import com.currensee.app.camera.CaptureStatus
import com.currensee.app.camera.permissionStatus
import com.currensee.app.data.AppDatabase
import com.currensee.app.data.Riwayat
import com.currensee.app.speech.SpeechController
import com.currensee.app.speech.SpeechOutput
import com.currensee.app.recognition.ImageRecognizer
import com.currensee.app.recognition.MoneyRecognizer
import com.currensee.app.recognition.RecognitionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CurrenSeeApp(
    activity: ComponentActivity,
    recognizerFactory: (Context) -> ImageRecognizer = { MoneyRecognizer(it.assets) },
    speechFactory: (Context) -> SpeechOutput = { SpeechController(it) },
    databaseFactory: (Context) -> AppDatabase = {
        Room.databaseBuilder(it, AppDatabase::class.java, "riwayat.db").build()
    }
) {
    val controller = remember(activity) { CameraController(activity.applicationContext) }
    val recognizer = remember(activity) { recognizerFactory(activity.applicationContext) }
    val speech = remember(activity) { speechFactory(activity.applicationContext) }
    val database = remember(activity) { databaseFactory(activity.applicationContext) }
    var recognition by remember { mutableStateOf<RecognitionState>(RecognitionState.Idle) }
    var lastAccepted by remember { mutableStateOf<RecognitionResult.Recognized?>(null) }
    var historySaveFailed by remember { mutableStateOf(false) }
    var requestGeneration by remember { mutableStateOf(0L) }
    fun invalidateResult() {
        requestGeneration++
        lastAccepted = null
        recognition = RecognitionState.Idle
        historySaveFailed = false
        speech.stop()
    }
    val preferences = remember(activity) { activity.getSharedPreferences("camera_permission", 0) }
    fun hasPermission() = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    fun readPermission(): CameraStatus {
        val granted = hasPermission()
        if (granted) preferences.edit { remove("blocked") }
        return permissionStatus(
            granted,
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA),
            preferences.getBoolean("blocked", false)
        )
    }
    var permission by remember(activity) { mutableStateOf(readPermission()) }
    var requesting by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val settingsError = stringResource(R.string.settings_unavailable)
    val permissionError = stringResource(R.string.permission_unavailable)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requesting = false
        preferences.edit {
            putBoolean(
                "blocked", !granted && !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.CAMERA
                )
            )
        }
        permission = readPermission()
    }

    DisposableEffect(activity, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permission = readPermission()
            if (event == Lifecycle.Event.ON_PAUSE) invalidateResult()
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            controller.close { recognizer.close() }
            speech.close()
            database.close()
        }
    }

    val granted = permission == CameraStatus.Starting
    val uiState = HomeUiState(
        camera = if (granted) controller.status else permission,
        isCameraActionEnabled = !requesting,
        isCaptureInFlight = controller.isCapturing,
        isSpeechAvailable = speech.isAvailable,
        recognition = recognition,
        historySaveFailed = historySaveFailed
    )
    HomeScreen(
        uiState = uiState,
        snackbarHostState = snackbar,
        onRecognize = recognize@{
            if (controller.isCapturing) return@recognize
            invalidateResult()
            // Periksa izin lagi pada setiap operasi kamera, bukan hanya saat layar dibuka.
            if (!hasPermission()) {
                permission = readPermission()
                return@recognize
            }
            val request = requestGeneration
            var outcome: RecognitionResult? = null
            recognition = RecognitionState.Capturing
            val started = controller.capture(
                onProcessing = {
                    if (request == requestGeneration) recognition = RecognitionState.Processing
                },
                onFinished = finished@{ status ->
                    if (request != requestGeneration) return@finished
                    if (status != CaptureStatus.Captured) {
                        recognition = RecognitionState.CaptureFailed
                        return@finished
                    }
                    when (val result = outcome) {
                        is RecognitionResult.Recognized -> {
                            lastAccepted = result
                            recognition = RecognitionState.Recognized(result.money.display)
                            speech.speak(result.money.speech)
                            // Hanya hasil yang diterima saat lifecycle masih aktif boleh disimpan.
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        database.riwayatDao().insert(Riwayat(
                                            nominal = result.money.display, confidence = result.score
                                        ))
                                    }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    Log.e("CurrenSeeHistory", "Gagal menyimpan hasil", error)
                                    if (request == requestGeneration &&
                                        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                                    ) historySaveFailed = true
                                }
                            }
                        }
                        RecognitionResult.Unrecognized -> recognition = RecognitionState.Unrecognized
                        is RecognitionResult.Failure, null -> recognition = RecognitionState.Failure
                    }
                },
                onImageCaptured = { image -> outcome = recognizer.recognize(image) }
            )
            if (!started) recognition = RecognitionState.CaptureFailed
        },
        onRepeatSpeech = {
            if (recognition is RecognitionState.Recognized && speech.isAvailable &&
                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) lastAccepted?.let { speech.speak(it.money.speech) }
        },
        onCameraAction = {
            // Retry mengganti binding kamera; jangan menunggu hasil dari binding lama.
            invalidateResult()
            when {
                hasPermission() -> {
                    permission = readPermission()
                    controller.retry()
                }
                permission == CameraStatus.PermissionBlocked -> {
                    try {
                        activity.startActivity(Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:${activity.packageName}".toUri()
                        ))
                    } catch (_: RuntimeException) {
                        scope.launch { snackbar.showSnackbar(settingsError) }
                    }
                }
                !requesting -> {
                    requesting = true
                    try {
                        launcher.launch(Manifest.permission.CAMERA)
                    } catch (_: RuntimeException) {
                        requesting = false
                        permission = CameraStatus.PermissionBlocked
                        scope.launch { snackbar.showSnackbar(permissionError) }
                    }
                }
            }
        },
        cameraContent = if (granted) {
            { CameraPreview(controller, Modifier.matchParentSize()) }
        } else null
    )
}
