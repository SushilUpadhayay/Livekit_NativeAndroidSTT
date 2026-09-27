package com.example.ondevicetts

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

class OnDeviceTtsManager(
    private val context: Context,
    private val callback: TtsCallback
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "OnDeviceTTS"
    }

    interface TtsCallback {
        fun onTtsInitialized(engineName: String, locale: Locale)
        fun onTtsInitFailed(errorMessage: String)
        fun onSpeechStarted(utteranceId: String)
        fun onSpeechDone(utteranceId: String)
        fun onSpeechError(utteranceId: String, errorCode: Int)
    }

    private var tts: TextToSpeech? = null
    var isInitialized: Boolean = false
        private set

    var isSpeaking: Boolean = false
        private set

    init {
        Log.i(TAG, "Initializing Android TextToSpeech engine...")
        val tempTts = TextToSpeech(context.applicationContext, null)
        val hasGoogleTts = tempTts.engines.any { it.name == "com.google.android.tts" }
        tempTts.shutdown()

        if (hasGoogleTts) {
            Log.i(TAG, "Preferring Google TextToSpeech (com.google.android.tts)")
            tts = TextToSpeech(context.applicationContext, this, "com.google.android.tts")
        } else {
            Log.i(TAG, "Using system default TextToSpeech")
            tts = TextToSpeech(context.applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts?.defaultEngine ?: "Default"
            val locale = Locale.US
            val result = tts?.setLanguage(locale)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Locale $locale not directly supported, falling back to device default locale")
                tts?.setLanguage(Locale.getDefault())
            }

            // Set media speaker attributes so sound plays loud and clear through main speakers
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS utterance started: $utteranceId")
                    isSpeaking = true
                    callback.onSpeechStarted(utteranceId ?: "")
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS utterance completed: $utteranceId")
                    isSpeaking = false
                    callback.onSpeechDone(utteranceId ?: "")
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS utterance error: $utteranceId")
                    isSpeaking = false
                    callback.onSpeechError(utteranceId ?: "", -1)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e(TAG, "TTS utterance error: $utteranceId, errorCode=$errorCode")
                    isSpeaking = false
                    callback.onSpeechError(utteranceId ?: "", errorCode)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    Log.d(TAG, "TTS utterance stopped: $utteranceId, interrupted=$interrupted")
                    isSpeaking = false
                    callback.onSpeechDone(utteranceId ?: "")
                }
            })

            isInitialized = true
            @Suppress("DEPRECATION")
            val activeLocale = tts?.voice?.locale ?: tts?.language ?: Locale.getDefault()
            Log.i(TAG, "TextToSpeech successfully initialized with engine=$engine, locale=$activeLocale")
            callback.onTtsInitialized(engine, activeLocale)
        } else {
            val err = "Failed to initialize TextToSpeech (status code $status)"
            Log.e(TAG, err)
            isInitialized = false
            callback.onTtsInitFailed(err)
        }
    }

    /**
     * Synthesizes and speaks text using on-device TTS.
     * @param text The text to speak.
     * @param flush If true, flushes any currently speaking utterance immediately.
     */
    fun speak(text: String, flush: Boolean = true): String {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "Cannot speak: TextToSpeech is not initialized yet.")
            return ""
        }

        val cleanText = text.trim()
        if (cleanText.isEmpty()) return ""

        val utteranceId = UUID.randomUUID().toString()
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD

        val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val musicVol = am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: -1
        val maxVol = am?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: -1
        Log.i(TAG, "Current STREAM_MUSIC volume: $musicVol / $maxVol")

        if (musicVol == 0 && am != null && maxVol > 0) {
            val targetVol = (maxVol * 0.7).toInt().coerceAtLeast(1)
            Log.w(TAG, "STREAM_MUSIC volume is 0! Automatically setting volume to $targetVol / $maxVol")
            try {
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVol, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Could not adjust stream volume", e)
            }
        }

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
        }

        Log.i(TAG, "Speaking text via on-device TTS (queueMode=${if (flush) "FLUSH" else "ADD"}): \"$cleanText\"")
        val res = tts?.speak(cleanText, queueMode, params, utteranceId)
        Log.i(TAG, "tts?.speak result=$res (0=SUCCESS, -1=ERROR), utteranceId=$utteranceId")
        return utteranceId
    }

    /**
     * Instantly stops any ongoing or queued TTS speech.
     */
    fun stop() {
        if (isSpeaking || tts?.isSpeaking == true) {
            Log.i(TAG, "Stopping on-device TTS speech immediately.")
            tts?.stop()
            isSpeaking = false
        }
    }

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch)
    }

    fun shutdown() {
        Log.i(TAG, "Shutting down OnDeviceTtsManager...")
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        isSpeaking = false
    }
}
