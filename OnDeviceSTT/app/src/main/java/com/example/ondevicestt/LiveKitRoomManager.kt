@file:OptIn(io.livekit.android.annotations.Beta::class)

package com.example.ondevicestt

import android.content.Context
import android.media.MediaRecorder
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        fun onActiveSpeakersChanged(isRemoteSpeaking: Boolean)
        fun onRemoteAudioTrackSubscribed(track: RemoteAudioTrack)
        fun onNexaTranscription(sender: String, text: String, isFinal: Boolean, isFromUser: Boolean = false)
        fun onNexaDataReceived(sender: String, text: String)
        fun onReconnected()
    }

    companion object {
        private const val TAG = "LiveKitChat"
    }

    var room: Room? = null
        private set

    var currentRemoteAudioTrack: RemoteAudioTrack? = null
        private set

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

        Log.d(TAG, "[LIVEKIT-TOKEN] Requesting token via TokenService for room=$roomName, identity=$participantIdentity, agent=$agentName...")

        val tokenResponse = tokenService.fetchToken(
            tokenServerUrl = tokenServerUrl,
            roomName = roomName,
            participantIdentity = participantIdentity,
            agentName = agentName
        )

        Log.d(TAG, "[LIVEKIT-TOKEN] Token obtained. Connecting to ${tokenResponse.serverUrl}...")
        connect(tokenResponse.serverUrl, tokenResponse.participantToken)
    }

    suspend fun connect(url: String, token: String) {
        if (isConnected || isConnecting) return

        // Configure LiveKit to use MODE_NORMAL (MediaAudioType) for high-fidelity media playback
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
        Log.d(TAG, "[LIVEKIT-MIC] Connected to room: ${newRoom.name}")

        // Exclusive mic ownership for native SpeechRecognizer - do not publish WebRTC mic track
        newRoom.localParticipant.setMicrophoneEnabled(false)
        Log.i(TAG, "[MIC-CONFIG] WebRTC mic publishing disabled - exclusive mic ownership given to native SpeechRecognizer.")
        Log.i(TAG, "[MIC-STATE] LiveKit WebRTC microphone publishing DISABLED (setMicrophoneEnabled=false)")

        routingManager.logAudioDiagnostics("AUDIO-DIAG")
        callback.onRoomConnected(newRoom.name ?: "")
    }

    suspend fun setMicrophoneEnabled(enabled: Boolean) {
        // Keep LiveKit mic publishing disabled so SpeechRecognizer retains exclusive ownership
        room?.localParticipant?.setMicrophoneEnabled(false)
        Log.i(TAG, "[LIVEKIT-MIC] WebRTC mic publishing remains disabled (exclusive SpeechRecognizer mode).")
        Log.i(TAG, "[MIC-STATE] LiveKit setMicrophoneEnabled($enabled) called -> enforced FALSE")
    }

    suspend fun publishData(text: String) {
        try {
            room?.localParticipant?.publishData(
                data = text.toByteArray(Charsets.UTF_8)
            )
            Log.i(TAG, "[DATA-CHANNEL] Published user transcript to NEXA: \"$text\"")
        } catch (e: Exception) {
            Log.e(TAG, "[DATA-CHANNEL] Failed to publish data", e)
        }
    }

    fun muteRemoteAudio() {
        try {
            currentRemoteAudioTrack?.setVolume(0.0)
            Log.i(TAG, "[INTERRUPT] Instant local TTS cutoff: RemoteAudioTrack volume set to 0.0")
        } catch (e: Exception) {
            Log.e(TAG, "[INTERRUPT] Failed to mute remote audio track", e)
        }
    }

    fun unmuteRemoteAudio() {
        try {
            currentRemoteAudioTrack?.setVolume(1.0)
            Log.d(TAG, "[REMOTE-AUDIO] RemoteAudioTrack volume restored to 1.0")
        } catch (e: Exception) {
            Log.e(TAG, "[REMOTE-AUDIO] Failed to unmute remote audio track", e)
        }
    }

    suspend fun disconnect() {
        try {
            currentRemoteAudioTrack = null
            Log.i(TAG, "[MIC-STATE] LiveKit disconnect() called. Releasing room and WebRTC audio.")
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
                Log.i(TAG, "[LIVEKIT-MIC] RoomEvent.Connected")
                routingManager.enforceSpeakerphoneIfNoHeadset()
                callback.onRoomConnected(room?.name ?: "")
            }

            is RoomEvent.Disconnected -> {
                Log.i(TAG, "[LIVEKIT-MIC] RoomEvent.Disconnected")
                callback.onRoomDisconnected()
            }

            is RoomEvent.FailedToConnect -> {
                Log.e(TAG, "[LIVEKIT-MIC] RoomEvent.FailedToConnect: ${event.error.message}")
                callback.onRoomConnectFailed(event.error)
            }

            is RoomEvent.ActiveSpeakersChanged -> {
                val remoteSpeakers = event.speakers.filter { it.identity != room?.localParticipant?.identity }
                val isRemoteSpeaking = remoteSpeakers.isNotEmpty()
                if (isRemoteSpeaking) {
                    unmuteRemoteAudio()
                }
                callback.onActiveSpeakersChanged(isRemoteSpeaking)
            }

            is RoomEvent.TrackSubscribed -> {
                val track = event.track
                val participant = event.participant
                if (track is AudioTrack) {
                    Log.i(
                        TAG,
                        "[SPEAKER-DEBUG] Remote AudioTrack SUBSCRIBED: name=${track.name}, from=${participant.identity?.value ?: participant.sid.value}, enabled=${track.enabled}"
                    )
                    (track as? RemoteAudioTrack)?.let { remoteAudio ->
                        currentRemoteAudioTrack = remoteAudio
                        remoteAudio.setVolume(1.0)
                        Log.i(TAG, "[SPEAKER-DEBUG] RemoteAudioTrack volume set to 1.0")
                        callback.onRemoteAudioTrackSubscribed(remoteAudio)
                    }
                    routingManager.logAudioDiagnostics("SPEAKER-DEBUG")
                }
            }

            is RoomEvent.Reconnected -> {
                Log.i(TAG, "[LIVEKIT-ROOM] Room reconnected.")
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
                    val senderName = if (isFromUser) "You" else (participant?.identity?.value ?: "Assistant")

                    val fullText = event.transcriptionSegments.joinToString(" ") { it.text }.trim()
                    val isFinal = event.transcriptionSegments.any { it.final }

                    if (fullText.isNotEmpty()) {
                        callback.onNexaTranscription(senderName, fullText, isFinal, isFromUser)
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
                        callback.onNexaDataReceived(senderName, rawText)
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
