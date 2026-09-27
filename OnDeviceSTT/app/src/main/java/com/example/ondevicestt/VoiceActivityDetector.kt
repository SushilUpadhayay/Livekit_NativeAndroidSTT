package com.example.ondevicestt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class VoiceActivityDetector(
    private val context: Context,
    private val onSpeechDetected: () -> Unit
) {
    companion object {
        private const val TAG = "VoiceActivityDetector"
        private const val MODEL_ASSET = "silero_vad.onnx"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 512 // 32ms window @ 16kHz
        private const val THRESHOLD = 0.5f
        private const val SPEECH_COOLDOWN_MS = 500L
    }

    private var audioRecord: AudioRecord? = null
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    private val isRunning = AtomicBoolean(false)
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Silero VAD v4 recurrent state buffers: [2, 1, 64] float arrays
    private var hState = Array(2) { Array(1) { FloatArray(64) } }
    private var cState = Array(2) { Array(1) { FloatArray(64) } }
    private var lastSpeechDetectedTime = 0L
    private var nonSpeechFrameCount = 0L

    init {
        Log.i(TAG, "[VAD-INIT] sampleRate=$SAMPLE_RATE channels=MONO source=VOICE_COMMUNICATION frameSize=$FRAME_SIZE threshold=$THRESHOLD model=Silero_v4")
    }

    private fun resetRecurrentStates() {
        hState = Array(2) { Array(1) { FloatArray(64) } }
        cState = Array(2) { Array(1) { FloatArray(64) } }
        Log.d(TAG, "[VAD-INFER] Recurrent states (h, c) reset to zeros [2, 1, 64].")
    }

    private fun initOrtSession() {
        if (ortSession != null) return
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            ortSession = ortEnv?.createSession(modelBytes, options)
            Log.i(TAG, "[VAD-INIT] Silero VAD ONNX session initialized successfully. Model size=${modelBytes.size} bytes.")
        } catch (e: Exception) {
            Log.e(TAG, "[VAD-INIT] Failed to initialize ONNX session", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "[VAD-RECORD] start() called but detector is ALREADY running.")
            return
        }

        Log.i(TAG, "[VAD-RECORD] start() called. Initializing ONNX session & AudioRecord...")
        initOrtSession()
        resetRecurrentStates()

        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufSize, FRAME_SIZE * 2 * 2)

        Log.i(TAG, "[VAD-RECORD] AudioRecord parameters: sampleRate=$SAMPLE_RATE, channel=CHANNEL_IN_MONO, format=ENCODING_PCM_16BIT, minBufSize=$minBufSize, allocatedBufferSize=$bufferSize, frameSize=$FRAME_SIZE")

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            val initState = audioRecord?.state
            val audioSessionId = audioRecord?.audioSessionId ?: -1
            Log.i(TAG, "[VAD-RECORD] AudioRecord created: state=${if (initState == AudioRecord.STATE_INITIALIZED) "STATE_INITIALIZED" else "STATE_UNINITIALIZED ($initState)"}, audioSessionId=$audioSessionId, source=VOICE_COMMUNICATION")

            if (initState != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "[VAD-RECORD] AudioRecord initialization FAILED (state != STATE_INITIALIZED). Cannot start VAD.")
                isRunning.set(false)
                return
            }

            audioRecord?.startRecording()
            val recState = audioRecord?.recordingState
            Log.i(TAG, "[VAD-RECORD] AudioRecord.startRecording() executed. recordingState=${if (recState == AudioRecord.RECORDSTATE_RECORDING) "RECORDSTATE_RECORDING" else "RECORDSTATE_STOPPED ($recState)"}")

            captureJob = scope.launch {
                val pcmBuffer = ShortArray(FRAME_SIZE)
                val floatBuffer = FloatArray(FRAME_SIZE)

                while (isActive && isRunning.get()) {
                    var samplesRead = 0
                    while (samplesRead < FRAME_SIZE && isRunning.get()) {
                        val read = audioRecord?.read(pcmBuffer, samplesRead, FRAME_SIZE - samplesRead) ?: -1
                        if (read > 0) {
                            samplesRead += read
                        } else {
                            Log.w(TAG, "[VAD-RECORD] AudioRecord.read() returned non-positive value: $read (samplesReadSoFar=$samplesRead, target=$FRAME_SIZE)")
                            break
                        }
                    }

                    if (samplesRead == FRAME_SIZE) {
                        // 1. Compute AudioRecord diagnostics on raw PCM buffer
                        var pcmMin = Short.MAX_VALUE.toInt()
                        var pcmMax = Short.MIN_VALUE.toInt()
                        var sumSquares = 0.0
                        var isAllZeros = true
                        var pcmPeak = 0

                        for (i in 0 until FRAME_SIZE) {
                            val sample = pcmBuffer[i].toInt()
                            if (sample != 0) isAllZeros = false
                            if (sample < pcmMin) pcmMin = sample
                            if (sample > pcmMax) pcmMax = sample
                            val absSample = if (sample < 0) -sample else sample
                            if (absSample > pcmPeak) pcmPeak = absSample
                            val normalized = sample / 32768.0f
                            floatBuffer[i] = normalized
                            sumSquares += (normalized * normalized)
                        }

                        val rms = sqrt(sumSquares / FRAME_SIZE).toFloat()
                        val peak = pcmPeak / 32768.0f

                        Log.d(
                            TAG,
                            "[VAD-AUDIO] samples=$samplesRead rms=${"%.5f".format(rms)} peak=${"%.5f".format(peak)} min=$pcmMin max=$pcmMax zeros=$isAllZeros sr=$SAMPLE_RATE ch=1 src=VOICE_COMMUNICATION bufSize=$bufferSize"
                        )

                        // 2. Compute PCM -> float preprocessing diagnostics
                        var floatMin = Float.MAX_VALUE
                        var floatMax = -Float.MAX_VALUE
                        for (i in 0 until FRAME_SIZE) {
                            val f = floatBuffer[i]
                            if (f < floatMin) floatMin = f
                            if (f > floatMax) floatMax = f
                        }
                        val isNormalized = floatMin >= -1.01f && floatMax <= 1.01f

                        Log.d(
                            TAG,
                            "[VAD-PREPROCESS] pcmMin=$pcmMin pcmMax=$pcmMax floatMin=${"%.4f".format(floatMin)} floatMax=${"%.4f".format(floatMax)} samples=$FRAME_SIZE shape=[1,$FRAME_SIZE] mono=true normalized=$isNormalized"
                        )

                        // 3. Process frame with Silero VAD ONNX
                        processFrame(floatBuffer)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[VAD-RECORD] Exception in AudioRecord capture loop", e)
            scope.launch { stop() }
        }
    }

    private fun processFrame(frame: FloatArray) {
        val env = ortEnv ?: run {
            Log.w(TAG, "[VAD-INFER] OrtEnvironment is null. Skipping inference.")
            return
        }
        val session = ortSession ?: run {
            Log.w(TAG, "[VAD-INFER] OrtSession is null. Skipping inference.")
            return
        }

        var inputTensor: OnnxTensor? = null
        var srTensor: OnnxTensor? = null
        var hTensor: OnnxTensor? = null
        var cTensor: OnnxTensor? = null
        var result: OrtSession.Result? = null

        try {
            inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(frame), longArrayOf(1, FRAME_SIZE.toLong()))
            srTensor = OnnxTensor.createTensor(env, longArrayOf(SAMPLE_RATE.toLong()))
            hTensor = OnnxTensor.createTensor(env, hState)
            cTensor = OnnxTensor.createTensor(env, cState)

            val inputs = mapOf(
                "input" to inputTensor,
                "sr" to srTensor,
                "h" to hTensor,
                "c" to cTensor
            )

            result = session.run(inputs)

            @Suppress("UNCHECKED_CAST")
            val outputProb = (result.get(0).value as Array<FloatArray>)[0][0]

            @Suppress("UNCHECKED_CAST")
            hState = result.get(1).value as Array<Array<FloatArray>>
            @Suppress("UNCHECKED_CAST")
            cState = result.get(2).value as Array<Array<FloatArray>>

            Log.d(
                TAG,
                "[VAD-INFER] probability=${"%.4f".format(outputProb)} threshold=${"%.2f".format(THRESHOLD)} frameSamples=$FRAME_SIZE inputShape=[1,$FRAME_SIZE] recurrentStates=true state=preserved"
            )

            val now = System.currentTimeMillis()
            val cooldownElapsed = (now - lastSpeechDetectedTime > SPEECH_COOLDOWN_MS)

            if (outputProb >= THRESHOLD) {
                Log.d(
                    TAG,
                    "[VAD-DECISION] SPEECH_DETECTED probability=${"%.4f".format(outputProb)} threshold=${"%.2f".format(THRESHOLD)} reason=probability>=threshold (cooldownElapsed=$cooldownElapsed)"
                )
                if (cooldownElapsed) {
                    lastSpeechDetectedTime = now
                    Log.d(TAG, "[VAD-EVENT] Speech detected (prob=${"%.3f".format(outputProb)}) -> firing onSpeechDetected() callback")
                    onSpeechDetected()
                } else {
                    Log.d(TAG, "[VAD-DECISION] SPEECH_DETECTED callback suppressed by cooldown (${now - lastSpeechDetectedTime}ms <= ${SPEECH_COOLDOWN_MS}ms)")
                }
            } else {
                nonSpeechFrameCount++
                if (nonSpeechFrameCount % 30 == 0L) {
                    Log.d(
                        TAG,
                        "[VAD-DECISION] NO_SPEECH probability=${"%.4f".format(outputProb)} threshold=${"%.2f".format(THRESHOLD)} reason=probability<threshold (sampled every 30 frames)"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[VAD-INFER] Inference error", e)
        } finally {
            inputTensor?.close()
            srTensor?.close()
            hTensor?.close()
            cTensor?.close()
            result?.close()
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        if (!isRunning.getAndSet(false)) return@withContext

        Log.i(TAG, "[VAD-RECORD] stop() called. Awaiting captureJob cancellation and AudioRecord release...")

        try {
            captureJob?.cancelAndJoin()
        } catch (e: Exception) {
            Log.w(TAG, "[VAD-RECORD] Exception awaiting captureJob cancellation", e)
        }
        captureJob = null

        val previousRecordingState = audioRecord?.recordingState
        try {
            audioRecord?.stop()
            Log.i(TAG, "[VAD-RECORD] AudioRecord.stop() executed (was $previousRecordingState).")
        } catch (e: Exception) {
            Log.w(TAG, "[VAD-RECORD] Exception stopping AudioRecord", e)
        }

        try {
            audioRecord?.release()
            Log.i(TAG, "[VAD-RECORD] AudioRecord.release() executed.")
        } catch (e: Exception) {
            Log.w(TAG, "[VAD-RECORD] Exception releasing AudioRecord", e)
        }
        audioRecord = null

        resetRecurrentStates()
        Log.i(TAG, "[VAD-RECORD] AudioRecord fully stopped, released, and states reset.")
    }
}
