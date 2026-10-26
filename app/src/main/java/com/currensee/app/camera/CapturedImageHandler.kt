package com.currensee.app.camera

import androidx.camera.core.ImageProxy

/**
 * Titik integrasi model. Dipanggil pada thread pekerja, bukan thread UI.
 * Gambar hanya valid selama callback ini berjalan; CameraController menutupnya di finally.
 * Selesaikan konsumsi sebelum return. Untuk pekerjaan async, salin data yang diperlukan dulu.
 * image.imageInfo.rotationDegrees tersedia untuk orientasi gambar saat integrasi model nanti.
 */
fun onCapturedImage(image: ImageProxy) {
    // Skeleton hanya memeriksa frame di memori. Tidak ada inferensi, unggahan, atau file foto.
    check(image.width > 0 && image.height > 0 && image.planes.isNotEmpty()) {
        "Frame kamera kosong"
    }
}

internal fun consumeCapturedImage(image: ImageProxy, consumer: (ImageProxy) -> Unit) {
    try {
        consumer(image)
    } finally {
        image.close()
    }
}
