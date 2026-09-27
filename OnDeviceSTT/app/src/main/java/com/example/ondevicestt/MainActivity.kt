@file:OptIn(io.livekit.android.annotations.Beta::class)

package com.example.ondevicestt

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import io.livekit.android.LiveKit
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.util.LoggingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.content.Context
import android.widget.EditText
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity(),
    OnDeviceSpeechManager.SpeechCallback,
    LiveKitRoomManager.RoomCallback {

    companion object {
        private const val TAG = "LiveKitChat"
        private const val DEFAULT_TOKEN_SERVER_URL = "http://172.16.20.79:8000/token" // change it to the ip of the connected wifi
        private const val PREFS_NAME = "livekit_prefs"
        private const val KEY_SERVER_URL = "token_server_url"
        private const val ROOM_NAME = "mobile-voice-room"
        private const val AGENT_NAME = "my-agent"
    }

    private lateinit var connectionStatusText: TextView
    private lateinit var headerActionButton: Button
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var emptyStateView: View
    private lateinit var micButton: Button
    private lateinit var micLabel: TextView

    private lateinit var chatAdapter: ChatAdapter
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var routingManager: AudioDeviceRoutingManager
    private lateinit var speechManager: OnDeviceSpeechManager
    private lateinit var roomManager: LiveKitRoomManager
    private lateinit var vad: VoiceActivityDetector

    private var isMicEnabled = false
    private var isNexaSpeaking = false
    private var isHandoffInProgress = false

    private enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (recordAudioGranted) {
            Log.d(TAG, "RECORD_AUDIO permission granted.")
            performConnect()
        } else {
            Toast.makeText(this, "Microphone permission is required for voice conversation.", Toast.LENGTH_LONG).show()
            updateConnectionUi(ConnectionState.DISCONNECTED)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        LiveKit.loggingLevel = LoggingLevel.DEBUG
        LiveKit.enableWebRTCLogging = true

        initUi()
        initManagers()
        updateConnectionUi(ConnectionState.DISCONNECTED)
    }

    private fun initUi() {
        connectionStatusText = findViewById(R.id.connectionStatusText)
        headerActionButton = findViewById(R.id.headerActionButton)
        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        emptyStateView = findViewById(R.id.emptyStateView)
        micButton = findViewById(R.id.micButton)
        micLabel = findViewById(R.id.micLabel)

        chatAdapter = ChatAdapter()
        chatRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        chatRecyclerView.adapter = chatAdapter

        headerActionButton.setOnClickListener {
            if (roomManager.isConnected || roomManager.isConnecting) {
                disconnectRoom()
            } else {
                checkPermissionAndConnect()
            }
        }

        connectionStatusText.setOnClickListener {
            showServerUrlDialog()
        }
        connectionStatusText.setOnLongClickListener {
            showServerUrlDialog()
            true
        }

        micButton.setOnClickListener {
            handleMicButtonClick()
        }
    }

    private fun getServerUrl(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_SERVER_URL, null)
        if (saved.isNullOrEmpty() || saved.contains("192.168.1.159") || saved.contains("172.16.20.121")) {
            return DEFAULT_TOKEN_SERVER_URL
        }
        return saved
    }

    private fun setServerUrl(url: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
    }

    private fun showServerUrlDialog() {
        val currentUrl = getServerUrl()
        val input = EditText(this).apply {
            setText(currentUrl)
            setSelection(currentUrl.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Token Server URL")
            .setMessage("Configure FastAPI token URL (e.g. http://192.168.1.159:8000/token):")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newUrl = input.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    setServerUrl(newUrl)
                    Toast.makeText(this, "Saved: $newUrl", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Reset Default") { _, _ ->
                setServerUrl(DEFAULT_TOKEN_SERVER_URL)
                Toast.makeText(this, "Reset to: $DEFAULT_TOKEN_SERVER_URL", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun initManagers() {
        routingManager = AudioDeviceRoutingManager(this)
        routingManager.audioHandler.start()
        speechManager = OnDeviceSpeechManager(this, this)
        roomManager = LiveKitRoomManager(this, routingManager, lifecycleScope, this)
        vad = VoiceActivityDetector(this) {
            handleVadSpeechDetected()
        }
    }

    private fun handleVadSpeechDetected() {
        if (!isMicEnabled || !roomManager.isConnected || speechManager.isListening || isHandoffInProgress) {
            Log.d(
                TAG,
                "[HANDOFF] VAD triggered but ignored (micEnabled=$isMicEnabled, connected=${roomManager.isConnected}, isListening=${speechManager.isListening}, handoffInProgress=$isHandoffInProgress)"
            )
            return
        }
        isHandoffInProgress = true

        lifecycleScope.launch {
            try {
                Log.i(TAG, "[HANDOFF] VAD detected speech! isNexaSpeaking=$isNexaSpeaking")

                // 1. If agent is speaking, cut off audio immediately and notify agent
                if (isNexaSpeaking) {
                    Log.i(TAG, "[INTERRUPT] Agent speaking during speech detection. Muting remote audio & publishing __INTERRUPT__")
                    isNexaSpeaking = false
                    roomManager.muteRemoteAudio()
                    roomManager.publishData("__INTERRUPT__")
                }

                // 2. Stop VAD and await its full release before starting SpeechRecognizer
                Log.i(TAG, "[HANDOFF] Stopping VAD AudioRecord (awaiting release)...")
                vad.stop()
                Log.i(TAG, "[HANDOFF] VAD AudioRecord released. Starting SpeechRecognizer...")

                // 3. Start SpeechRecognizer
                speechManager.startListening()
            } catch (e: Exception) {
                Log.e(TAG, "[HANDOFF] Error during VAD to SpeechRecognizer handoff", e)
                if (isMicEnabled && roomManager.isConnected) {
                    vad.start()
                }
            } finally {
                isHandoffInProgress = false
            }
        }
    }

    private fun checkPermissionAndConnect() {
        val permissionsNeeded = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missingPermissions = permissionsNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            performConnect()
        } else {
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun performConnect() {
        if (roomManager.isConnected || roomManager.isConnecting) return

        updateConnectionUi(ConnectionState.CONNECTING)

        val serverUrl = getServerUrl()
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Connecting to LiveKit via token server: $serverUrl, agent: $AGENT_NAME")
                roomManager.connectWithDevTokenServer(
                    tokenServerUrl = serverUrl,
                    roomName = ROOM_NAME,
                    agentName = AGENT_NAME
                )
                isMicEnabled = true
                speechManager.isEnabled = true
                Log.i(TAG, "[MIC-STATE] MainActivity performConnect: starting VAD listening...")
                vad.start()
                updateConnectionUi(ConnectionState.CONNECTED)
            } catch (e: Exception) {
                Log.e(TAG, "[LIVEKIT-CONFIG] Connection failed", e)
                Toast.makeText(this@MainActivity, "Connection failed: ${e.message}", Toast.LENGTH_SHORT).show()
                disconnectRoom()
            }
        }
    }

    private fun disconnectRoom() {
        Log.i(TAG, "[MIC-STATE] MainActivity disconnectRoom: stopping VAD and SpeechRecognizer...")
        isMicEnabled = false
        speechManager.isEnabled = false
        speechManager.stopListening()
        lifecycleScope.launch {
            vad.stop()
            roomManager.disconnect()
            isNexaSpeaking = false
            updateConnectionUi(ConnectionState.DISCONNECTED)
        }
    }

    private fun handleMicButtonClick() {
        if (roomManager.isConnected) {
            toggleMicrophone()
        } else {
            checkPermissionAndConnect()
        }
    }

    private fun toggleMicrophone() {
        val newState = !isMicEnabled
        isMicEnabled = newState
        speechManager.isEnabled = isMicEnabled
        Log.i(TAG, "[MIC-STATE] MainActivity toggleMicrophone: isMicEnabled=$isMicEnabled")
        lifecycleScope.launch {
            if (isMicEnabled) {
                speechManager.stopListening()
                vad.start()
            } else {
                vad.stop()
                speechManager.stopListening()
            }
        }
        updateMicButtonState(isMicEnabled)
        Log.i(TAG, "[STT-MIC] Native SpeechRecognizer microphone state toggled to: $isMicEnabled")
    }

    // ==========================================
    // SpeechCallback (OnDeviceSpeechManager)
    // ==========================================

    override fun onReadyForSpeech() {}

    override fun onSpeechStarted() {
        Log.d(TAG, "[STT-NATIVE] onSpeechStarted received")
    }

    override fun onSpeechEnded() {}

    override fun onAudioLevel(rmsdB: Float) {}

    override fun onPartialTranscript(text: String) {
        runOnUiThread {
            val timestamp = timeFormatter.format(Date())
            chatAdapter.updateUserInterimMessage("You", text, timestamp)
            emptyStateView.visibility = View.GONE
            chatRecyclerView.visibility = View.VISIBLE
            chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    override fun onFinalTranscript(text: String) {
        if (isNexaSpeaking) {
            isNexaSpeaking = false
        }

        lifecycleScope.launch {
            roomManager.publishData(text)
        }

        runOnUiThread {
            val timestamp = timeFormatter.format(Date())
            chatAdapter.finalizeUserInterimMessage("You", text, timestamp)
            emptyStateView.visibility = View.GONE
            chatRecyclerView.visibility = View.VISIBLE
            chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
        }

        lifecycleScope.launch {
            speechManager.stopListening()
            delay(100L)
            if (isMicEnabled && roomManager.isConnected) {
                Log.i(TAG, "[HANDOFF] Final transcript processed -> restarting VAD")
                vad.start()
            }
        }
    }

    override fun onError(errorCode: Int, errorMessage: String, speechDetectedPreviously: Boolean) {
        Log.w(TAG, "[HANDOFF] SpeechRecognizer error: code=$errorCode ($errorMessage), returning to VAD")
        lifecycleScope.launch {
            speechManager.stopListening()
            delay(100L)
            if (isMicEnabled && roomManager.isConnected) {
                Log.i(TAG, "[HANDOFF] Error handled -> restarting VAD")
                vad.start()
            }
        }
    }

    // ==========================================
    // RoomCallback (LiveKitRoomManager)
    // ==========================================

    override fun onRoomConnected(roomName: String) {
        runOnUiThread {
            isMicEnabled = true
            speechManager.isEnabled = true
            if (!speechManager.isListening) {
                vad.start()
            }
            updateConnectionUi(ConnectionState.CONNECTED)
        }
    }

    override fun onRoomDisconnected() {
        runOnUiThread {
            isMicEnabled = false
            speechManager.isEnabled = false
            speechManager.stopListening()
            lifecycleScope.launch {
                vad.stop()
            }
            updateConnectionUi(ConnectionState.DISCONNECTED)
        }
    }

    override fun onRoomConnectFailed(error: Throwable) {
        runOnUiThread {
            isMicEnabled = false
            speechManager.isEnabled = false
            speechManager.stopListening()
            lifecycleScope.launch {
                vad.stop()
            }
            updateConnectionUi(ConnectionState.DISCONNECTED)
        }
    }

    override fun onActiveSpeakersChanged(isRemoteSpeaking: Boolean) {
        isNexaSpeaking = isRemoteSpeaking
        Log.d(TAG, "[AGENT-SPEECH-STATE] isNexaSpeaking=$isNexaSpeaking")
    }

    override fun onRemoteAudioTrackSubscribed(track: RemoteAudioTrack) {}

    override fun onNexaTranscription(sender: String, text: String, isFinal: Boolean, isFromUser: Boolean) {
        runOnUiThread {
            val timestamp = timeFormatter.format(Date())
            val isUser = isFromUser || sender == "You"
            if (isUser) {
                if (isFinal) {
                    chatAdapter.finalizeUserInterimMessage("You", text, timestamp)
                } else {
                    chatAdapter.updateUserInterimMessage("You", text, timestamp)
                }
            } else {
                if (isFinal) {
                    chatAdapter.finalizeNexaInterimMessage(sender, text, timestamp)
                } else {
                    chatAdapter.updateNexaInterimMessage(sender, text, timestamp)
                }
            }
            emptyStateView.visibility = View.GONE
            chatRecyclerView.visibility = View.VISIBLE
            chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    override fun onNexaDataReceived(sender: String, text: String) {
        runOnUiThread {
            val timestamp = timeFormatter.format(Date())
            val message = ChatMessage(
                sender = sender,
                text = text,
                isUser = false,
                timestamp = timestamp
            )
            chatAdapter.addMessage(message)
            emptyStateView.visibility = View.GONE
            chatRecyclerView.visibility = View.VISIBLE
            chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    override fun onReconnected() {
        runOnUiThread {
            // Room reconnected
        }
    }

    // ==========================================
    // UI Helpers
    // ==========================================

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
        routingManager.audioHandler.stop()
        speechManager.destroy()
        runBlocking(Dispatchers.IO) {
            vad.stop()
            roomManager.disconnect()
        }
        super.onDestroy()
    }
}
