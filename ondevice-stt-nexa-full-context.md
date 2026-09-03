# On-Device STT + LiveKit + NEXA — Full Context Explained

This document answers the full set of questions about what we've discussed, in plain language, with what's confirmed vs. what's still assumption clearly marked.

---

## 1. What was our original/current architecture?

NEXA is a live voice-calling system. Before this discussion, the flow was:

```
User's phone microphone
      │  (raw audio, sent live over LiveKit)
      ▼
   LiveKit Room  ── just carries the audio, like a phone line
      │
      ▼
  NEXA server-side agent
      │
      ▼
  VAD (detects when user is speaking) → Hosted STT (Nemotron/Whisper)
      │
      ▼
     LLM (generates a reply)
      │
      ▼
     TTS (turns reply into speech)
      │
      ▼
   LiveKit Room (carries audio back)
      │
      ▼
  User's phone speaker
```

Separately, Ambition Guru's (AG) app also had a different, simpler feature: recording a clip of speech and sending it to "our Whisper API" to get text back. That's a different, non-live use case from the live NEXA call above.

## 2. What architecture do we ultimately want?

Keep the live conversation feeling (mic → NEXA → LLM → TTS → speaker), but do the speech-to-text step **on the phone itself**, using Android's built-in STT, instead of sending audio to a server-hosted STT engine.

## 3. What exactly is the requirement for on-device STT?

- Must use **Android's native `SpeechRecognizer` API** specifically.
- Must **not** use Vosk, Sherpa-ONNX, Whisper.cpp, any other embedded/local neural STT model, or any other cloud STT API.
- The existing two-way LiveKit voice communication must keep working.
- Call recording must keep working (confirmed as a hard requirement).

## 4. What problem did we encounter on the Android/client side?

When `SpeechRecognizer` tried to run at the same time as LiveKit's microphone capture, it failed with error codes **5** and **13**. Two things fighting over the same microphone.

## 5. Why was the user's/NEXA's voice being incorrectly transcribed?

Separate issue from #4: if `SpeechRecognizer` is left listening while NEXA's TTS reply is playing out of the phone speaker, it can pick up that speaker sound through the mic and transcribe it — so NEXA saying "How are you?" could wrongly show up as if the *user* said "How are you?" This is an echo problem, not a permissions or setup problem.

## 6. What role is LiveKit currently playing?

Just the live audio "pipe" — like a phone line. It carries voice both directions between the user's phone and NEXA's server-side agent. It does no thinking, no transcription — just transport.

## 7. What role is the hosted STT currently playing?

It's the server-side engine (Nemotron, previously Whisper) that turns the raw audio LiveKit delivers into text, so the LLM has something to respond to. This is the piece we're trying to replace with on-device recognition.

## 8. Why does LiveKit normally need microphone audio in the current architecture?

Because the hosted STT needs the raw voice audio to do its job — and LiveKit is simply how that audio physically gets from the phone to the server.

## 9. Does LiveKit actually need microphone audio in our NEW desired architecture?

**Not for speech-to-text purposes anymore** — if the phone already turns speech into text locally, the LLM only needs text, not audio.

**But:** since call recording is a hard requirement, LiveKit's mic track still needs to keep publishing audio anyway — just for recording/storage, not for transcription. So the mic audio still needs to travel over LiveKit, just for a different reason than before.

## 10. What did we discover about Android SpeechRecognizer and microphone access?

