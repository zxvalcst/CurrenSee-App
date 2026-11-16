package com.currensee.app.camera

import androidx.camera.core.ImageProxy

/** Callback sinkron pada worker. Hanya controller yang menutup frame, tepat sekali. */
internal fun consumeCapturedImage(image: ImageProxy, consumer: (ImageProxy) -> Unit) {
    try {
        consumer(image)
    } finally {
        image.close()
    }
}
