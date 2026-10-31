package com.currensee.app.recognition

import java.text.NumberFormat
import java.util.Locale

enum class MoneyKind { PAPER, COIN }

enum class Denomination(val label: String, val rupiah: Int, val kind: MoneyKind, val speech: String) {
    PAPER_1000("kertas_1000", 1000, MoneyKind.PAPER, "Seribu rupiah"),
    PAPER_10000("kertas_10000", 10000, MoneyKind.PAPER, "Sepuluh ribu rupiah"),
    PAPER_100000("kertas_100000", 100000, MoneyKind.PAPER, "Seratus ribu rupiah"),
    PAPER_2000("kertas_2000", 2000, MoneyKind.PAPER, "Dua ribu rupiah"),
    PAPER_20000("kertas_20000", 20000, MoneyKind.PAPER, "Dua puluh ribu rupiah"),
    PAPER_5000("kertas_5000", 5000, MoneyKind.PAPER, "Lima ribu rupiah"),
    PAPER_50000("kertas_50000", 50000, MoneyKind.PAPER, "Lima puluh ribu rupiah"),
    COIN_100("logam_100", 100, MoneyKind.COIN, "Seratus rupiah"),
    COIN_1000("logam_1000", 1000, MoneyKind.COIN, "Seribu rupiah"),
    COIN_200("logam_200", 200, MoneyKind.COIN, "Dua ratus rupiah"),
    COIN_500("logam_500", 500, MoneyKind.COIN, "Lima ratus rupiah");

    val display: String
        get() = "Rp" + NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID")).format(rupiah)
}

sealed interface RecognitionResult {
    data class Recognized(val money: Denomination, val score: Float) : RecognitionResult {
        init {
            require(score.isFinite() && score in RecognitionDecision.MIN_SCORE..1f)
        }
    }
    data object Unrecognized : RecognitionResult
    data class Failure(val cause: Throwable) : RecognitionResult
}
