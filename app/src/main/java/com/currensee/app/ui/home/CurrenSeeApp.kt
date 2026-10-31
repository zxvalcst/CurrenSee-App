package com.currensee.app.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageProxy
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import com.currensee.app.camera.onCapturedImage
import com.currensee.app.camera.permissionStatus
import com.currensee.app.data.AppDatabase
import com.currensee.app.data.Riwayat
import com.currensee.app.speech.SpeechController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CurrenSeeApp(
    activity: ComponentActivity,
    onImageCaptured: (ImageProxy) -> Unit = ::onCapturedImage
) {
    val controller = remember(activity) { CameraController(activity.applicationContext) }
    val speech = remember(activity) { SpeechController(activity.applicationContext) }
    val database = remember(activity) {
        Room.databaseBuilder(
            activity.applicationContext,
            AppDatabase::class.java, "riwayat.db"
        ).build()
    }
    // Hasil placeholder sampai model deteksi terpasang. Ganti dengan keluaran model nanti.
    var lastResult by remember { mutableStateOf<Pair<String, String>?>(null) } // (teks tampilan, teks ucapan)
    LaunchedEffect(controller.captureStatus) {
        if (controller.captureStatus == CaptureStatus.Captured) {
            // TO DO(9.2): placeholder, ganti dengan hasil klasifikasi model TFLite
            val result = "Rp50.000" to "Lima puluh ribu rupiah"
            lastResult = result
            speech.speak(result.second)
            withContext(Dispatchers.IO) {
                database.riwayatDao().insert(Riwayat(nominal = result.first, confidence = 0.92f))
                Log.d("RiwayatDemo", database.riwayatDao().getAll().toString())
            }
        }
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
    val consumer by rememberUpdatedState(onImageCaptured)
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
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            controller.close()
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
        recognition = when (controller.captureStatus) {
            CaptureStatus.Idle -> RecognitionState.Idle
            CaptureStatus.Capturing -> RecognitionState.Capturing
            CaptureStatus.Captured -> lastResult?.let { RecognitionState.Recognized(it.first) }
                ?: RecognitionState.ImageCaptured
            CaptureStatus.Error -> RecognitionState.CaptureFailed
        }
    )
    HomeScreen(
        uiState = uiState,
        snackbarHostState = snackbar,
        onRecognize = {
            // Periksa izin lagi pada setiap operasi kamera, bukan hanya saat layar dibuka.
            if (hasPermission()) controller.capture { consumer(it) }
            else permission = readPermission()
        },
        onRepeatSpeech = { lastResult?.let { speech.speak(it.second) } },
        onCameraAction = {
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