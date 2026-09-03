@file:OptIn(io.livekit.android.annotations.Beta::class)

package com.example.ondevicestt

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.AudioTrack
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.util.LoggingLevel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LiveKitChat"

        // LiveKit Server WebSocket URL
        private const val LIVEKIT_URL = "wss://vc-test.nextai.asia"

        // LiveKit Access Token (JWT)
        private const val LIVEKIT_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJuYW1lIjoidXNlciIsIm1ldGFkYXRhIjoie1widGVuYW50X2lkXCI6IFwiNjllZjQwZDVhMzQyMTVjYmQ0YTIzZjExXCIsIFwicm9vbV9uYW1lXCI6IFwidjJfYjNlNjA5NjdcIiwgXCJ1c2VyX2lkXCI6IFwiMzcwODJcIiwgXCJwcm9tcHRfaWRcIjogXCI2YTczMjA5NDk0Y2I5NTA1MjVkZjJiZjJcIn0iLCJ2aWRlbyI6eyJyb29tQ3JlYXRlIjp0cnVlLCJyb29tTGlzdCI6dHJ1ZSwicm9vbVJlY29yZCI6dHJ1ZSwicm9vbUFkbWluIjp0cnVlLCJyb29tSm9pbiI6dHJ1ZSwicm9vbSI6InYyX2IzZTYwOTY3IiwiY2FuUHVibGlzaCI6dHJ1ZSwiY2FuU3Vic2NyaWJlIjp0cnVlLCJjYW5QdWJsaXNoRGF0YSI6dHJ1ZSwiY2FuVXBkYXRlT3duTWV0YWRhdGEiOnRydWV9LCJyb29tQ29uZmlnIjp7ImFnZW50cyI6W3siYWdlbnROYW1lIjoibmV4YV9hZ2VudCIsIm1ldGFkYXRhIjoie1widGVuYW50X2lkXCI6IFwiNjllZjQwZDVhMzQyMTVjYmQ0YTIzZjExXCIsIFwicm9vbV9uYW1lXCI6IFwidjJfYjNlNjA5NjdcIiwgXCJ1c2VyX2lkXCI6IFwiMzcwODJcIiwgXCJwcm9tcHRfaWRcIjogXCI2YTczMjA5NDk0Y2I5NTA1MjVkZjJiZjJcIiwgXCJqb2JfaWRcIjogXCI2YTk5M2ViNTdmMDA4ZWI4OGIwMjQ5OWZcIiwgXCJ0cmFuc2NyaXB0X2lkXCI6IFwiNmE5OTNlYjU3ZjAwOGViODhiMDI0OTllXCJ9In1dfSwic3ViIjoidXNlciIsImlzcyI6IkFQSTgyTlZ2N2hNbjZybyIsIm5iZiI6MTc4ODQyNzk1NywiZXhwIjoxNzg4NDQ5NTU3fQ._aCgabwJ3GlRl8Fux7zZIjKx4f51ADYiFoW4lr8kU1U"
    }

    private lateinit var connectionStatusText: TextView
    private lateinit var headerActionButton: Button
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var emptyStateView: View
    private lateinit var micButton: Button
    private lateinit var micLabel: TextView

    private lateinit var chatAdapter: ChatAdapter
    private var room: Room? = null
    private var eventJob: Job? = null
    private var isMicEnabled = false
    private var isNexaSpeaking = false
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val mainHandler = Handler(Looper.getMainLooper())

    // On-Device SpeechRecognizer
    private var speechRecognizer: SpeechRecognizer? = null
    private var isSttListening = false
    private var isRestartPending = false

    // Permission launcher for microphone access
    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Microphone permission granted.")
            performConnect()
        } else {
            Toast.makeText(
                this,
                "Microphone permission is required for voice conversation.",
                Toast.LENGTH_LONG
            ).show()
            updateConnectionUi(ConnectionState.DISCONNECTED)
        }
    }

    private enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Enable full LiveKit & WebRTC logging for real-time audio diagnostics
        LiveKit.loggingLevel = LoggingLevel.DEBUG
        LiveKit.enableWebRTCLogging = true

        // Initialize UI Elements
        connectionStatusText = findViewById(R.id.connectionStatusText)
        headerActionButton = findViewById(R.id.headerActionButton)
        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        emptyStateView = findViewById(R.id.emptyStateView)
        micButton = findViewById(R.id.micButton)
        micLabel = findViewById(R.id.micLabel)

        // Setup Chat RecyclerView
        chatAdapter = ChatAdapter()
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        chatRecyclerView.layoutManager = layoutManager
        chatRecyclerView.adapter = chatAdapter

        // Initialize On-Device SpeechRecognizer on UI Thread
        initOnDeviceSpeechRecognizer()

        // Setup Click Listeners
        headerActionButton.setOnClickListener {
            if (room?.state == Room.State.CONNECTED || room?.state == Room.State.CONNECTING) {
                disconnectRoom()
            } else {
                checkPermissionAndConnect()
            }
        }

        micButton.setOnClickListener {
            handleMicButtonClick()
        }

        updateConnectionUi(ConnectionState.DISCONNECTED)
    }

    private fun initOnDeviceSpeechRecognizer() {
        runOnUiThread {
            try {
                if (speechRecognizer != null) {
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                    speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply {
                        setRecognitionListener(speechRecognitionListener)
                    }
                    Log.d(TAG, "Initialized Native On-Device SpeechRecognizer (Hardware Offline Engine).")
                } else if (SpeechRecognizer.isRecognitionAvailable(this)) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                        setRecognitionListener(speechRecognitionListener)
                    }
                    Log.d(TAG, "Initialized Standard SpeechRecognizer (On-Device Preferred).")
                } else {
                    Log.w(TAG, "Speech recognition is not available on this device.")
                    Toast.makeText(this, "Speech recognition service not found on device", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize SpeechRecognizer", e)
            }
        }
    }

    private var isMutedForStt = false

    private fun muteSpeechChime(mute: Boolean) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (mute && !isMutedForStt) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                isMutedForStt = true
            } else if (!mute && isMutedForStt) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                isMutedForStt = false
            }
        } catch (e: Exception) {
            // Ignored if restricted by device policy or DND requirements
        }
    }

    private fun startOnDeviceStt() {
        mainHandler.post {
            if (isNexaSpeaking) {
                Log.d(TAG, "[DEBUG] STT START blocked: NEXA is currently speaking")
                return@post
            }

            if (isSttListening) {
                return@post
            }

            if (!isMicEnabled) {
                return@post
            }

            if (speechRecognizer == null) {
                initOnDeviceSpeechRecognizer()
            }

            try {
                // Temporarily mute system chime right before activating recognizer
                muteSpeechChime(true)

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                isSttListening = true
                Log.d(TAG, "[DEBUG] STT START")
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                muteSpeechChime(false)
                isSttListening = false
                Log.e(TAG, "Exception calling startListening", e)
                scheduleSttRestart(1000)
            }
        }
    }

    private fun stopOnDeviceStt() {
        mainHandler.removeCallbacksAndMessages(null)
        isRestartPending = false
        muteSpeechChime(false)
        mainHandler.post {
            if (isSttListening) {
                try {
                    // Use cancel() instead of stopListening() to cleanly abort without throwing ERROR_CLIENT
                    speechRecognizer?.cancel()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cancelling SpeechRecognizer", e)
                } finally {
                    isSttListening = false
                    Log.d(TAG, "[STT-NATIVE] STT STOPPED")
                }
            }
        }
    }

    private fun scheduleSttRestart(delayMillis: Long) {
        if (!isMicEnabled || room?.state != Room.State.CONNECTED || isRestartPending || isNexaSpeaking) return
        isRestartPending = true
        mainHandler.postDelayed({
            isRestartPending = false
            if (isMicEnabled && room?.state == Room.State.CONNECTED && !isNexaSpeaking) {
                startOnDeviceStt()
            }
        }, delayMillis)
    }

    private val speechRecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isSttListening = true
            // Restore stream volume immediately once recognizer is ready
            muteSpeechChime(false)
            Log.d(TAG, "[STT-NATIVE] READY - Listening for user speech...")
        }

        override fun onBeginningOfSpeech() {
            muteSpeechChime(false)
            Log.d(TAG, "[STT-NATIVE] USER SPEECH DETECTED")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "[STT-NATIVE] USER SPEECH ENDED")
            isSttListening = false
        }

        override fun onError(errorCode: Int) {
            muteSpeechChime(false)
            isSttListening = false
            val errorMsg = getErrorText(errorCode)
            Log.w(TAG, "[STT-NATIVE] ERROR: code=$errorCode ($errorMsg)")

            // Cleanly recreate recognizer if client state became invalid or busy
            if (errorCode == SpeechRecognizer.ERROR_CLIENT || errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                initOnDeviceSpeechRecognizer()
            }

            // Auto restart listening after a brief delay if session is active and NEXA is not speaking
            if (isMicEnabled && room?.state == Room.State.CONNECTED && !isNexaSpeaking) {
                val delay = when (errorCode) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_AUDIO -> 800L
                    SpeechRecognizer.ERROR_CLIENT -> 600L
                    else -> 300L
                }
                scheduleSttRestart(delay)
            }
        }

        override fun onResults(results: Bundle?) {
            isSttListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)

            if (isNexaSpeaking) {
                Log.d(TAG, "[STT-NATIVE] Result discarded because NEXA was speaking")
                return
            }

            if (!matches.isNullOrEmpty()) {
                val recognizedText = matches[0].trim()
                if (recognizedText.isNotEmpty()) {
                    Log.i(TAG, "[STT-NATIVE] FINAL TRANSCRIPT: \"$recognizedText\"")
                    runOnUiThread {
                        val timestamp = timeFormatter.format(Date())
                        chatAdapter.finalizeUserInterimMessage("You", recognizedText, timestamp)
                        emptyStateView.visibility = View.GONE
                        chatRecyclerView.visibility = View.VISIBLE
                        chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                    }
                }
            }

            // Continue listening for subsequent user speech if NEXA is not speaking
            if (isMicEnabled && room?.state == Room.State.CONNECTED && !isNexaSpeaking) {
                scheduleSttRestart(200)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (isNexaSpeaking) {
                return
            }

            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val partialText = matches[0].trim()
                if (partialText.isNotEmpty()) {
                    Log.d(TAG, "[STT-NATIVE] PARTIAL TRANSCRIPT: \"$partialText\"")
                    runOnUiThread {
                        val timestamp = timeFormatter.format(Date())
                        chatAdapter.updateUserInterimMessage("You", partialText, timestamp)
                        emptyStateView.visibility = View.GONE
                        chatRecyclerView.visibility = View.VISIBLE
                        chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                    }
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun getErrorText(errorCode: Int): String {
        return when (errorCode) {
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

    private fun handleMicButtonClick() {
        if (room?.state == Room.State.CONNECTED) {
            toggleMicrophone()
        } else {
            checkPermissionAndConnect()
        }
    }

    private fun checkPermissionAndConnect() {
        val hasMicPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasMicPermission) {
            performConnect()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun performConnect() {
        if (room?.state == Room.State.CONNECTED || room?.state == Room.State.CONNECTING) {
            return
        }

        updateConnectionUi(ConnectionState.CONNECTING)
        Log.d(TAG, "[LIVEKIT-CONFIG] Connecting to $LIVEKIT_URL...")

        lifecycleScope.launch {
            try {
                // LiveKit requires CallAudioType for full-duplex VoIP streaming with AEC.
                // Configure AudioSwitchHandler to dynamically route to:
                // 1. Bluetooth Earphones (if paired)
                // 2. Wired Earphones (if plugged in)
                // 3. Main Phone Speakerphone (when no earphones connected)
                // 4. Earpiece (fallback)
                val audioHandler = AudioSwitchHandler(applicationContext).apply {
                    preferredDeviceList = listOf(
                        AudioDevice.BluetoothHeadset::class.java,
                        AudioDevice.WiredHeadset::class.java,
                        AudioDevice.Speakerphone::class.java,
                        AudioDevice.Earpiece::class.java,
                    )
                    registerAudioDeviceChangeListener { audioDevices, selectedAudioDevice ->
                        Log.i(TAG, "[AUDIO-ROUTING] Active device: ${selectedAudioDevice?.name}, Available: ${audioDevices.map { it.name }}")
                    }
                }
                val overrides = LiveKitOverrides(
                    audioOptions = AudioOptions(
                        audioOutputType = AudioType.CallAudioType(),
                        audioHandler = audioHandler,
                        javaAudioDeviceModuleCustomizer = { builder ->
                            Log.i(TAG, "[LIVEKIT-CONFIG] javaAudioDeviceModuleCustomizer: Setting AudioSource to MediaRecorder.AudioSource.VOICE_RECOGNITION")
                            builder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                        }
                    )
                )

                // Create LiveKit Room instance with custom audio options
                val newRoom = LiveKit.create(
                    appContext = applicationContext,
                    overrides = overrides
                ).also {
                    room = it
                }

                // Collect room events
                eventJob?.cancel()
                eventJob = lifecycleScope.launch {
                    newRoom.events.collect { event ->
                        handleRoomEvent(event)
                    }
                }

                // Connect to LiveKit room
                newRoom.connect(LIVEKIT_URL, LIVEKIT_TOKEN)
                Log.d(TAG, "[LIVEKIT-MIC] Connected to room: ${newRoom.name}")

                // Publish local microphone audio so NEXA receives user voice
                newRoom.localParticipant.setMicrophoneEnabled(true)
                isMicEnabled = true
                Log.i(TAG, "[LIVEKIT-MIC] Local microphone PUBLISHING active (audioSource=VOICE_RECOGNITION).")

                updateConnectionUi(ConnectionState.CONNECTED)

            } catch (e: Exception) {
                Log.e(TAG, "[LIVEKIT-CONFIG] Connection failed", e)
                Toast.makeText(this@MainActivity, "Connection failed: ${e.message}", Toast.LENGTH_SHORT).show()
                disconnectRoom()
            }
        }
    }

    private fun toggleMicrophone() {
        val currentRoom = room ?: return
        lifecycleScope.launch {
            try {
                val newState = !isMicEnabled
                currentRoom.localParticipant.setMicrophoneEnabled(newState)
                isMicEnabled = newState
                updateMicButtonState(isMicEnabled)

                if (isMicEnabled && !isNexaSpeaking) {
                    startOnDeviceStt()
                } else {
                    stopOnDeviceStt()
                }

                Log.i(TAG, "[LIVEKIT-MIC] Microphone enabled state toggled to: $isMicEnabled")
            } catch (e: Exception) {
                Log.e(TAG, "[LIVEKIT-MIC] Failed to toggle microphone", e)
                Toast.makeText(this@MainActivity, "Mic error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleRoomEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.Connected -> {
                Log.i(TAG, "[LIVEKIT-MIC] RoomEvent.Connected")
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val voiceVol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                val maxVoiceVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val musicVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxMusicVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val currentDevice = room?.audioSwitchHandler?.selectedAudioDevice?.name ?: "Automatic (Headphones/Speaker)"
                Log.i(TAG, "[AUDIO-ROUTING] Audio state - Device: $currentDevice, VoiceCallVol: $voiceVol/$maxVoiceVol, MusicVol: $musicVol/$maxMusicVol, AudioMode: ${am.mode}")

                runOnUiThread {
                    updateConnectionUi(ConnectionState.CONNECTED)
                    if (!isNexaSpeaking) {
                        startOnDeviceStt()
                    }
                }
            }

            is RoomEvent.Disconnected -> {
                Log.i(TAG, "[LIVEKIT-MIC] RoomEvent.Disconnected")
                runOnUiThread {
                    stopOnDeviceStt()
                    updateConnectionUi(ConnectionState.DISCONNECTED)
                }
            }

            is RoomEvent.FailedToConnect -> {
                Log.e(TAG, "[LIVEKIT-MIC] RoomEvent.FailedToConnect: ${event.error.message}")
                runOnUiThread {
                    stopOnDeviceStt()
                    updateConnectionUi(ConnectionState.DISCONNECTED)
                }
            }

            is RoomEvent.ActiveSpeakersChanged -> {
                val remoteSpeakers = event.speakers.filter { it.identity != room?.localParticipant?.identity }
                val isRemoteSpeaking = remoteSpeakers.isNotEmpty()

                if (isRemoteSpeaking && !isNexaSpeaking) {
                    // NEXA started speaking TTS audio
                    isNexaSpeaking = true
                    Log.d(TAG, "[NEXA-TTS] PLAYOUT STARTED (NEXA speaking)")
                    stopOnDeviceStt()
                    runOnUiThread {
                        chatAdapter.discardUserInterimMessage()
                    }
                } else if (!isRemoteSpeaking && isNexaSpeaking) {
                    // NEXA finished speaking TTS audio
                    isNexaSpeaking = false
                    Log.d(TAG, "[NEXA-TTS] PLAYOUT ENDED (NEXA idle)")
                    if (isMicEnabled && room?.state == Room.State.CONNECTED) {
                        // Wait 350ms for acoustic echo in room to clear before listening for user speech
                        scheduleSttRestart(350)
                    }
                }
            }

            is RoomEvent.ParticipantConnected -> {
                Log.d(TAG, "[LIVEKIT-ROOM] Participant joined: ${event.participant.identity?.value ?: event.participant.sid.value}")
            }

            is RoomEvent.ParticipantDisconnected -> {
                Log.d(TAG, "[LIVEKIT-ROOM] Participant left: ${event.participant.identity?.value ?: event.participant.sid.value}")
            }

            is RoomEvent.TrackSubscribed -> {
                val track = event.track
                val participant = event.participant
                if (track is AudioTrack) {
                    Log.i(TAG, "[NEXA-TTS] Remote audio track SUBSCRIBED: name=${track.name}, from=${participant.identity?.value ?: participant.sid.value}")
                    (track as? RemoteAudioTrack)?.let { remoteAudio ->
                        remoteAudio.setVolume(1.0)
                        Log.i(TAG, "[NEXA-TTS] Remote audio track volume confirmed active (1.0).")
                    }
                }
            }

            is RoomEvent.TrackUnsubscribed -> {
                Log.i(TAG, "[NEXA-TTS] Remote track unsubscribed: ${event.track.name}")
            }

            is RoomEvent.TrackMuted -> {
                Log.i(TAG, "[NEXA-TTS] Track muted: ${event.publication.sid}")
            }

            is RoomEvent.TrackUnmuted -> {
                Log.i(TAG, "[NEXA-TTS] Track unmuted: ${event.publication.sid}")
            }

            is RoomEvent.TranscriptionReceived -> {
                try {
                    val participant = event.participant
                    val isFromUser = participant != null && (
                        participant.identity == room?.localParticipant?.identity ||
                        participant.identity?.value == "user"
                    )
                    val senderName = if (isFromUser) "You" else (participant?.identity?.value ?: "NEXA")

                    val fullText = event.transcriptionSegments.joinToString(" ") { it.text }.trim()
                    val isFinal = event.transcriptionSegments.any { it.final }

                    Log.i(TAG, "[HOSTED-STT/NEXA] TranscriptionReceived [$senderName] (isFromUser=$isFromUser, final=$isFinal): \"$fullText\"")

                    if (fullText.isNotEmpty()) {
                        runOnUiThread {
                            val timestamp = timeFormatter.format(Date())
                            if (isFromUser) {
                                if (isFinal) {
                                    chatAdapter.finalizeUserInterimMessage("You", fullText, timestamp)
                                } else {
                                    chatAdapter.updateUserInterimMessage("You", fullText, timestamp)
                                }
                            } else {
                                if (isFinal) {
                                    chatAdapter.finalizeNexaInterimMessage(senderName, fullText, timestamp)
                                } else {
                                    chatAdapter.updateNexaInterimMessage(senderName, fullText, timestamp)
                                }
                            }
                            emptyStateView.visibility = View.GONE
                            chatRecyclerView.visibility = View.VISIBLE
                            chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling TranscriptionReceived", e)
                }
            }

            is RoomEvent.DataReceived -> {
                try {
                    val rawText = event.data.toString(Charsets.UTF_8).trim()
                    val senderName = event.participant?.identity?.value ?: "NEXA"
                    val isFromUser = event.participant == null || event.participant?.identity == room?.localParticipant?.identity

                    if (rawText.isNotEmpty() && !isFromUser) {
                        Log.d(TAG, "DataReceived from NEXA: $rawText")
                        runOnUiThread {
                            addChatMessage(
                                sender = senderName,
                                text = rawText,
                                isUser = false
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing DataReceived packet", e)
                }
            }

            else -> {
                Log.d(TAG, "RoomEvent: ${event.javaClass.simpleName}")
            }
        }
    }

    fun addChatMessage(sender: String, text: String, isUser: Boolean) {
        val timestamp = timeFormatter.format(Date())
        val message = ChatMessage(
            sender = sender,
            text = text,
            isUser = isUser,
            timestamp = timestamp
        )
        chatAdapter.addMessage(message)
        emptyStateView.visibility = View.GONE
        chatRecyclerView.visibility = View.VISIBLE
        chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
    }

    private fun disconnectRoom() {
        stopOnDeviceStt()
        lifecycleScope.launch {
            try {
                eventJob?.cancel()
                eventJob = null
                room?.disconnect()
                room?.release()
                room = null
                isMicEnabled = false
                isNexaSpeaking = false
                Log.d(TAG, "Room disconnected and resources released.")
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting", e)
            } finally {
                updateConnectionUi(ConnectionState.DISCONNECTED)
            }
        }
    }

    private fun updateConnectionUi(state: ConnectionState) {
        when (state) {
            ConnectionState.DISCONNECTED -> {
                connectionStatusText.text = "● Disconnected"
                connectionStatusText.setTextColor(Color.parseColor("#E53935"))
                headerActionButton.text = "Connect"
                headerActionButton.isEnabled = true
                updateMicButtonState(active = false, isConnected = false)
            }

            ConnectionState.CONNECTING -> {
                connectionStatusText.text = "● Connecting..."
                connectionStatusText.setTextColor(Color.parseColor("#FB8C00"))
                headerActionButton.text = "Connecting"
                headerActionButton.isEnabled = false
                micLabel.text = "Connecting to NEXA..."
            }

            ConnectionState.CONNECTED -> {
                connectionStatusText.text = "● Connected"
                connectionStatusText.setTextColor(Color.parseColor("#43A047"))
                headerActionButton.text = "Disconnect"
                headerActionButton.isEnabled = true
                updateMicButtonState(active = isMicEnabled, isConnected = true)
            }
        }
    }

    private fun updateMicButtonState(active: Boolean, isConnected: Boolean = true) {
        if (!isConnected) {
            micButton.text = "🎤"
            micButton.setBackgroundColor(Color.parseColor("#007AFF"))
            micLabel.text = "Tap to Connect & Speak"
        } else if (active) {
            micButton.text = "🎙️"
            micButton.setBackgroundColor(Color.parseColor("#E53935"))
            micLabel.text = "Microphone Active (Tap to Mute)"
        } else {
            micButton.text = "🎤"
            micButton.setBackgroundColor(Color.parseColor("#757575"))
            micLabel.text = "Microphone Muted (Tap to Unmute)"
        }
    }

    override fun onDestroy() {
        stopOnDeviceStt()
        speechRecognizer?.destroy()
        speechRecognizer = null
        eventJob?.cancel()
        room?.disconnect()
        room?.release()
        room = null
        super.onDestroy()
    }
}
