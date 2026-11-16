package com.currensee.app.recognition

import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.util.Log
import androidx.camera.core.ImageProxy
import com.currensee.app.BuildConfig
import java.util.UUID

/** Hanya metadata teknis/skor; jangan tambahkan piksel, EXIF, URI, atau pesan exception. */
internal object CaptureDiagnostics {
    private const val TAG = "CurrenSeeDiag"
    private val current = ThreadLocal<String>()

    fun newId(): String? = if (BuildConfig.DEBUG) UUID.randomUUID().toString() else null

    fun event(id: String?, message: () -> String) {
        if (BuildConfig.DEBUG && id != null) Log.d(TAG, "capture=$id ${message()}")
    }

    fun event(message: () -> String) = event(current.get(), message)

    // Scope hanya selama callback sinkron; tidak mengubah API recognizer atau kepemilikan frame.
    fun <T> withCapture(id: String?, block: () -> T): T {
        if (!BuildConfig.DEBUG) return block()
        val previous = current.get()
        current.set(id ?: previous ?: newId())
        try { return block() } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    fun image(image: ImageProxy) = event {
        val crop = image.cropRect
        val rotation = image.imageInfo.rotationDegrees
        val swap = rotation == 90 || rotation == 270
        val width = if (swap) crop.height() else crop.width()
        val height = if (swap) crop.width() else crop.height()
        val side = minOf(width, height)
        val left = (width - side) / 2
        val top = (height - side) / 2
        val format = when (image.format) {
            ImageFormat.JPEG -> "JPEG"
            ImageFormat.JPEG_R -> "JPEG_R"
            ImageFormat.YUV_420_888 -> "YUV_420_888"
            PixelFormat.RGBA_8888 -> "RGBA_8888"
            else -> "OTHER"
        }
        "IMAGE format=$format(${image.format}) original=${image.width}x${image.height} " +
            "rotationClockwise=$rotation cropRect=[${crop.left},${crop.top},${crop.right},${crop.bottom}] " +
            "upright=${width}x$height centerCropUpright=[$left,$top,${left + side},${top + side}] " +
            "input=FLOAT32[1,${ImagePreprocessor.SIZE},${ImagePreprocessor.SIZE},3] RGB range=0..255 " +
            "order=cropRect,rotate,centerCrop,resize"
    }

    fun scores(output: ModelScores) = event { RecognitionTrace.scores(output) }

    fun decision(paper: ModelScores, coin: ModelScores, result: RecognitionResult) = event {
        RecognitionTrace.decision(paper, coin, result)
    }
}

/** Penjelasan observasional. RecognitionDecision tetap satu-satunya pembuat keputusan. */
internal object RecognitionTrace {
    fun scores(output: ModelScores): String {
        val ranked = output.values.indices.sortedByDescending { output.values[it] }
        fun entry(index: Int) = "$index:${output.labels.getOrNull(index) ?: "missing_label"}=${output.values[index]}"
        return "MODEL kind=${output.kind} scores=[${output.values.indices.joinToString { entry(it) }}] " +
            "top1=${ranked.getOrNull(0)?.let { entry(it) }} " +
            "top2=${ranked.getOrNull(1)?.let { entry(it) }} " +
            "sum=${output.values.sum()} valid=${RecognitionDecision.hasValidOutput(output)}"
    }

    fun decision(paper: ModelScores, coin: ModelScores, result: RecognitionResult): String {
        val valid = paper.kind == MoneyKind.PAPER && coin.kind == MoneyKind.COIN &&
            RecognitionDecision.hasValidOutput(paper) && RecognitionDecision.hasValidOutput(coin)
        if (!valid) return "DECISION REJECTED reason=invalid_model_output_or_kind"

        fun reasons(own: ModelScores, other: ModelScores): String {
            val ownRanks = own.values.indices.sortedByDescending { own.values[it] }
            val otherRanks = other.values.indices.sortedByDescending { other.values[it] }
            val top = ownRanks[0]
            val second = ownRanks[1]
            val otherTop = otherRanks[0]
            val failures = buildList {
                if (own.labels[top] == "bukan_uang") add("top_is_bukan_uang")
                if (own.values[top] < RecognitionDecision.MIN_SCORE) add("score_below_0.75")
                if (own.values[top] - own.values[second] < RecognitionDecision.MIN_MARGIN) add("margin_below_0.15")
                if (other.labels[otherTop] != "bukan_uang") add("other_top_is_money")
                if (other.values[otherTop] < RecognitionDecision.MIN_OTHER_NOT_MONEY) add("other_score_below_0.50")
                if (other.values[otherTop] <= other.values[otherRanks[1]]) add("other_top_tied")
                if (own.labels[top] != "bukan_uang" &&
                    Denomination.entries.none { it.label == own.labels[top] && it.kind == own.kind }
                ) add("no_nominal_mapping")
            }
            return "${own.kind}=[${failures.joinToString().ifEmpty { "eligible" }}]"
        }
        val candidates = "${reasons(paper, coin)} ${reasons(coin, paper)}"
        return when (result) {
            is RecognitionResult.Recognized -> "DECISION ACCEPTED kind=${result.money.kind} " +
                "label=${result.money.label} rupiah=${result.money.rupiah} score=${result.score} " +
                "reason=single_eligible_candidate $candidates"
            RecognitionResult.Unrecognized -> "DECISION REJECTED reason=no_single_eligible_candidate $candidates"
            is RecognitionResult.Failure -> "DECISION FAILURE reason=runtime_failure"
        }
    }
}
