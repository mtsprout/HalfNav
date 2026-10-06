package io.github.mtsprout.halfnav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks directions with the phone's text-to-speech voice, ducking music while it talks the way
 * navigation apps do.
 */
class VoiceGuide(context: Context) : TextToSpeech.OnInitListener {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private val tts = TextToSpeech(context.applicationContext, this)
    private val ids = AtomicInteger()
    private var ready = false
    private val pending = mutableListOf<String>()

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Text-to-speech unavailable (status $status)")
            return
        }
        tts.language = Locale.US
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                audio.requestAudioFocus(focus)
            }

            override fun onDone(utteranceId: String?) {
                if (!tts.isSpeaking) audio.abandonAudioFocusRequest(focus)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                audio.abandonAudioFocusRequest(focus)
            }
        })
        ready = true
        pending.forEach(::say)
        pending.clear()
    }

    fun speak(phrases: List<String>) {
        phrases.forEach { if (ready) say(it) else pending += it }
    }

    private fun say(text: String) {
        Log.i(TAG, text)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "halfnav-${ids.incrementAndGet()}")
    }

    /** Stop talking right away (e.g. when muted). */
    fun silence() {
        pending.clear()
        if (ready) tts.stop()
        audio.abandonAudioFocusRequest(focus)
    }

    fun shutdown() {
        silence()
        tts.shutdown()
    }

    private companion object {
        const val TAG = "HalfNavVoice"
    }
}
