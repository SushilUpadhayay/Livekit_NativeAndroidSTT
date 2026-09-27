package com.example.ondevicetts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.livekit.android.LiveKit
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.util.LoggingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(),
    OnDeviceTtsManager.TtsCallback,
    LiveKitRoomManager.RoomCallback {

    companion object {
        private const val TAG = "OnDeviceTTS"
        private const val DEFAULT_TOKEN_SERVER_URL = "http://172.16.20.79:8000/token"
        private const val PREFS_NAME = "livekit_tts_prefs"
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

    private lateinit var routingManager: AudioDeviceRoutingManager
    private lateinit var ttsManager: OnDeviceTtsManager
    private lateinit var roomManager: LiveKitRoomManager

    private var isMicEnabled = true
    private var currentAssistantTurnId: String? = null

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

        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

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
        if (saved.isNullOrEmpty() || saved.contains("192.168.1.159") || saved.contains("172.16.20.121") || saved.contains("172.16.20.118")) {
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
            .setMessage("Configure FastAPI token URL (e.g. http://172.16.20.79:8000/token):")
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
        ttsManager = OnDeviceTtsManager(this, this)
        roomManager = LiveKitRoomManager(this, routingManager, lifecycleScope, this)
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
        lifecycleScope.launch {
            roomManager.setMicrophoneEnabled(isMicEnabled)
        }
        updateMicButtonState(isMicEnabled, isConnected = true)
        Log.i(TAG, "Microphone state toggled: isMicEnabled=$isMicEnabled")
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
                updateConnectionUi(ConnectionState.CONNECTED)
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                Toast.makeText(this@MainActivity, "Connection failed: ${e.message}", Toast.LENGTH_SHORT).show()
                disconnectRoom()
            }
        }
    }

    private fun disconnectRoom() {
        isMicEnabled = false
        ttsManager.stop()
        lifecycleScope.launch {
            roomManager.disconnect()
            updateConnectionUi(ConnectionState.DISCONNECTED)
        }
    }

    private fun interruptTtsAndAgent() {
        Log.i(TAG, "Interrupting on-device TTS and sending __INTERRUPT__ to server agent")
        ttsManager.stop()
        currentAssistantTurnId = null
        lifecycleScope.launch {
            roomManager.publishData("__INTERRUPT__")
        }
    }

    // ==========================================
    // OnDeviceTtsManager.TtsCallback
    // ==========================================

    override fun onTtsInitialized(engineName: String, locale: Locale) {
        Log.i(TAG, "On-device TTS Initialized: $engineName ($locale)")
    }

    override fun onTtsInitFailed(errorMessage: String) {
        Log.e(TAG, "On-device TTS Init Failed: $errorMessage")
        runOnUiThread {
            Toast.makeText(this, "TTS Init Failed: $errorMessage", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSpeechStarted(utteranceId: String) {
        Log.d(TAG, "TTS speech started: $utteranceId")
    }

    override fun onSpeechDone(utteranceId: String) {
        Log.d(TAG, "TTS speech done: $utteranceId")
    }

    override fun onSpeechError(utteranceId: String, errorCode: Int) {
        Log.w(TAG, "TTS speech error: utteranceId=$utteranceId, errorCode=$errorCode")
    }

    // ==========================================
    // LiveKitRoomManager.RoomCallback
    // ==========================================

    override fun onRoomConnected(roomName: String) {
        runOnUiThread {
            isMicEnabled = true
            updateConnectionUi(ConnectionState.CONNECTED)
        }
    }

    override fun onRoomDisconnected() {
        runOnUiThread {
            isMicEnabled = false
            ttsManager.stop()
            updateConnectionUi(ConnectionState.DISCONNECTED)
        }
    }

    override fun onRoomConnectFailed(error: Throwable) {
        runOnUiThread {
            isMicEnabled = false
            ttsManager.stop()
            updateConnectionUi(ConnectionState.DISCONNECTED)
        }
    }

    override fun onUserTranscription(text: String, isFinal: Boolean) {
        // If user is speaking, interrupt ongoing on-device TTS immediately
        if (ttsManager.isSpeaking) {
            interruptTtsAndAgent()
        }

        runOnUiThread {
            val timestamp = timeFormatter.format(Date())
            if (isFinal) {
                chatAdapter.finalizeUserInterimMessage("You", text, timestamp)
            } else {
                chatAdapter.updateUserInterimMessage("You", text, timestamp)
            }
            emptyStateView.visibility = View.GONE
            chatRecyclerView.visibility = View.VISIBLE
            chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    override fun onAssistantTranscription(sender: String, text: String, isFinal: Boolean) {
        // Ignored to avoid duplicate chat bubble.
        // Assistant responses are streamed sentence-by-sentence via onAssistantSentenceStream with On-Device TTS.
    }

    override fun onAssistantSentenceStream(
        sender: String,
        sentence: String,
        turnId: String,
        index: Int,
        isFinal: Boolean
    ) {
        runOnUiThread {
            val timestamp = timeFormatter.format(Date())
            val displayName = "NEXA"
            val isFirstSentence = (index == 0 || turnId != currentAssistantTurnId)
            if (isFirstSentence) {
                currentAssistantTurnId = turnId
            }

            if (sentence.isNotEmpty()) {
                chatAdapter.appendNexaSentence(displayName, sentence, timestamp)
            }

            if (isFinal) {
                val fullText = chatAdapter.getLastNexaText()
                if (fullText.isNotEmpty()) {
                    chatAdapter.finalizeNexaInterimMessage(displayName, fullText, timestamp)
                }
            }

            emptyStateView.visibility = View.GONE
            chatRecyclerView.visibility = View.VISIBLE
            chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)

            if (sentence.isNotEmpty()) {
                // First sentence flushes previous speech (instant start); subsequent sentences queue seamlessly!
                ttsManager.speak(sentence, flush = isFirstSentence)
            }
        }
    }

    override fun onAssistantDataReceived(sender: String, text: String) {
        runOnUiThread {
            val timestamp = timeFormatter.format(Date())
            val displayName = "NEXA"
            val message = ChatMessage(
                sender = displayName,
                text = text,
                isUser = false,
                timestamp = timestamp
            )
            chatAdapter.addMessage(message)
            emptyStateView.visibility = View.GONE
            chatRecyclerView.visibility = View.VISIBLE
            chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)

            ttsManager.speak(text, flush = true)
        }
    }

    override fun onActiveSpeakersChanged(isRemoteSpeaking: Boolean) {
        Log.d(TAG, "Active speakers changed: isRemoteSpeaking=$isRemoteSpeaking")
    }

    override fun onReconnected() {
        runOnUiThread {
            Log.i(TAG, "Room reconnected.")
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
        ttsManager.shutdown()
        runBlocking(Dispatchers.IO) {
            roomManager.disconnect()
        }
        super.onDestroy()
    }
}
