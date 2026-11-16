package com.currensee.app

import android.Manifest
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageProxy
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.currensee.app.data.AppDatabase
import com.currensee.app.recognition.Denomination
import com.currensee.app.recognition.ImageRecognizer
import com.currensee.app.recognition.MoneyRecognizer
import com.currensee.app.recognition.RecognitionResult
import com.currensee.app.speech.SpeechOutput
import com.currensee.app.ui.home.CurrenSeeApp
import com.currensee.app.ui.theme.CurrenSeeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Kamera nyata, classifier/suara terkendali, Room in-memory. Tidak menilai akurasi model. */
@SdkSuppress(minSdkVersion = 28)
class CameraIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: AppDatabase
    private val speech = TestSpeech()
    private val closed = CountDownLatch(1)

    private fun startCamera(consumer: (ImageProxy) -> RecognitionResult) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName, Manifest.permission.CAMERA
        )
        compose.setContent {
            CurrenSeeTheme {
                CurrenSeeApp(compose.activity,
                    recognizerFactory = {
                        object : ImageRecognizer {
                            override fun recognize(image: ImageProxy): RecognitionResult {
                                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                                check(image.width > 0 && image.height > 0)
                                return consumer(image)
                            }
                            override fun close() {
                                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                                closed.countDown()
                            }
                        }
                    },
                    speechFactory = { speech },
                    databaseFactory = {
                        Room.inMemoryDatabaseBuilder(it, AppDatabase::class.java).build()
                            .also { db -> database = db }
                    }
                )
            }
        }
        waitFor("Kamera belakang aktif")
    }

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun capture() = compose.onNodeWithText("Kenali uang").performScrollTo().performClick()
    private fun rows() = runBlocking(Dispatchers.IO) { database.riwayatDao().getAll() }
    private fun waitForRows(count: Int) = compose.waitUntil(15_000) { rows().size == count }

    @Test fun captureAndResumeStoreActualAcceptedScoreAndSpeakIndonesian() {
        val captures = AtomicInteger()
        startCamera {
            captures.incrementAndGet()
            RecognitionResult.Recognized(Denomination.PAPER_20000, 0.87f)
        }
        capture()
        waitFor("Rp20.000")
        waitForRows(1)
        assertEquals(1, captures.get())
        assertEquals("Rp20.000", rows().single().nominal)
        assertEquals(0.87f, rows().single().confidence, 0f)
        compose.onNodeWithText("Ulangi suara").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("Dua puluh ribu rupiah", "Dua puluh ribu rupiah"), speech.spoken) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("Kamera belakang aktif")
        compose.onNodeWithText("Ulangi suara").assertIsNotEnabled()
        capture()
        waitFor("Rp20.000")
        waitForRows(2)
        assertEquals(2, captures.get())
    }

    @Test fun failureAndUnrecognizedClearOldSpeechAndNeverEnterHistory() {
        val captures = AtomicInteger()
        startCamera {
            when (captures.incrementAndGet()) {
                1 -> RecognitionResult.Recognized(Denomination.COIN_500, 0.91f)
                2 -> RecognitionResult.Failure(IllegalStateException("Injected model failure"))
                else -> RecognitionResult.Unrecognized
            }
        }
        capture(); waitFor("Rp500"); waitForRows(1)
        capture(); waitFor("Pengenalan uang gagal")
        compose.onNodeWithText("Ulangi suara").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Kenali uang").assertIsEnabled()
        capture(); waitFor("Uang belum dikenali")
        compose.onNodeWithText("Ulangi suara").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("Lima ratus rupiah"), speech.spoken) }
        assertEquals(1, rows().size)
    }

    @Test fun throwingConsumerReleasesFrameAndNextCaptureSucceeds() {
        val captures = AtomicInteger()
        startCamera {
            if (captures.incrementAndGet() == 1) error("Injected consumer failure")
            RecognitionResult.Recognized(Denomination.COIN_100, 0.9f)
        }
        capture(); waitFor("Gambar gagal diambil")
        compose.onNodeWithText("Ulangi suara").assertIsNotEnabled()
        capture(); waitFor("Rp100"); waitForRows(1)
        assertEquals(2, captures.get())
    }

    @Test fun processingDisablesBothActionsUntilWorkerFinishes() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val captures = AtomicInteger()
        startCamera {
            captures.incrementAndGet()
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS))
            RecognitionResult.Recognized(Denomination.PAPER_1000, 0.9f)
        }
        try {
            capture()
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            waitFor("Sedang mengenali uang")
            compose.onNodeWithText("Kenali uang").assertIsNotEnabled()
            compose.onNodeWithText("Ulangi suara").assertIsNotEnabled()
            assertEquals(1, captures.get())
        } finally { release.countDown() }
        waitFor("Rp1.000"); waitForRows(1)
    }

    @Test fun backgroundDuringInferenceDiscardsResultEvenAfterResume() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val captures = AtomicInteger()
        startCamera {
            if (captures.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS))
            }
            RecognitionResult.Recognized(Denomination.COIN_500, 0.9f)
        }
        try {
            capture()
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor("Kamera belakang aktif")
        } finally { release.countDown() }
        compose.waitUntil(15_000) {
            !compose.onNodeWithText("Kenali uang").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }
        compose.onNodeWithText("Belum ada hasil", substring = true).assertExists()
        compose.onNodeWithText("Ulangi suara").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(speech.spoken.isEmpty()) }
        assertTrue(rows().isEmpty())
        capture(); waitFor("Rp500"); waitForRows(1)
    }

    @Test fun disposingScreenWaitsForWorkerBeforeClosingRecognizer() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        startCamera {
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS))
            RecognitionResult.Recognized(Denomination.PAPER_1000, 0.9f)
        }
        try {
            capture()
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            compose.activityRule.scenario.close()
            assertEquals(1L, closed.count)
        } finally { release.countDown() }
        assertTrue(closed.await(15, TimeUnit.SECONDS))
        assertTrue(speech.spoken.isEmpty())
    }

    @Test fun historyFailureKeepsAcceptedNominalAndRepeatSpeech() {
        startCamera { RecognitionResult.Recognized(Denomination.COIN_200, 0.86f) }
        // Fault hanya di DB in-memory: Room dapat membuka ulang DB yang sekadar ditutup.
        runBlocking(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_insert BEFORE INSERT ON riwayat " +
                    "BEGIN SELECT RAISE(ABORT, 'Injected write failure'); END"
            )
        }
        capture(); waitFor("Rp200"); waitFor("Hasil belum tersimpan di riwayat")
        compose.onNodeWithText("Ulangi suara").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("Dua ratus rupiah", "Dua ratus rupiah"), speech.spoken) }
        compose.onNodeWithText("Kenali uang").assertIsEnabled()
    }

    @Test fun realCameraFrameRunsBothModelsWithoutFixedNominal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val actual = AtomicReference<RecognitionResult>()
        val runtime = MoneyRecognizer(context.assets)
        try {
            startCamera { runtime.recognize(it).also(actual::set) }
            capture()
            compose.waitUntil(30_000) { actual.get() != null }
            val result = actual.get()
            assertFalse("Inferensi frame kamera gagal: $result", result is RecognitionResult.Failure)
            when (result) {
                is RecognitionResult.Recognized -> {
                    waitFor(result.money.display); waitForRows(1)
                    assertEquals(result.score, rows().single().confidence, 0f)
                }
                else -> { waitFor("Uang belum dikenali"); assertTrue(rows().isEmpty()) }
            }
        } finally {
            compose.activityRule.scenario.close()
            assertTrue(closed.await(15, TimeUnit.SECONDS))
            runtime.close()
        }
    }

    private class TestSpeech : SpeechOutput {
        override val isAvailable = true
        val spoken = mutableListOf<String>()
        override fun speak(text: String) {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            spoken += text
        }
        override fun stop() {}
        override fun close() {}
    }
}
