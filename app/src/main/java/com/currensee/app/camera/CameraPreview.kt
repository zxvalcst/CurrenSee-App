package com.currensee.app.camera

import android.view.View
import android.view.ViewOutlineProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun CameraPreview(controller: CameraController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER // Crop proporsional, bukan stretch.
            clipChildren = true
            clipToPadding = true
            outlineProvider = ViewOutlineProvider.BOUNDS
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
    DisposableEffect(controller, previewView, lifecycleOwner) {
        controller.bind(lifecycleOwner, previewView)
        onDispose { controller.unbind() }
    }
}
