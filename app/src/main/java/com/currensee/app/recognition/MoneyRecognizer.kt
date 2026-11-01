package com.currensee.app.recognition

import android.content.res.AssetManager
import android.os.Looper
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface ImageRecognizer : AutoCloseable {
    /** Sinkron di worker kamera; pemanggil tetap memiliki ImageProxy. */
    fun recognize(image: ImageProxy): RecognitionResult
}

class MoneyRecognizer(private val assets: AssetManager) : ImageRecognizer {
    private var models: MoneyModels? = null
    private var closed = false

    @Synchronized
    override fun recognize(image: ImageProxy): RecognitionResult = CaptureDiagnostics.withCapture(null) {
        var stage = "initialization"
        try {
            checkWorker()
            check(!closed) { "Recognizer sudah ditutup" }
            CaptureDiagnostics.image(image)
            val runtime = models ?: MoneyModels(assets).also { models = it }
            stage = "preprocessing"
            val input = ImagePreprocessor.prepare(image)
            stage = "inference"
            val (paper, coin) = runtime.run(input)
            stage = "decision"
            RecognitionDecision.decide(paper, coin).also { CaptureDiagnostics.decision(paper, coin, it) }
        } catch (error: Exception) {
            failure(stage, error)
        } catch (error: LinkageError) {
            failure(stage, error)
        }
    }

    private fun failure(stage: String, error: Throwable): RecognitionResult.Failure {
        CaptureDiagnostics.event { "DECISION FAILURE reason=$stage errorType=${error.javaClass.simpleName}" }
        return RecognitionResult.Failure(error)
    }

    @Synchronized
    override fun close() {
        closed = true
        models?.close()
        models = null
    }
}

internal object ModelVariant {
    const val CURRENT = "v5_fp16"   // "v2_int8", "v5_fp16", "v5_int8"
}

internal fun checkWorker() = check(Looper.myLooper() != Looper.getMainLooper()) {
    "Runtime model harus dijalankan di thread pekerja"
}

/** Satu pasangan interpreter per layar. Seluruh akses, termasuk close, terserialisasi. */
internal class MoneyModels(assets: AssetManager) : AutoCloseable {
    private val paper: Model
    private val coin: Model
    init {
        checkWorker()
        paper = Model(assets, MoneyKind.PAPER, "kertas")
        try {
            coin = Model(assets, MoneyKind.COIN, "logam")
        } catch (error: Throwable) {
            paper.close()
            throw error
        }
    }

    @Synchronized
    fun run(input: ByteBuffer): Pair<ModelScores, ModelScores> {
        checkWorker()
        require(input.isDirect && input.capacity() == ImagePreprocessor.INPUT_BYTES &&
            input.order() == ByteOrder.nativeOrder()) { "Buffer input harus RGB FLOAT32 [1,224,224,3]" }
        return paper.run(input) to coin.run(input)
    }

    @Synchronized
    override fun close() {
        try { paper.close() } finally { coin.close() }
    }

    private class Model(assets: AssetManager, val kind: MoneyKind, name: String) : AutoCloseable {
        val labels = assets.open("models/labels_$name.txt").bufferedReader(Charsets.UTF_8).use {
            ModelLabels.read(it.readText(), kind)
        }
        // Asset stream juga bekerja bila APK mengompresi .tflite; tidak perlu mengubah packaging.
        private val model = assets.open("models/model_${name}_${ModelVariant.CURRENT}.tflite").use { it.readBytes() }.let {
            ByteBuffer.allocateDirect(it.size).order(ByteOrder.nativeOrder()).apply { put(it); rewind() }
        }
        // Kernel CPU bawaan: persiapan XNNPACK gagal pada asset ini dalam uji emulator.
        private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2).setUseXNNPACK(false))
        private val output = ByteBuffer.allocateDirect(labels.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        init {
            try {
                interpreter.allocateTensors()
                require(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount == 1) {
                    "$name: tensor count ${interpreter.inputTensorCount}/${interpreter.outputTensorCount}"
                }
                val inputTensor = interpreter.getInputTensor(0)
                val outputTensor = interpreter.getOutputTensor(0)
                val spec = "$name input=${inputTensor.dataType()} ${inputTensor.shape().contentToString()}, " +
                    "output=${outputTensor.dataType()} ${outputTensor.shape().contentToString()}, labels=${labels.size}"
                CaptureDiagnostics.event { "CONTRACT $spec" }
                require(inputTensor.dataType() == DataType.FLOAT32 &&
                    inputTensor.shape().contentEquals(intArrayOf(1, 224, 224, 3)) &&
                    outputTensor.dataType() == DataType.FLOAT32 &&
                    outputTensor.shape().contentEquals(intArrayOf(1, labels.size))) { spec }
            } catch (error: Throwable) {
                interpreter.close()
                throw error
            }
        }

        fun run(input: ByteBuffer): ModelScores {
            input.rewind()
            output.clear()
            interpreter.run(input, output)
            output.rewind()
            return ModelScores(kind, labels, FloatArray(labels.size) { output.float })
                .also { CaptureDiagnostics.scores(it) }
        }

        override fun close() = interpreter.close()
    }
}
