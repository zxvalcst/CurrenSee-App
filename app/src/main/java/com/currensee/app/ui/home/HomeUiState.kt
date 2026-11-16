package com.currensee.app.ui.home

import com.currensee.app.camera.CameraStatus

sealed interface RecognitionState {
    data object Idle : RecognitionState
    data object Processing : RecognitionState
    data class Recognized(val denomination: String) : RecognitionState
    data object Unrecognized : RecognitionState
    data object Capturing : RecognitionState
    data object Failure : RecognitionState
    data object CaptureFailed : RecognitionState
}

data class HomeUiState(
    val recognition: RecognitionState = RecognitionState.Idle,
    val isSpeechAvailable: Boolean = false,
    val camera: CameraStatus = CameraStatus.PreviewOnly,
    val isCameraActionEnabled: Boolean = true,
    val isCaptureInFlight: Boolean = false,
    val historySaveFailed: Boolean = false
) {
    val canCapture: Boolean
        get() = camera in setOf(CameraStatus.Ready, CameraStatus.PreviewOnly) &&
            recognition != RecognitionState.Processing &&
            recognition != RecognitionState.Capturing && !isCaptureInFlight
}
