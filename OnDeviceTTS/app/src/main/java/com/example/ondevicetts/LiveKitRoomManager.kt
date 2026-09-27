@file:OptIn(io.livekit.android.annotations.Beta::class)

package com.example.ondevicetts

import android.content.Context
import android.util.Log
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.AudioTrack
import io.livekit.android.room.track.RemoteAudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

class LiveKitRoomManager(
    private val context: Context,
    private val routingManager: AudioDeviceRoutingManager,
    private val scope: CoroutineScope,
    private val callback: RoomCallback
) {
    private val tokenService = TokenService()

    interface RoomCallback {
        fun onRoomConnected(roomName: String)
        fun onRoomDisconnected()
        fun onRoomConnectFailed(error: Throwable)
        fun onUserTranscription(text: String, isFinal: Boolean)
        fun onAssistantTranscription(sender: String, text: String, isFinal: Boolean)
        fun onAssistantDataReceived(sender: String, text: String)
        fun onAssistantSentenceStream(sender: String, sentence: String, turnId: String, index: Int, isFinal: Boolean)
        fun onActiveSpeakersChanged(isRemoteSpeaking: Boolean)
        fun onReconnected()
    }

    companion object {
        private const val TAG = "LiveKitRoom"
    }

    var room: Room? = null
        private set

    var currentRemoteAudioTrack: RemoteAudioTrack? = null
        private set

    /**
     * When true (default), remote audio track is muted so only On-Device TTS is heard.
     */
    var muteRemoteAudioTrack: Boolean = true

    private var eventJob: Job? = null

    val isConnected: Boolean
        get() = room?.state == Room.State.CONNECTED

    val isConnecting: Boolean
        get() = room?.state == Room.State.CONNECTING

    suspend fun connectWithDevTokenServer(
        tokenServerUrl: String = TokenService.DEFAULT_TOKEN_SERVER_URL,
        roomName: String = "mobile-voice-room",
        participantIdentity: String = "android-user-${UUID.randomUUID().toString().take(8)}",
        agentName: String = "my-agent"
    ) {
        if (isConnected || isConnecting) return

        Log.d(TAG, "Requesting token for room=$roomName, identity=$participantIdentity, agent=$agentName...")

        val tokenResponse = tokenService.fetchToken(
            tokenServerUrl = tokenServerUrl,
            roomName = roomName,
            participantIdentity = participantIdentity,
            agentName = agentName
        )

        Log.d(TAG, "Token obtained. Connecting to ${tokenResponse.serverUrl}...")
        connect(tokenResponse.serverUrl, tokenResponse.participantToken)
    }

    suspend fun connect(url: String, token: String) {
        if (isConnected || isConnecting) return

        val newRoom = LiveKit.create(
            appContext = context.applicationContext,
            overrides = LiveKitOverrides(
                audioOptions = AudioOptions(
                    audioOutputType = AudioType.MediaAudioType(),
                    audioHandler = routingManager.audioHandler
                )
            )
        ).also {
            room = it
        }

        eventJob?.cancel()
        eventJob = scope.launch {
            newRoom.events.collect { event ->
                handleRoomEvent(event)
            }
        }

        newRoom.connect(url, token)
        Log.i(TAG, "Connected to LiveKit room: ${newRoom.name}")

        // Enable microphone so user's voice is streamed to LiveKit STT
        newRoom.localParticipant.setMicrophoneEnabled(true)
        Log.i(TAG, "Microphone enabled in LiveKit (streaming to cloud/agent STT).")

        routingManager.logAudioDiagnostics("CONNECT-DIAG")
        callback.onRoomConnected(newRoom.name ?: "")
    }

    suspend fun setMicrophoneEnabled(enabled: Boolean) {
        try {
            room?.localParticipant?.setMicrophoneEnabled(enabled)
            Log.i(TAG, "Microphone enabled state set to: $enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle microphone state", e)
        }
    }

    suspend fun publishData(text: String) {
        try {
            room?.localParticipant?.publishData(
                data = text.toByteArray(Charsets.UTF_8)
            )
            Log.i(TAG, "Published data: \"$text\"")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish data", e)
        }
    }

    fun muteRemoteAudio() {
        try {
            currentRemoteAudioTrack?.setVolume(0.0)
            Log.i(TAG, "RemoteAudioTrack volume set to 0.0")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mute remote audio track", e)
        }
    }

    fun unmuteRemoteAudio() {
        try {
            currentRemoteAudioTrack?.setVolume(1.0)
            Log.i(TAG, "RemoteAudioTrack volume restored to 1.0")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unmute remote audio track", e)
        }
    }

    suspend fun disconnect() {
        try {
            currentRemoteAudioTrack = null
            eventJob?.cancel()
            eventJob = null
            room?.disconnect()
            room?.release()
            room = null
            Log.d(TAG, "Room disconnected and resources released.")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting room", e)
        } finally {
            callback.onRoomDisconnected()
        }
    }

    private fun handleRoomEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.Connected -> {
                Log.i(TAG, "RoomEvent.Connected")
                routingManager.enforceSpeakerphoneIfNoHeadset()
                callback.onRoomConnected(room?.name ?: "")
            }

            is RoomEvent.Disconnected -> {
                Log.i(TAG, "RoomEvent.Disconnected")
                callback.onRoomDisconnected()
            }

            is RoomEvent.FailedToConnect -> {
                Log.e(TAG, "RoomEvent.FailedToConnect: ${event.error.message}")
                callback.onRoomConnectFailed(event.error)
            }

            is RoomEvent.ActiveSpeakersChanged -> {
                val remoteSpeakers = event.speakers.filter { it.identity != room?.localParticipant?.identity }
                val isRemoteSpeaking = remoteSpeakers.isNotEmpty()
                callback.onActiveSpeakersChanged(isRemoteSpeaking)
            }

            is RoomEvent.TrackSubscribed -> {
                val track = event.track
                val participant = event.participant
                if (track is AudioTrack) {
                    Log.i(
                        TAG,
                        "Remote AudioTrack subscribed: name=${track.name}, from=${participant.identity?.value ?: participant.sid.value}"
                    )
                    (track as? RemoteAudioTrack)?.let { remoteAudio ->
                        currentRemoteAudioTrack = remoteAudio
                        // Since OnDeviceTTS is used, mute remote audio track to avoid duplicate audio
                        if (muteRemoteAudioTrack) {
                            remoteAudio.setVolume(0.0)
                            Log.i(TAG, "RemoteAudioTrack muted (0.0) to give exclusive audio to on-device TTS.")
                        } else {
                            remoteAudio.setVolume(1.0)
                        }
                    }
                    routingManager.logAudioDiagnostics("TRACK-SUB-DIAG")
                }
            }

            is RoomEvent.Reconnected -> {
                Log.i(TAG, "Room reconnected.")
                callback.onReconnected()
            }

            is RoomEvent.TranscriptionReceived -> {
                try {
                    val participant = event.participant
                    val isFromUser = participant != null && (
                        participant.identity == room?.localParticipant?.identity ||
                        participant.identity?.value?.startsWith("android-user") == true ||
                        participant.identity?.value == "android-user" ||
                        participant.identity?.value == "user" ||
                        participant == room?.localParticipant
                    )
                    val fullText = event.transcriptionSegments.joinToString(" ") { it.text }.trim()
                    val isFinal = event.transcriptionSegments.any { it.final }

                    if (fullText.isNotEmpty() && isFromUser) {
                        callback.onUserTranscription(fullText, isFinal)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling TranscriptionReceived", e)
                }
            }

            is RoomEvent.DataReceived -> {
                try {
                    val rawText = event.data.toString(Charsets.UTF_8).trim()
                    val senderName = event.participant?.identity?.value ?: "NEXA"
                    val isFromUser = event.participant != null && (
                        event.participant?.identity == room?.localParticipant?.identity ||
                        event.participant?.identity?.value?.startsWith("android-user") == true
                    )

                    if (rawText.isNotEmpty() && !isFromUser) {
                        Log.d(TAG, "DataReceived from $senderName: $rawText")
                        if (rawText.startsWith("{") && rawText.endsWith("}")) {
                            try {
                                val json = org.json.JSONObject(rawText)
                                if (json.optString("type") == "assistant_sentence") {
                                    val sentence = json.optString("text")
                                    val turnId = json.optString("turn_id")
                                    val index = json.optInt("index", 0)
                                    val isFinal = json.optBoolean("is_final", false)
                                    callback.onAssistantSentenceStream(senderName, sentence, turnId, index, isFinal)
                                    return
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse json packet, treating as raw string", e)
                            }
                        }
                        callback.onAssistantDataReceived(senderName, rawText)
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
}
