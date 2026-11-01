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

/**
 * TTS yang bisa menyambung ulang. Di HP dengan RAM kecil (mis. MIUI), proses mesin TTS
 * Google bisa dimatikan sistem saat aplikasi masih hidup; objek TextToSpeech lalu
 * mengembalikan ERROR ("not bound to TTS engine") dan tidak pulih sendiri.
 * Di sini ERROR dideteksi, mesin dibuat ulang, dan ucapan terakhir diulang satu kali.
 */
class SpeechController(context: Context) : SpeechOutput {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var pending: String? = null

    override var isAvailable by mutableStateOf(false)
        private set

    init { connect() }

    private fun connect() {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(appContext) { status -> onEngineInit(engine, status) }
        tts = engine
    }

    private fun onEngineInit(engine: TextToSpeech?, status: Int) {
        // Abaikan callback dari mesin lama yang sudah diganti/ditutup.
        if (engine == null || closed || engine !== tts) {
            engine?.shutdown()
            return
        }
        if (status != TextToSpeech.SUCCESS) {
            Log.e("SpeechController", "TTS init gagal, status=$status")
            ready = false
            isAvailable = false
            return
        }
        val result = engine.setLanguage(Locale.forLanguageTag("id-ID"))
        ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        isAvailable = ready
        Log.d("SpeechController", "TTS siap, setLanguage=$result, isAvailable=$isAvailable")
        pending?.let { text ->
            pending = null
            if (ready) speakNow(text, allowRecover = false) // sekali saja, hindari loop
        }
    }

    override fun speak(text: String) {
        if (closed) return
        if (!ready) { // mesin sedang menyambung: simpan, ucapkan setelah siap
            pending = text
            if (tts == null) connect()
            return
        }
        speakNow(text, allowRecover = true)
    }

    private fun speakNow(text: String, allowRecover: Boolean) {
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "currensee")
            ?: TextToSpeech.ERROR
        if (result == TextToSpeech.ERROR) {
            Log.w("SpeechController", "speak gagal (mesin TTS terputus), allowRecover=$allowRecover")
            if (allowRecover) recover(text)
        }
    }

    private fun recover(text: String) {
        pending = text
        ready = false
        val old = tts
        tts = null
        runCatching { old?.shutdown() }
        connect()
    }

    override fun stop() {
        if (ready) runCatching { tts?.stop() }
    }

    override fun close() {
        closed = true
        pending = null
        ready = false
        runCatching { tts?.shutdown() }
        tts = null
    }
}