package com.currensee.app.recognition

import kotlin.math.abs

object ModelLabels {
    val paper = listOf("bukan_uang", "kertas_1000", "kertas_10000", "kertas_100000",
        "kertas_2000", "kertas_20000", "kertas_5000", "kertas_50000")
    val coin = listOf("bukan_uang", "logam_100", "logam_1000", "logam_200", "logam_500")

    fun expected(kind: MoneyKind): List<String> = if (kind == MoneyKind.PAPER) paper else coin

    fun read(text: String, kind: MoneyKind): List<String> {
        val labels = text.removePrefix("\uFEFF").trimEnd('\r', '\n').lines().map { it.trim() }
        require(labels == expected(kind)) { "Urutan/jumlah label $kind tidak sesuai: $labels" }
        return labels // Urutan file adalah urutan output; tidak pernah diurutkan ulang.
    }
}

data class ModelScores(val kind: MoneyKind, val labels: List<String>, val values: FloatArray)

object RecognitionDecision {
    // Threshold awal dari spesifikasi tugas; belum divalidasi untuk akurasi penggunaan nyata.
    const val MIN_SCORE = 0.75f
    const val MIN_MARGIN = 0.15f
    const val MIN_OTHER_NOT_MONEY = 0.50f
    private const val SOFTMAX_SUM_TOLERANCE = 0.01f

    fun hasValidOutput(output: ModelScores): Boolean =
        output.labels == ModelLabels.expected(output.kind) &&
            output.values.size == output.labels.size &&
            output.values.all { it.isFinite() && it in 0f..1f } &&
            abs(output.values.sum() - 1f) <= SOFTMAX_SUM_TOLERANCE

    fun decide(paper: ModelScores, coin: ModelScores): RecognitionResult {
        if (paper.kind != MoneyKind.PAPER || coin.kind != MoneyKind.COIN ||
            !hasValidOutput(paper) || !hasValidOutput(coin)
        ) return RecognitionResult.Unrecognized

        fun topTwo(output: ModelScores): Pair<Int, Int> {
            val indices = output.values.indices.sortedByDescending { output.values[it] }
            return indices[0] to indices[1]
        }
        val (paperTop, paperSecond) = topTwo(paper)
        val (coinTop, coinSecond) = topTwo(coin)
        fun candidate(own: ModelScores, top: Int, second: Int,
                      other: ModelScores, otherTop: Int, otherSecond: Int): RecognitionResult.Recognized? {
            if (own.labels[top] == "bukan_uang" || own.values[top] < MIN_SCORE ||
                own.values[top] - own.values[second] < MIN_MARGIN ||
                other.labels[otherTop] != "bukan_uang" ||
                other.values[otherTop] < MIN_OTHER_NOT_MONEY ||
                other.values[otherTop] <= other.values[otherSecond]
            ) return null
            val money = Denomination.entries.singleOrNull {
                it.label == own.labels[top] && it.kind == own.kind
            } ?: return null
            return RecognitionResult.Recognized(money, own.values[top])
        }
        val accepted = listOfNotNull(
            candidate(paper, paperTop, paperSecond, coin, coinTop, coinSecond),
            candidate(coin, coinTop, coinSecond, paper, paperTop, paperSecond)
        )
        // Tidak memilih pemenang dengan membandingkan softmax lintas model.
        return accepted.singleOrNull() ?: RecognitionResult.Unrecognized
    }
}