- `SpeechRecognizer` runs as its own separate process — it can't share the exact same audio stream LiveKit is capturing.
- **Confirmed platform rule** (Android's official Compatibility Definition Document): while an app captures audio using the `VOICE_COMMUNICATION` or `CAMCORDER` audio source, Android is *required* to silence every other app's mic capture (except accessibility services). LiveKit uses `VOICE_COMMUNICATION` by default for calls — that's the actual cause of the errors in #4, not a random bug.
- Other audio sources, like `MIC` or `VOICE_RECOGNITION`, are **not** covered by that mandatory-silence rule — using one of those instead turns the conflict from "always blocked" into "best effort, device-dependent."
- LiveKit's Android SDK has a real, supported hook to change which audio source it uses (`javaAudioDeviceModuleCustomizer`, inside `AudioOptions`/`LiveKitOverrides`, passed into `Room.create()`), without modifying LiveKit's own code.
- LiveKit also supports pausing/resuming the local mic without leaving the call (`localParticipant.setMicrophoneEnabled(true/false)`).

## 11. What possible solutions did we consider?

- **A — Turn-based:** Pause LiveKit's mic → let `SpeechRecognizer` listen → get the text → resume LiveKit's mic.
- **B — Do nothing:** Leave LiveKit's mic on `VOICE_COMMUNICATION` the whole time.
- **C — Switch LiveKit's audio source:** Change it away from `VOICE_COMMUNICATION` (e.g., to `VOICE_RECOGNITION`) so the mandatory-silence rule no longer applies.

Separately, on the server side, we also compared:
- Sending recognized text straight to the LLM as a special one-off shortcut, **vs.**
- Treating on-device STT as a new, swappable STT engine option inside NEXA's existing pluggable STT setup.

## 12. Why did we reject or prefer each solution?

- **A (turn-based):** Rejected as the final answer — while `SpeechRecognizer` has exclusive mic access, LiveKit (and therefore the recording) gets silence. That breaks the recording requirement.
- **B (do nothing):** Rejected — this is the literal cause of the errors we saw. The OS-mandated rule means it will keep failing.
- **C (switch audio source):** Currently preferred — it lets LiveKit keep publishing (so recording stays intact) while removing the guaranteed block on `SpeechRecognizer`. Trade-off: lose `VOICE_COMMUNICATION`'s automatic echo cancellation, which needs real-device testing to confirm it's not a problem.
- **Text-to-LLM shortcut vs. new pluggable STT backend:** The pluggable-backend approach was preferred because NEXA already has this kind of swappable STT design, so it fits naturally and doesn't require touching the LLM/TTS code at all.

## 13. What is the solution/architecture we've currently decided to pursue?

- Keep using Android's native `SpeechRecognizer` only (per the hard requirement).
- Keep LiveKit's mic track publishing (for recording), but change its audio source away from `VOICE_COMMUNICATION` (Option C) — **pending real-device testing**.
- Add a state machine on the phone: `SpeechRecognizer` is stopped while NEXA's TTS is playing, ready right after, and listening while it's the user's turn — this solves the echo problem from #5.
- On NEXA's server, add a new STT backend option (fits into the existing pluggable STT setup) that doesn't transcribe audio itself — it just waits for the phone to send text over LiveKit's data channel, and treats that as the finished transcript.
- The hosted STT (Nemotron/Whisper) is simply not called for these clients anymore.

## 14. How will the new audio/text flow work end-to-end?

```
User speaks
   │
   ├─────────────────────────────┐
   │                             │
   ▼                             ▼
Phone mic (LiveKit track,     Phone mic (Android
audio source changed          SpeechRecognizer —
away from VOICE_COMMUNICATION) only "listening" during
   │                           the user's turn)
   ▼                             │
LiveKit Room                     ▼
   │                        Recognized text
   ▼                             │
NEXA recording pipeline    ┌─────┴─────┐
(stores audio, unchanged)  ▼           ▼
                     Chat bubble   LiveKit data channel
                     (shown         │
                      instantly     ▼
                      on phone)   NEXA agent (new STT
                                  backend — just relays
                                  the text, no transcribing)
                                       │
                                       ▼
                                      LLM
                                       │
                                       ▼
                                      TTS
                                       │
                                       ▼
                                  LiveKit Room
                                       │
                                       ▼
                                  Phone speaker
                          (SpeechRecognizer stops
                           listening while this plays,
                           to avoid transcribing it)
```

## 15. How will the user's recognized text be shown in the chat bubble?

As soon as `SpeechRecognizer` finishes and gives a final result, the app shows it in the chat bubble immediately, locally on the phone — it doesn't have to wait for a round trip to NEXA's server first.

## 16. How will we completely bypass/remove the hosted STT?

By adding the new "text-input" STT backend option to NEXA's existing pluggable STT setup, and configuring these clients to use it instead of Nemotron/Whisper. For these clients, incoming audio is only used for recording — it's never sent through VAD or any transcription step.

## 17. What needs to change on the Android/client side?

- Mic permission handling (already built in the test app from earlier).
- The listen/stop state machine tied to NEXA's TTS playback (stop while TTS plays, listen on the user's turn).
- Change LiveKit's local audio source away from `VOICE_COMMUNICATION` (pending testing).
- Send `SpeechRecognizer`'s final text over LiveKit's data channel, in addition to showing it in the local chat bubble.
- Keep publishing the LiveKit audio track the whole time (for recording) — no more muting/unmuting per turn.

## 18. What needs to change on the NEXA/server/agent side?

- A new STT backend option added to the existing pluggable STT factory — one that listens for text over the data channel instead of processing audio.
- No changes needed to the LLM or TTS logic — they keep working exactly as they do today.
- Recording pipeline itself shouldn't need changes, as long as it just needs the audio track to exist in the room.

## 19. What LiveKit functionality will we still use?

