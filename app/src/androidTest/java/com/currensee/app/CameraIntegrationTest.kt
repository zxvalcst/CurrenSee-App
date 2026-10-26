package com.currensee.app

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageProxy
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.currensee.app.ui.home.CurrenSeeApp
import com.currensee.app.ui.theme.CurrenSeeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue

/** Membutuhkan perangkat/emulator dengan kamera belakang. Tidak memanggil model AI. */
@SdkSuppress(minSdkVersion = 28)
class CameraIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun startCamera(consumer: (ImageProxy) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName, Manifest.permission.CAMERA
        )
        compose.setContent {
            CurrenSeeTheme { CurrenSeeApp(compose.activity, consumer) }
        }
        waitFor("Kamera belakang aktif")
    }

    private fun waitFor(text: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun realCaptureAndBackgroundResumeWorkWithoutFakeRecognition() {
        val captures = AtomicInteger()
        startCamera { image ->
            check(image.width > 0 && image.height > 0)
            captures.incrementAndGet()
        }
        compose.onNodeWithText("Kenali uang").performScrollTo().performClick()
        waitFor("Gambar berhasil diambil. Pengenalan uang belum tersedia.")
        assertEquals(1, captures.get())
        compose.onNodeWithText("Rp", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Ulangi suara").assertIsNotEnabled()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("Kamera belakang aktif")
        compose.onNodeWithText("Kenali uang").performScrollTo().performClick()
        compose.waitUntil(20_000) { captures.get() == 2 }
        waitFor("Gambar berhasil diambil. Pengenalan uang belum tersedia.")
    }

    @Test
    fun failedConsumerReleasesFrameAndNextCaptureCanSucceed() {
        val captures = AtomicInteger()
        startCamera {
            if (captures.incrementAndGet() == 1) error("Injected consumer failure")
        }
        compose.onNodeWithText("Kenali uang").performScrollTo().performClick()
        waitFor("Gambar gagal diambil")
        compose.onNodeWithText("Kenali uang").performScrollTo().performClick()
        waitFor("Gambar berhasil diambil. Pengenalan uang belum tersedia.")
        assertEquals(2, captures.get())
        compose.onNodeWithText("Ulangi suara").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun captureButtonStaysDisabledUntilImageConsumerFinishes() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val captures = AtomicInteger()
        startCamera {
            captures.incrementAndGet()
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS))
        }
        try {
            compose.onNodeWithText("Kenali uang").performScrollTo().performClick()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            compose.onNodeWithText("Kenali uang").assertIsNotEnabled()
            compose.onNodeWithText("Sedang mengambil gambar…", substring = true).assertExists()
            assertEquals(1, captures.get())
        } finally {
            release.countDown()
        }
        waitFor("Gambar berhasil diambil. Pengenalan uang belum tersedia.")
    }
}
