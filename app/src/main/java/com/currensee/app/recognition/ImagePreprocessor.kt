package com.currensee.app.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import androidx.camera.core.ImageProxy
import androidx.core.graphics.scale
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ImagePreprocessor {
    const val SIZE = 224
    const val INPUT_BYTES = SIZE * SIZE * 3 * Float.SIZE_BYTES

    fun prepare(image: ImageProxy): ByteBuffer {
        val bitmap = when {
            image.format == ImageFormat.JPEG ||
                (Build.VERSION.SDK_INT >= 34 && image.format == ImageFormat.JPEG_R) -> {
                require(image.planes.size == 1) { "Jumlah plane JPEG tidak sesuai" }
                val source = image.planes[0].buffer.duplicate().apply { rewind() }
                val bytes = ByteArray(source.remaining()).also { source.get(it) }
                // BitmapFactory membaca piksel mentah, tidak menerapkan rotasi EXIF.
                // JPEG_R didekode sebagai base image SDR, bukan nilai HDR/gainmap.
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                }) ?: error("JPEG tidak dapat didekode")
            }
            image.format == ImageFormat.YUV_420_888 || image.format == PixelFormat.RGBA_8888 ->
                image.toBitmap() // CameraX menangani rowStride/pixelStride; tanpa rotasi/crop.
            else -> error("Format ImageProxy tidak didukung: ${image.format}")
        }
        return try {
            require(bitmap.width == image.width && bitmap.height == image.height) {
                "Dimensi decode ${bitmap.width}x${bitmap.height} != ImageProxy ${image.width}x${image.height}"
            }
            prepare(bitmap, Rect(image.cropRect), image.imageInfo.rotationDegrees)
        } finally {
            bitmap.recycle()
        }
    }

    /** Tidak mengambil kepemilikan bitmap pemanggil. Rotasi searah jarum jam diterapkan sekali. */
    internal fun prepare(bitmap: Bitmap, crop: Rect, rotation: Int): ByteBuffer {
        require(rotation in listOf(0, 90, 180, 270)) { "Rotasi tidak didukung: $rotation" }
        require(!crop.isEmpty && Rect(0, 0, bitmap.width, bitmap.height).contains(crop)) {
            "cropRect di luar bitmap: $crop"
        }
        val temporary = mutableSetOf<Bitmap>()
        try {
            val cropped = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height())
                .also { temporary += it }
            val upright = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true).also { temporary += it }
            val side = minOf(upright.width, upright.height)
            val left = (upright.width - side) / 2
            val top = (upright.height - side) / 2
            // Potong bitmap sebelum resize: sampling bilinear tidak boleh mengambil piksel
            // dari luar center crop (drawBitmap dengan source Rect dapat mencampur tepinya).
            val square = Bitmap.createBitmap(upright, left, top, side, side).also { temporary += it }
            val target = square.scale(SIZE, SIZE, filter = true).also { temporary += it }
            val pixels = IntArray(SIZE * SIZE)
            target.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            return ByteBuffer.allocateDirect(INPUT_BYTES).order(ByteOrder.nativeOrder()).apply {
                for (pixel in pixels) {
                    // MobileNetV3 include_preprocessing=True: RGB FLOAT32 0..255, tanpa /255.
                    putFloat(Color.red(pixel).toFloat())
                    putFloat(Color.green(pixel).toFloat())
                    putFloat(Color.blue(pixel).toFloat())
                }
                rewind()
            }
        } finally {
            temporary.forEach { if (it !== bitmap) it.recycle() }
        }
    }
}
