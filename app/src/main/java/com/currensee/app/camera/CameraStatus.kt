package com.currensee.app.camera

enum class CameraStatus {
    PreviewOnly, PermissionRequired, PermissionDenied, PermissionBlocked,
    Starting, Ready, Paused, Unavailable, Error
}

enum class CaptureStatus { Idle, Capturing, Captured, Error }

internal fun permissionStatus(granted: Boolean, showRationale: Boolean, blocked: Boolean) = when {
    granted -> CameraStatus.Starting
    showRationale -> CameraStatus.PermissionDenied
    blocked -> CameraStatus.PermissionBlocked
    else -> CameraStatus.PermissionRequired
}
