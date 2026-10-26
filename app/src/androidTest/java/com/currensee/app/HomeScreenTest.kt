package com.currensee.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.currensee.app.camera.CameraStatus
import com.currensee.app.ui.home.HomeScreen
import com.currensee.app.ui.home.HomeUiState
import com.currensee.app.ui.home.RecognitionState
import com.currensee.app.ui.theme.CurrenSeeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.io.File

class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun missingPermissionExplainsCameraAndDisablesCapture() {
        var requests = 0
        compose.setContent {
            CurrenSeeTheme {
                HomeScreen(
                    HomeUiState(camera = CameraStatus.PermissionRequired), {}, {},
                    onCameraAction = { requests++ }
                )
            }
        }
        compose.onNodeWithText("Aktifkan izin kamera", substring = true).assertExists()
        compose.onNodeWithText("Ulangi suara").assertIsNotEnabled()
        compose.onNodeWithText("Kenali uang").assertIsNotEnabled()
        compose.onNodeWithText("Aktifkan kamera").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithText("Belum ada hasil", substring = true).assertExists()
        compose.onNodeWithText("Rp", substring = true).assertDoesNotExist()
    }

    @Test
    fun actionsFollowRecognitionAndSpeechAvailability() {
        val state = mutableStateOf(HomeUiState(RecognitionState.Processing))
        var speechRequests = 0
        compose.setContent {
            CurrenSeeTheme {
                HomeScreen(state.value, {}, { speechRequests++ })
            }
        }
        compose.onNodeWithText("Sedang mengenali uang…", substring = true).assertExists()
        compose.onNodeWithText("Kenali uang").assertIsNotEnabled()
        compose.onNodeWithText("Ulangi suara").assertIsNotEnabled()

        // Test fixture; aplikasi tidak menghasilkan nominal contoh ini.
        compose.runOnIdle {
            state.value = HomeUiState(RecognitionState.Recognized("Rp50.000"))
        }
        compose.onNodeWithText("Rp50.000", substring = true).assertExists()
        compose.onNodeWithText("Kenali uang").assertIsEnabled()
        compose.onNodeWithText("Ulangi suara").assertIsNotEnabled()

        compose.runOnIdle { state.value = state.value.copy(isSpeechAvailable = true) }
        compose.onNodeWithText("Ulangi suara").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, speechRequests) }

        compose.runOnIdle {
            state.value = HomeUiState(RecognitionState.Unrecognized, isSpeechAvailable = true)
        }
        compose.onNodeWithText("Uang belum dikenali", substring = true).assertExists()
        compose.onNodeWithText("Ulangi suara").assertIsNotEnabled()
    }

    @Test
    fun normalScreenKeepsPrimaryActionVisible() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                CurrenSeeTheme {
                    HomeScreen(HomeUiState(camera = CameraStatus.Ready), {}, {})
                }
            }
        }
        compose.onNodeWithText("Kenali uang").assertIsDisplayed()
        compose.onNodeWithText("Ulangi suara").assertIsDisplayed()
        saveScreenshot("normal")
    }

    @Test
    fun blockedPermissionAndCaptureFailureHaveAccessibleActions() {
        val state = mutableStateOf(HomeUiState(camera = CameraStatus.PermissionBlocked))
        compose.setContent { CurrenSeeTheme { HomeScreen(state.value, {}, {}) } }
        compose.onNodeWithText("Buka pengaturan").assertIsEnabled()
        compose.onNodeWithText("Kenali uang").assertIsNotEnabled()
        compose.runOnIdle {
            state.value = HomeUiState(RecognitionState.Capturing, camera = CameraStatus.Ready)
        }
        compose.onNodeWithText("Sedang mengambil gambar…", substring = true).assertExists()
        compose.onNodeWithText("Kenali uang").assertIsNotEnabled()
        compose.runOnIdle {
            state.value = HomeUiState(RecognitionState.CaptureFailed, camera = CameraStatus.Ready)
        }
        compose.onNodeWithText("Gambar gagal diambil", substring = true).assertExists()
        compose.onNodeWithText("Kenali uang").assertIsEnabled()
    }

    @Test
    fun denominationsWrapWithoutClippingAndControlsRemainReachable() {
        val denomination = mutableStateOf("Rp1.000")
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                CurrenSeeTheme {
                    Box(Modifier.size(320.dp, 568.dp)) {
                        HomeScreen(
                            HomeUiState(RecognitionState.Recognized(denomination.value)), {}, {}
                        )
                    }
                }
            }
        }
        for (scale in listOf(1f, 2f)) {
            for (amount in listOf("Rp1.000", "Rp50.000", "Rp100.000")) {
                compose.runOnIdle {
                    fontScale.value = scale
                    denomination.value = amount
                }
                val nominal = compose.onNodeWithText(amount, useUnmergedTree = true)
                nominal.performScrollTo().assertIsDisplayed()
                saveScreenshot("${amount.replace(".", "")}_font${scale.toInt()}")
                nominal.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayouts ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    getLayouts(layouts)
                    assertEquals(1, layouts.size)
                    val layout = layouts.single()
                    assertFalse(
                        "Nominal terpotong: $amount, font $scale, size=${layout.size}, " +
                            "width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, " +
                            "lines=${layout.lineCount}, paragraph=${layout.multiParagraph.height}",
                        layout.hasVisualOverflow
                    )
                    for (line in 1 until layout.lineCount) {
                        val digits = layout.layoutInput.text.text
                            .substring(layout.getLineStart(line), layout.getLineEnd(line))
                            .count { it.isDigit() }
                        assertFalse("Kelompok ribuan terbelah: $amount", digits in 1..2)
                    }
                }
                compose.onNodeWithText("Kenali uang").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Ulangi suara").performScrollTo()
                    .assertIsDisplayed().assertIsNotEnabled()
            }
        }
        saveScreenshot("small_font2_controls")
    }

    private fun saveScreenshot(name: String) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(cache, "currensee_$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
