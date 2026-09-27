package com.example.ondevicetts

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.audio.AudioSwitchHandler

class AudioDeviceRoutingManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioRouting"

        fun audioModeToString(mode: Int): String = when (mode) {
            AudioManager.MODE_NORMAL -> "MODE_NORMAL(0)"
            AudioManager.MODE_IN_CALL -> "MODE_IN_CALL(2)"
            AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION(3)"
            else -> "MODE(${mode})"
        }
    }

    val audioHandler: AudioSwitchHandler by lazy {
        AudioSwitchHandler(context.applicationContext).apply {
            audioMode = AudioManager.MODE_NORMAL
            audioStreamType = AudioManager.STREAM_MUSIC
            audioAttributeUsageType = android.media.AudioAttributes.USAGE_MEDIA
            audioAttributeContentType = android.media.AudioAttributes.CONTENT_TYPE_SPEECH
            preferredDeviceList = listOf(
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.WiredHeadset::class.java,
                AudioDevice.Speakerphone::class.java,
                AudioDevice.Earpiece::class.java,
            )
            registerAudioDeviceChangeListener { audioDevices, selectedAudioDevice ->
                Log.i(TAG, "Active device: ${selectedAudioDevice?.name}, Available: ${audioDevices.map { it.name }}")
            }
        }
    }

    fun getAudioManager(): AudioManager {
        return context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    fun enforceSpeakerphoneIfNoHeadset() {
        val am = getAudioManager()
        val available = audioHandler.availableAudioDevices
        val hasHeadset = available.any { it is AudioDevice.BluetoothHeadset || it is AudioDevice.WiredHeadset }
        if (!hasHeadset) {
            val speakerDevice = available.firstOrNull { it is AudioDevice.Speakerphone }
            if (speakerDevice != null) {
                audioHandler.selectDevice(speakerDevice)
                Log.i(TAG, "Selected AudioDevice.Speakerphone via AudioSwitchHandler")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    am.clearCommunicationDevice()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clearCommunicationDevice", e)
                }
            }

            try {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set isSpeakerphoneOn", e)
            }
        }
    }

    fun logAudioDiagnostics(tagPrefix: String) {
        val am = getAudioManager()
        val voiceVol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        val maxVoiceVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val musicVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxMusicVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentDevice = audioHandler.selectedAudioDevice?.name ?: "auto"

        Log.i(
            TAG,
            "[$tagPrefix] AudioMode=${audioModeToString(am.mode)} | ActiveDevice=$currentDevice | VoiceVol=$voiceVol/$maxVoiceVol | MusicVol=$musicVol/$maxMusicVol"
        )
    }
}
