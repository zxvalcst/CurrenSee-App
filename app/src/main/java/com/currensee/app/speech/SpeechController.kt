package com.currensee.app.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

interface SpeechOutput : AutoCloseable {
    val isAvailable: Boolean
    fun speak(text: String)
    fun stop()
}

class SpeechController(context: Context) : TextToSpeech.OnInitListener, SpeechOutput {
    private val tts = TextToSpeech(context.applicationContext, this)

    override var isAvailable by mutableStateOf(false)
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e("SpeechController", "TTS init gagal, status=$status")
            isAvailable = false
            return
        }
        val result = tts.setLanguage(Locale.forLanguageTag("id-ID"))
        isAvailable = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        Log.d("SpeechController", "TTS siap, setLanguage=$result, isAvailable=$isAvailable")
    }

    override fun speak(text: String) {
        if (isAvailable) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "currensee")
    }

    override fun stop() { tts.stop() }

    override fun close() = tts.shutdown()
}
