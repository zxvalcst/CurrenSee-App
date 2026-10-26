package com.currensee.app.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageProxy
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.currensee.app.R
import com.currensee.app.camera.CameraController
import com.currensee.app.camera.CameraPreview
import com.currensee.app.camera.CameraStatus
import com.currensee.app.camera.CaptureStatus
import com.currensee.app.camera.onCapturedImage
import com.currensee.app.camera.permissionStatus
import kotlinx.coroutines.launch

@Composable
fun CurrenSeeApp(
    activity: ComponentActivity,
    onImageCaptured: (ImageProxy) -> Unit = ::onCapturedImage
) {
    val controller = remember(activity) { CameraController(activity.applicationContext) }
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
        }
    }

    val granted = permission == CameraStatus.Starting
    val uiState = HomeUiState(
        camera = if (granted) controller.status else permission,
        isCameraActionEnabled = !requesting,
        isCaptureInFlight = controller.isCapturing,
        recognition = when (controller.captureStatus) {
            CaptureStatus.Idle -> RecognitionState.Idle
            CaptureStatus.Capturing -> RecognitionState.Capturing
            CaptureStatus.Captured -> RecognitionState.ImageCaptured
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
        // Integrasi suara belum tersedia; isSpeechAvailable tetap false.
        onRepeatSpeech = {},
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
