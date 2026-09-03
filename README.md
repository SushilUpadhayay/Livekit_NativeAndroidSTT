# On-Device STT (minimal test app)

A tiny Kotlin app to test Android's native, on-device speech-to-text.

## What it does
- Asks for microphone permission when you tap the mic
- Mic button starts listening using Android's built-in `SpeechRecognizer`
- Requests the on-device model when the phone has one (`EXTRA_PREFER_OFFLINE`) - falls back automatically if not available
- Transcribed text appears in the text box on screen

## How to open it
1. Open Android Studio → "Open" → select this `OnDeviceSTT` folder.
2. Let Gradle sync (it will download the wrapper/dependencies the first time - needs internet).
3. Run on a real device (emulators usually don't have a working speech recognition service).

## Notes
- `minSdk = 24`, targets `compileSdk/targetSdk = 34`.
- No extra UI libraries - just `androidx.core` and `androidx.appcompat`, kept deliberately lightweight.
- Currently set to English (`en-US`) via `RecognizerIntent.EXTRA_LANGUAGE`. Change that string in `MainActivity.kt` if you need a different locale.
- If the phone doesn't have a speech engine at all (rare, but some stripped-down ROMs), the app shows a toast saying STT isn't available.
