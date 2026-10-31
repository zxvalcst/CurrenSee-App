package com.currensee.app.recognition

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RecognitionDecisionTest {
    private fun output(kind: MoneyKind, winner: Int, score: Float = 0.9f): ModelScores {
        val labels = ModelLabels.expected(kind)
        val values = FloatArray(labels.size) { (1f - score) / (labels.size - 1) }
        values[winner] = score
        return ModelScores(kind, labels, values)
    }

    @Test fun assetLabelOrderIsExactAndNeverSorted() {
        assertEquals(ModelLabels.paper, ModelLabels.read(
            File("src/main/assets/models/labels_kertas.txt").readText(), MoneyKind.PAPER))
        assertEquals(ModelLabels.coin, ModelLabels.read(
            File("src/main/assets/models/labels_logam.txt").readText(), MoneyKind.COIN))
        assertThrows(IllegalArgumentException::class.java) {
            ModelLabels.read(ModelLabels.paper.reversed().joinToString("\n"), MoneyKind.PAPER)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelLabels.read("bukan_uang\nlogam_100", MoneyKind.COIN)
        }
    }

    @Test fun everyLabelMapsToItsOwnNominalAndIndonesianSpeech() {
        val displays = listOf("Rp1.000", "Rp10.000", "Rp100.000", "Rp2.000", "Rp20.000",
            "Rp5.000", "Rp50.000", "Rp100", "Rp1.000", "Rp200", "Rp500")
        val speech = listOf("Seribu rupiah", "Sepuluh ribu rupiah", "Seratus ribu rupiah",
            "Dua ribu rupiah", "Dua puluh ribu rupiah", "Lima ribu rupiah", "Lima puluh ribu rupiah",
            "Seratus rupiah", "Seribu rupiah", "Dua ratus rupiah", "Lima ratus rupiah")
        assertEquals(ModelLabels.paper.drop(1) + ModelLabels.coin.drop(1), Denomination.entries.map { it.label })
        assertEquals(displays, Denomination.entries.map { it.display })
        assertEquals(speech, Denomination.entries.map { it.speech })
        for (money in Denomination.entries) {
            val own = output(money.kind, ModelLabels.expected(money.kind).indexOf(money.label))
            val other = output(if (money.kind == MoneyKind.PAPER) MoneyKind.COIN else MoneyKind.PAPER, 0)
            val result = if (money.kind == MoneyKind.PAPER) RecognitionDecision.decide(own, other)
                else RecognitionDecision.decide(other, own)
            assertEquals(RecognitionResult.Recognized(money, 0.9f), result)
        }
    }

    @Test fun bothMoneyPredictionsAreRejectedEvenWhenOneScoreIsHigher() {
        assertEquals(RecognitionResult.Unrecognized, RecognitionDecision.decide(
            output(MoneyKind.PAPER, 7, 0.99f), output(MoneyKind.COIN, 4, 0.8f)))
        assertEquals(RecognitionResult.Unrecognized, RecognitionDecision.decide(
            output(MoneyKind.PAPER, 0), output(MoneyKind.COIN, 0)))
    }

    @Test fun scoreAndOtherModelBoundariesUseSpecifiedThresholds() {
        assertEquals(0.75f, RecognitionDecision.MIN_SCORE, 0f)
        assertEquals(0.15f, RecognitionDecision.MIN_MARGIN, 0f)
        assertEquals(0.50f, RecognitionDecision.MIN_OTHER_NOT_MONEY, 0f)
        assertTrue(RecognitionDecision.decide(output(MoneyKind.PAPER, 1, 0.75f),
            output(MoneyKind.COIN, 0, 0.50f)) is RecognitionResult.Recognized)
        assertEquals(RecognitionResult.Unrecognized, RecognitionDecision.decide(
            output(MoneyKind.PAPER, 1, 0.749f), output(MoneyKind.COIN, 0)))
        assertEquals(RecognitionResult.Unrecognized, RecognitionDecision.decide(
            output(MoneyKind.PAPER, 1), output(MoneyKind.COIN, 0, 0.499f)))
    }

    @Test fun smallMarginAndAmbiguousOtherModelAreRejected() {
        val close = ModelScores(MoneyKind.PAPER, ModelLabels.paper,
            floatArrayOf(0.01f, 0.50f, 0.49f, 0f, 0f, 0f, 0f, 0f))
        assertEquals(RecognitionResult.Unrecognized,
            RecognitionDecision.decide(close, output(MoneyKind.COIN, 0)))
        val tied = ModelScores(MoneyKind.COIN, ModelLabels.coin, floatArrayOf(0.5f, 0.5f, 0f, 0f, 0f))
        assertEquals(RecognitionResult.Unrecognized,
            RecognitionDecision.decide(output(MoneyKind.PAPER, 7), tied))
    }

    @Test fun invalidOutputsNeverBecomeMoney() {
        val valid = output(MoneyKind.PAPER, 7)
        val bad = listOf(FloatArray(0), FloatArray(4), FloatArray(5) { Float.NaN },
            floatArrayOf(Float.POSITIVE_INFINITY, 0f, 0f, 0f, 0f),
            floatArrayOf(-0.1f, 0.9f, 0.1f, 0.1f, 0f),
            floatArrayOf(1.1f, 0f, 0f, 0f, 0f), FloatArray(5))
        for (values in bad) {
            val coin = ModelScores(MoneyKind.COIN, ModelLabels.coin, values)
            assertFalse(RecognitionDecision.hasValidOutput(coin))
            assertEquals(RecognitionResult.Unrecognized, RecognitionDecision.decide(valid, coin))
        }
        assertEquals(RecognitionResult.Unrecognized, RecognitionDecision.decide(valid,
            output(MoneyKind.COIN, 0).copy(labels = ModelLabels.coin.reversed())))
        assertThrows(IllegalArgumentException::class.java) {
            RecognitionResult.Recognized(Denomination.COIN_100, Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecognitionResult.Recognized(Denomination.COIN_100, 0.7f)
        }
    }
}
