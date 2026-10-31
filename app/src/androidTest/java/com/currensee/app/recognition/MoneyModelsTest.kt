package com.currensee.app.recognition

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class MoneyModelsTest {
    @Test fun bothRealAssetsHaveValidContractsAndRunOnSameFloatInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Pola RGB deterministik untuk smoke test runtime, bukan foto uang / uji akurasi.
        val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        for (y in 0 until 224) for (x in 0 until 224) {
            bitmap.setPixel(x, y, Color.rgb(x, y, (x + y) % 256))
        }
        val input = ImagePreprocessor.prepare(bitmap, Rect(0, 0, 224, 224), 0)
        bitmap.recycle()
        MoneyModels(context.assets).use { models ->
            val (paper, coin) = models.run(input)
            assertEquals(8, paper.values.size)
            assertEquals(5, coin.values.size)
            assertEquals(ModelLabels.paper, paper.labels)
            assertEquals(ModelLabels.coin, coin.labels)
            assertTrue(RecognitionDecision.hasValidOutput(paper))
            assertTrue(RecognitionDecision.hasValidOutput(coin))
            val repeated = models.run(input)
            assertArrayEquals(paper.values, repeated.first.values, 0.00001f)
            assertArrayEquals(coin.values, repeated.second.values, 0.00001f)
            val report = JSONObject().put("input", "FLOAT32 [1,224,224,3], RGB 0..255")
                .put("paper", JSONArray(paper.values.toList())).put("coin", JSONArray(coin.values.toList()))
            Log.i("CurrenSeeModelTest", report.toString())
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?.let { File(it).apply { mkdirs() } } ?: context.cacheDir
            File(output, "model-smoke-output.json").writeText(report.toString(2))
            input.rewind()
            val bytes = ByteArray(input.remaining()).also { input.get(it) }
            File(output, "model-smoke-input.f32").writeBytes(bytes)
        }
    }

    @Test fun centerCropRotationAndRgbRangeMatchTrainingContract() {
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        // cropRect [2,0..6,4], lalu putar 90 derajat: merah di kanan, biru di kiri.
        for (y in 0 until 4) for (x in 2 until 6) {
            bitmap.setPixel(x, y, if (y < 2) Color.RED else Color.BLUE)
        }
        val input = ImagePreprocessor.prepare(bitmap, Rect(2, 0, 6, 4), 90)
        fun rgb(x: Int, y: Int): List<Float> {
            val offset = (y * 224 + x) * 3 * Float.SIZE_BYTES
            return List(3) { input.getFloat(offset + it * Float.SIZE_BYTES) }
        }
        assertEquals(listOf(0f, 0f, 255f), rgb(20, 100))
        assertEquals(listOf(255f, 0f, 0f), rgb(200, 100))
        assertEquals(ImagePreprocessor.INPUT_BYTES, input.capacity())
        assertEquals(ByteOrder.nativeOrder(), input.order())
        assertFalse(bitmap.isRecycled)
        // Rasio 2:1 tanpa cropRect tambahan: bagian pinggir magenta harus dibuang center crop.
        for (y in 0 until 4) for (x in 2 until 6) bitmap.setPixel(x, y, Color.GREEN)
        val centered = ImagePreprocessor.prepare(bitmap, Rect(0, 0, 8, 4), 0)
        assertEquals(0f, centered.getFloat(0), 0f)
        assertEquals(255f, centered.getFloat(4), 0f)
        assertEquals(0f, centered.getFloat(8), 0f)
        assertThrows(IllegalArgumentException::class.java) {
            ImagePreprocessor.prepare(bitmap, Rect(-1, 0, 8, 4), 0)
        }
        bitmap.recycle()
    }

    @Test fun jpegCaptureDecodesWithoutClosingFrameOrRotatingTwice() {
        val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
        for (y in 0 until 16) for (x in 0 until 32) {
            bitmap.setPixel(x, y, if (y < 8) Color.RED else Color.BLUE)
        }
        val bytes = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
            it.toByteArray()
        }
        bitmap.recycle()
        var closed = 0
        val plane = proxy(ImageProxy.PlaneProxy::class.java) { method ->
            when (method) {
                "getBuffer" -> ByteBuffer.wrap(bytes).apply { position(5) }
                "getPixelStride" -> 1
                "getRowStride" -> bytes.size
                else -> null
            }
        }
        val info = proxy(ImageInfo::class.java) { if (it == "getRotationDegrees") 90 else null }
        val image = proxy(ImageProxy::class.java) {
            when (it) {
                "getFormat" -> ImageFormat.JPEG
                "getWidth" -> 32
                "getHeight" -> 16
                "getCropRect" -> Rect(0, 0, 32, 16)
                "getImageInfo" -> info
                "getPlanes" -> arrayOf(plane)
                "close" -> { closed++; null }
                else -> null
            }
        }
        val input = ImagePreprocessor.prepare(image)
        val left = (100 * 224 + 20) * 3 * Float.SIZE_BYTES
        val right = (100 * 224 + 200) * 3 * Float.SIZE_BYTES
        assertTrue(input.getFloat(left) <= 5f)
        assertTrue(input.getFloat(left + 8) >= 250f)
        assertTrue(input.getFloat(right) >= 250f)
        assertTrue(input.getFloat(right + 8) <= 5f)
        assertEquals(0, closed) // CameraController tetap pemilik frame.
    }

    private fun <T : Any> proxy(type: Class<T>, call: (String) -> Any?): T = requireNotNull(type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> call(method.name) }
    ))
}
