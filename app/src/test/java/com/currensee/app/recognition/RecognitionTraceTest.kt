package com.currensee.app.recognition

import com.currensee.app.BuildConfig
import org.junit.Assert.*
import org.junit.Test

class RecognitionTraceTest {
    @Test fun buildVariantGuardsDoNotEvaluateDisabledPayloads() {
        CaptureDiagnostics.event(null) { error("Null capture must not log") }
        if (BuildConfig.DEBUG) {
            assertNotNull(CaptureDiagnostics.newId())
        } else {
            assertNull(CaptureDiagnostics.newId())
            CaptureDiagnostics.event("release-test") { error("Release must not evaluate diagnostic payloads") }
            var ran = false
            CaptureDiagnostics.withCapture("release-test") {
                ran = true
                CaptureDiagnostics.event { error("Release worker must not log") }
            }
            assertTrue(ran)
        }
    }

    private fun output(kind: MoneyKind, winner: Int, score: Float = 0.9f): ModelScores {
        val labels = ModelLabels.expected(kind)
        return ModelScores(kind, labels, FloatArray(labels.size) {
            if (it == winner) score else (1f - score) / (labels.size - 1)
        })
    }

    private fun trace(paper: ModelScores, coin: ModelScores) =
        RecognitionTrace.decision(paper, coin, RecognitionDecision.decide(paper, coin))

    @Test fun scoresKeepEveryIndexAndDistinguishBothThousandRupiahClasses() {
        for ((kind, index) in listOf(MoneyKind.PAPER to 1, MoneyKind.COIN to 2)) {
            val output = output(kind, index)
            val line = RecognitionTrace.scores(output)
            output.labels.forEachIndexed { i, label -> assertTrue(line.contains("$i:$label=${output.values[i]}")) }
            assertTrue(line.contains("top1=$index:${output.labels[index]}=0.9"))
            assertTrue(line.contains("top2=0:bukan_uang="))
            assertTrue(line.contains("valid=true"))
        }
        val accepted = trace(output(MoneyKind.PAPER, 1), output(MoneyKind.COIN, 0))
        assertTrue(accepted.contains("ACCEPTED kind=PAPER label=kertas_1000 rupiah=1000"))
        assertTrue(accepted.contains("PAPER=[eligible]"))
        assertTrue(trace(output(MoneyKind.PAPER, 0), output(MoneyKind.COIN, 2))
            .contains("ACCEPTED kind=COIN label=logam_1000 rupiah=1000"))
    }

    @Test fun rejectionExplainsExistingDecisionWithoutChangingScores() {
        val paper = output(MoneyKind.PAPER, 1, 0.749f)
        val original = paper.values.copyOf()
        assertTrue(trace(paper, output(MoneyKind.COIN, 0)).contains("score_below_0.75"))
        assertArrayEquals(original, paper.values, 0f)
        assertTrue(trace(output(MoneyKind.PAPER, 1), output(MoneyKind.COIN, 4)).contains("other_top_is_money"))
        val nonMoney = trace(output(MoneyKind.PAPER, 0), output(MoneyKind.COIN, 0))
        assertTrue(nonMoney.contains("top_is_bukan_uang"))
        assertFalse(nonMoney.contains("no_nominal_mapping"))
        assertTrue(trace(output(MoneyKind.PAPER, 1), output(MoneyKind.COIN, 0, 0.499f)).contains("other_score_below_0.50"))
        val tied = ModelScores(MoneyKind.COIN, ModelLabels.coin, floatArrayOf(.5f, .5f, 0f, 0f, 0f))
        assertTrue(trace(output(MoneyKind.PAPER, 1), tied).contains("other_top_tied"))
        val close = ModelScores(MoneyKind.PAPER, ModelLabels.paper, floatArrayOf(.01f, .5f, .49f, 0f, 0f, 0f, 0f, 0f))
        assertTrue(trace(close, output(MoneyKind.COIN, 0)).contains("margin_below_0.15"))
        val invalid = output(MoneyKind.COIN, 0).copy(values = floatArrayOf(Float.NaN))
        assertTrue(trace(paper, invalid).contains("REJECTED reason=invalid_model_output_or_kind"))
        assertTrue(RecognitionTrace.scores(invalid).contains("valid=false"))
    }
}
