package com.currensee.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.datasource.CollectionPreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.currensee.app.R
import com.currensee.app.ui.theme.CurrenSeeTheme
import com.currensee.app.ui.theme.CameraSurface
import com.currensee.app.ui.theme.CameraWhite
import com.currensee.app.camera.CameraStatus

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onRecognize: () -> Unit,
    onRepeatSpeech: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onCameraAction: () -> Unit = {},
    cameraContent: (@Composable BoxScope.() -> Unit)? = null
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(innerPadding)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    // Panel diukur sesuai isinya; kamera mengisi sisa ruang.
                    // Bila isi membesar, tinggi minimum kamera tetap ada dan layar bisa di-scroll.
                    .height(IntrinsicSize.Min)
                    .background(CameraSurface)
            ) {
                HomeHeader()
                CameraArea(
                    uiState, onCameraAction, cameraContent,
                    Modifier.fillMaxWidth().weight(1f).heightIn(min = 160.dp)
                )
                ControlPanel(uiState, onRecognize, onRepeatSpeech)
            }
        }
    }
}

@Composable
private fun HomeHeader() {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)
                .semantics { heading() }
        )
    }
}

@Composable
private fun CameraArea(
    uiState: HomeUiState,
    onCameraAction: () -> Unit,
    cameraContent: (@Composable BoxScope.() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.clipToBounds().background(CameraSurface),
        contentAlignment = Alignment.Center
    ) {
        // matchParentSize mengecualikan AndroidView dari pengukuran intrinsik panel scroll.
        cameraContent?.invoke(this)
        // Penanda statis untuk panduan framing, bukan hasil deteksi objek.
        Canvas(Modifier.matchParentSize().padding(24.dp)) {
            val corner = 24.dp.toPx()
            val guides = Path().apply {
                moveTo(0f, corner); lineTo(0f, 0f); lineTo(corner, 0f)
                moveTo(size.width - corner, 0f); lineTo(size.width, 0f); lineTo(size.width, corner)
                moveTo(size.width, size.height - corner); lineTo(size.width, size.height)
                lineTo(size.width - corner, size.height)
                moveTo(corner, size.height); lineTo(0f, size.height); lineTo(0f, size.height - corner)
            }
            drawPath(guides, CameraWhite, style = Stroke(width = 2.dp.toPx()))
        }
        val statusText = when (uiState.camera) {
            CameraStatus.PreviewOnly -> R.string.camera_disconnected
            CameraStatus.PermissionRequired -> R.string.camera_permission_required
            CameraStatus.PermissionDenied -> R.string.camera_permission_denied
            CameraStatus.PermissionBlocked -> R.string.camera_permission_blocked
            CameraStatus.Starting -> R.string.camera_starting
            CameraStatus.Ready -> R.string.camera_ready
            CameraStatus.Paused -> R.string.camera_paused
            CameraStatus.Unavailable -> R.string.camera_unavailable
            CameraStatus.Error -> R.string.camera_error
        }
        val actionText = when (uiState.camera) {
            CameraStatus.PermissionRequired, CameraStatus.PermissionDenied -> R.string.enable_camera
            CameraStatus.PermissionBlocked -> R.string.open_settings
            CameraStatus.Unavailable, CameraStatus.Error -> R.string.retry_camera
            else -> null
        }
        if (uiState.camera == CameraStatus.Ready) {
            Text(
                stringResource(statusText),
                style = MaterialTheme.typography.bodyMedium,
                color = CameraWhite,
                modifier = Modifier.align(Alignment.BottomStart).padding(32.dp)
                    .background(CameraSurface, RoundedCornerShape(8.dp)).padding(8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        } else {
            Column(
                Modifier.padding(horizontal = 40.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    stringResource(statusText),
                    style = MaterialTheme.typography.bodyLarge,
                    color = CameraWhite,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                if (actionText != null) {
                    Button(
                        onClick = onCameraAction,
                        enabled = uiState.isCameraActionEnabled,
                        modifier = Modifier.heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CameraWhite,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) { Text(stringResource(actionText), textAlign = TextAlign.Center) }
                }
            }
        }
    }
}

@Composable
private fun ControlPanel(
    uiState: HomeUiState,
    onRecognize: () -> Unit,
    onRepeatSpeech: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 24.dp)) {
            Text(
                text = stringResource(R.string.camera_instruction),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            RecognitionResult(uiState.recognition)
            Box(Modifier.padding(top = 24.dp)) {
                RecognitionActions(uiState, onRecognize, onRepeatSpeech)
            }
            Text(
                text = stringResource(R.string.prototype_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

@Composable
private fun RecognitionResult(state: RecognitionState) {
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
        },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.recognition_heading),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val resultText = when (state) {
            RecognitionState.Idle -> stringResource(R.string.result_empty)
            RecognitionState.Processing -> stringResource(R.string.result_processing)
            is RecognitionState.Recognized -> state.denomination
            RecognitionState.Unrecognized -> stringResource(R.string.result_unrecognized)
            RecognitionState.Capturing -> stringResource(R.string.capture_in_progress)
            RecognitionState.ImageCaptured -> stringResource(R.string.capture_success)
            RecognitionState.CaptureFailed -> stringResource(R.string.capture_failed)
        }
        Text(
            // Peluang pindah baris setelah Rp dan pemisah ribuan; ukuran font tetap.
            text = if (state is RecognitionState.Recognized) {
                resultText.replaceFirst("Rp", "Rp\u200B").replace(".", ".\u200B")
            } else resultText,
            modifier = Modifier.fillMaxWidth().semantics {
                // TalkBack tetap membaca nominal utuh tanpa karakter pemisah tambahan.
                text = AnnotatedString(resultText)
            },
            style = if (state is RecognitionState.Recognized) {
                MaterialTheme.typography.headlineLarge
            } else {
                MaterialTheme.typography.titleLarge
            }
        )
        if (state == RecognitionState.Unrecognized) {
            Text(
                text = stringResource(R.string.try_again_instruction),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RecognitionActions(
    uiState: HomeUiState,
    onRecognize: () -> Unit,
    onRepeatSpeech: () -> Unit
) {
    val canRepeatSpeech = uiState.recognition is RecognitionState.Recognized &&
        uiState.isSpeechAvailable
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onRecognize,
            enabled = uiState.canCapture,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Text(stringResource(R.string.recognize_money), textAlign = TextAlign.Center)
        }
        OutlinedButton(
            onClick = onRepeatSpeech,
            enabled = canRepeatSpeech,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Text(stringResource(R.string.repeat_speech), textAlign = TextAlign.Center)
        }
        if (!canRepeatSpeech) {
            Text(
                text = stringResource(
                    if (uiState.isSpeechAvailable) R.string.speech_needs_result
                    else R.string.speech_unavailable
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Data nominal contoh hanya digunakan oleh Preview, bukan oleh aplikasi.
@Preview(name = "01 - Awal", showBackground = true, widthDp = 393, heightDp = 851)
@Composable
private fun HomeInitialPreview() {
    CurrenSeeTheme { HomeScreen(HomeUiState(), {}, {}) }
}

@Preview(name = "02 - Sedang memproses", showBackground = true, widthDp = 393, heightDp = 851)
@Composable
private fun HomeProcessingPreview() {
    CurrenSeeTheme {
        HomeScreen(HomeUiState(RecognitionState.Processing), {}, {})
    }
}

class DenominationPreviewProvider : CollectionPreviewParameterProvider<String>(
    listOf("Rp1.000", "Rp50.000", "Rp100.000")
)

@Preview(name = "03 - Contoh nominal", showBackground = true, widthDp = 393, heightDp = 851)
@Preview(name = "07 - Nominal font 2x", showBackground = true, widthDp = 320, heightDp = 568, fontScale = 2f)
@Composable
private fun HomeRecognizedPreview(
    @PreviewParameter(DenominationPreviewProvider::class) denomination: String
) {
    CurrenSeeTheme {
        HomeScreen(HomeUiState(RecognitionState.Recognized(denomination)), {}, {})
    }
}

@Preview(name = "04 - Belum dikenali", showBackground = true, widthDp = 393, heightDp = 851)
@Composable
private fun HomeUnrecognizedPreview() {
    CurrenSeeTheme {
        HomeScreen(HomeUiState(RecognitionState.Unrecognized), {}, {})
    }
}

@Preview(name = "05 - Layar kecil", showBackground = true, widthDp = 320, heightDp = 568)
@Preview(name = "06 - Font 2x", showBackground = true, widthDp = 320, heightDp = 568, fontScale = 2f)
@Composable
private fun HomeSmallPreview() {
    CurrenSeeTheme { HomeScreen(HomeUiState(), {}, {}) }
}