- Room/connection management (joining the call).
- Local audio track publishing (for recording only, not transcription).
- Data channel / text messages (to carry the transcript to NEXA).
- Delivering TTS audio back to the phone.

## 20. What LiveKit functionality will we no longer use?

- LiveKit's audio track is no longer used to feed NEXA's own transcription — NEXA doesn't run VAD/STT on it anymore for these clients.
- The hosted STT service (Nemotron/Whisper) isn't called at all for these clients.

## 21. What evidence supports that the problem is real?

- **Directly observed:** `SpeechRecognizer` errors 5 and 13 when running alongside LiveKit's active mic capture.
- **Android's official Compatibility Definition Document:** confirms a *mandatory* rule that silences all other apps' mic access while an app captures with `VOICE_COMMUNICATION` or `CAMCORDER`.
- **Android's own developer documentation** on audio sharing/concurrent capture: confirms privacy-sensitive sources get exclusive priority, while regular sources (`MIC`, `VOICE_RECOGNITION`) get best-effort sharing instead.
- **WebRTC community reports:** independently confirm only one audio capture session reliably works at a time on Android when using WebRTC's own capture path — consistent with what we saw.

## 22. What evidence supports that our proposed solution is technically possible?

- LiveKit's own published SDK documentation confirms `AudioOptions.javaAudioDeviceModuleCustomizer` exists and gives access to the underlying audio builder's `.setAudioSource()` method — a supported customization, not a hack.
- LiveKit's `setMicrophoneEnabled()` method is documented and works without disconnecting from the room.
- LiveKit officially supports data channels/text messages, and its Agents framework is documented to accept text input from a participant.
- NEXA's own code already has a pluggable STT setup supporting multiple backend types — adding one more fits the existing pattern.

## 23. What's confirmed fact vs. still an assumption?

**Confirmed:**
- The root cause of errors 5/13 (Android's mandatory silencing rule for `VOICE_COMMUNICATION`/`CAMCORDER`).
- The LiveKit SDK hooks needed (audio source override, mic pause/resume, data channel) all exist and are documented.
- NEXA already supports swappable STT backends.
- Recording is a hard requirement.
- Nepali support isn't a concern for this specific effort; NEXA doesn't need to work offline.

**Still assumptions — need real-device verification:**
- Whether switching the audio source away from `VOICE_COMMUNICATION` actually results in `SpeechRecognizer` getting real, usable audio at the same time LiveKit is publishing — Android's own docs call this "best effort," not guaranteed.
- Whether call audio quality (echo, background noise) holds up without `VOICE_COMMUNICATION`'s built-in processing.
- The exact message format NEXA's new STT backend should expect from the phone over the data channel — not yet designed.
- Whether the `job_id`/`transcript_id` fields seen in the room token are relevant to this new flow (asked earlier, not yet answered).
- Whether giving up barge-in (interrupting NEXA mid-response) is acceptable — flagged, not yet signed off.

## 24. What are the remaining risks / unanswered questions?

- Different phone brands/OEMs may behave differently with concurrent mic capture — needs testing across real devices, not just one.
- Audio quality/echo risk from moving off `VOICE_COMMUNICATION`.
- No barge-in during NEXA's responses — a real behavior change from a natural phone call.
- The data channel message format between phone and NEXA's new STT backend isn't designed yet.
- The `job_id`/`transcript_id` question from earlier is still open.
- How the phone will reliably know when TTS is playing to correctly gate `SpeechRecognizer` — likely straightforward since the phone itself plays that audio, but not yet implemented/verified.

## 25. Complete architecture — simple step-by-step flow

1. User taps to speak (or the app is in "listening" state after NEXA finishes talking).
2. Phone's `SpeechRecognizer` starts listening. At the same time, LiveKit's mic track is publishing audio (using a non-`VOICE_COMMUNICATION` source) purely for recording.
3. User finishes speaking. `SpeechRecognizer` returns final text.
4. That text is shown instantly in the chat bubble on the phone.
5. The same text is sent to NEXA over LiveKit's data channel.
6. NEXA's new STT backend receives the text (no audio transcription happens server-side).
7. The text goes to the LLM, which generates a reply.
8. The reply goes to TTS, producing speech audio.
9. That audio is sent back over LiveKit to the phone and played through the speaker.
10. While that plays, `SpeechRecognizer` is stopped (to avoid transcribing NEXA's own voice).
11. Once playback finishes, `SpeechRecognizer` goes back to "ready," and the cycle repeats for the next turn.

Meanwhile, the audio LiveKit carried in step 2 is stored by NEXA's existing recording pipeline, same as before — just no longer used for transcription.
