package com.example.ondevicestt

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class OnDeviceSpeechManager(
    private val context: Context,
    private val callback: SpeechCallback
) {

    interface SpeechCallback {
        fun onReadyForSpeech()
        fun onSpeechStarted()
        fun onSpeechEnded()
        fun onPartialTranscript(text: String)
        fun onFinalTranscript(text: String)
        fun onAudioLevel(rmsdB: Float)
        fun onError(errorCode: Int, errorMessage: String, speechDetectedPreviously: Boolean)
    }

    companion object {
        private const val TAG = "LiveKitChat"

        fun getErrorText(errorCode: Int): String = when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error (3)"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error (5)"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions (9)"
            SpeechRecognizer.ERROR_NETWORK -> "Network error (2)"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout (1)"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match (7)"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy (8)"
            SpeechRecognizer.ERROR_SERVER -> "Server error (4)"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout (6)"
            else -> "Error code: $errorCode"
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    var isListening: Boolean = false
        private set

    private var lastRmsLogTime = 0L
    private var speechDetectedInSession = false
    var isEnabled: Boolean = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var isSystemMuted = false

    private fun muteSystemSounds() {
        try {
            if (!isSystemMuted) {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
                try {
                    audioManager?.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                } catch (_: Exception) {}
                isSystemMuted = true
                mainHandler.postDelayed({
                    unmuteSystemSounds()
                }, 500L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not mute system stream", e)
        }
    }

    private fun unmuteSystemSounds() {
        try {
            if (isSystemMuted) {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
                try {
                    audioManager?.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: Exception) {}
                isSystemMuted = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not unmute system stream", e)
        }
    }

    init {
        initSpeechRecognizer()
    }

    fun initSpeechRecognizer() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
                        setRecognitionListener(recognitionListener)
                    }
                    Log.d(TAG, "Initialized Native On-Device SpeechRecognizer (Hardware Offline Engine).")
                    Log.i(TAG, "[MIC-STATE] SpeechRecognizer INITIALIZED (Native On-Device SpeechRecognizer)")
                } else if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(recognitionListener)
                    }
                    Log.d(TAG, "Initialized Standard SpeechRecognizer (On-Device Preferred).")
                    Log.i(TAG, "[MIC-STATE] SpeechRecognizer INITIALIZED (Standard SpeechRecognizer)")
                } else {
                    Log.w(TAG, "Speech recognition is not available on this device.")
                    Log.w(TAG, "[MIC-STATE] SpeechRecognizer UNAVAILABLE on this device")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize SpeechRecognizer", e)
                Log.e(TAG, "[MIC-STATE] SpeechRecognizer initialization FAILED", e)
            }
        }
    }

    fun startListening() {
        mainHandler.post {
            if (isListening || !isEnabled) {
                Log.d(TAG, "[MIC-STATE] startListening() ignored: isListening=$isListening, isEnabled=$isEnabled")
                return@post
            }

            if (speechRecognizer == null) {
                initSpeechRecognizer()
            }

            try {
                muteSystemSounds()
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
                }
                isListening = true
                Log.d(TAG, "[DEBUG] STT START with language: ${Locale.getDefault().toLanguageTag()}")
                Log.i(TAG, "[MIC-STATE] SpeechRecognizer.startListening() CALLED (competing with VAD AudioRecord if active)")
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                unmuteSystemSounds()
                isListening = false
                Log.e(TAG, "Exception calling startListening", e)
                Log.e(TAG, "[MIC-STATE] SpeechRecognizer.startListening() EXCEPTION", e)
                callback.onError(SpeechRecognizer.ERROR_CLIENT, "Exception starting SpeechRecognizer: ${e.message}", false)
            }
        }
    }

    fun stopListening() {
        unmuteSystemSounds()
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            if (isListening) {
                try {
                    speechRecognizer?.cancel()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cancelling SpeechRecognizer", e)
                } finally {
                    isListening = false
                    Log.d(TAG, "[STT-NATIVE] STT STOPPED")
                    Log.i(TAG, "[MIC-STATE] SpeechRecognizer.cancel() executed -> STT STOPPED (Mic released by SpeechRecognizer)")
                }
            }
        }
    }

    fun destroy() {
        Log.i(TAG, "[MIC-STATE] SpeechRecognizer destroy() called")
        unmuteSystemSounds()
        stopListening()
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            unmuteSystemSounds()
            isListening = true
            speechDetectedInSession = false
            Log.d(TAG, "[STT-NATIVE] READY - Listening for user speech...")
            callback.onReadyForSpeech()
        }

        override fun onBeginningOfSpeech() {
            unmuteSystemSounds()
            speechDetectedInSession = true
            Log.d(TAG, "[STT-NATIVE] USER SPEECH DETECTED (Voice Activity Triggered)")
            callback.onSpeechStarted()
        }

        override fun onRmsChanged(rmsdB: Float) {
            val now = System.currentTimeMillis()
            if (now - lastRmsLogTime > 800 && rmsdB > 0.5f) {
                lastRmsLogTime = now
                Log.d(TAG, "[STT-AUDIO-LEVEL] Mic input level: ${"%.1f".format(rmsdB)} dB")
            }
            callback.onAudioLevel(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "[STT-NATIVE] USER SPEECH ENDED (Processing audio...)")
            isListening = false
            callback.onSpeechEnded()
        }

        override fun onError(errorCode: Int) {
            unmuteSystemSounds()
            isListening = false
            val errorMsg = getErrorText(errorCode)
            Log.w(TAG, "[STT-NATIVE] ERROR: code=$errorCode ($errorMsg) | SpeechDetectedPreviously=$speechDetectedInSession")

            if (errorCode == SpeechRecognizer.ERROR_CLIENT || errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                initSpeechRecognizer()
            }

            callback.onError(errorCode, errorMsg, speechDetectedInSession)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val recognizedText = matches?.firstOrNull()?.trim().orEmpty()

            if (recognizedText.isNotEmpty()) {
                Log.i(TAG, "[STT-NATIVE] FINAL TRANSCRIPT: \"$recognizedText\"")
                callback.onFinalTranscript(recognizedText)
            } else {
                Log.w(TAG, "[STT-NATIVE] Empty results in onResults -> treating as NO_MATCH")
                callback.onError(SpeechRecognizer.ERROR_NO_MATCH, getErrorText(SpeechRecognizer.ERROR_NO_MATCH), speechDetectedInSession)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val partialText = matches[0].trim()
                if (partialText.isNotEmpty()) {
                    Log.d(TAG, "[STT-NATIVE] PARTIAL TRANSCRIPT: \"$partialText\"")
                    callback.onPartialTranscript(partialText)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
