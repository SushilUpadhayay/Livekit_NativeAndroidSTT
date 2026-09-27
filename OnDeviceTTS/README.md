# On-Device TTS Android Application

An Android application written in Kotlin that connects to **LiveKit**, publishes the device microphone stream to LiveKit for server/agent Speech-To-Text (STT), and synthesizes incoming assistant responses locally on the device using Android's native `TextToSpeech` engine.

---

## Architecture Comparison

| Feature | OnDeviceSTT | OnDeviceTTS (This App) |
| :--- | :--- | :--- |
| **Speech-To-Text (STT)** | Native on-device (`android.speech.SpeechRecognizer`) | **LiveKit STT** (Device mic streamed to LiveKit room via WebRTC) |
| **Text-To-Speech (TTS)** | Cloud / Agent TTS (Remote audio track played via WebRTC) | **On-Device Native TTS** (`android.speech.tts.TextToSpeech`) |
| **Remote Audio** | Enabled (receives assistant voice stream) | **Muted** (ensures exclusive playback for native TTS) |
| **Interruption** | VAD detects speech -> stops remote audio & sends `__INTERRUPT__` | Speaking/mic unmuted -> halts on-device TTS (`tts.stop()`) & sends `__INTERRUPT__` |

---

## Data Flow

1. **User Connects**: App fetches token from FastAPI server (`http://<ip>:8000/token`) and joins LiveKit room.
2. **User Speaks**: Microphone audio is published via LiveKit WebRTC (`setMicrophoneEnabled(true)`).
3. **LiveKit STT**: The LiveKit Room / Agent receives the audio track and transcribes the speech. Transcriptions are sent back as `RoomEvent.TranscriptionReceived`.
4. **Agent Response**: The agent produces text (via `TranscriptionReceived` or `DataReceived`).
5. **On-Device TTS**: The Android app feeds the response text to Android's native `TextToSpeech.speak()`.
6. **Instant Interruptibility**: Tapping the mic or speaking halts `TextToSpeech.stop()` and sends `__INTERRUPT__` to the agent.

---

## How to Open & Build

### 1. Open in Android Studio
1. Open Android Studio.
2. Select **Open** and choose `D:\Livekit_NativeAndroidSTT\OnDeviceTTS`.
3. Let Gradle sync dependencies.

### 2. Build via Command Line
Run the Gradle wrapper:
```bash
./gradlew assembleDebug
```
The output APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Connecting to Backend

1. Make sure your LiveKit FastAPI token server is running:
   ```bash
   cd D:\Livekit_NativeAndroidSTT\self_agent
   python server.py
   ```
2. In the app:
   - Tap or long-tap on the **● Disconnected** status text to configure the token server URL (e.g., `http://192.168.1.159:8000/token`).
   - Tap **Connect** to join the room and start conversing.
