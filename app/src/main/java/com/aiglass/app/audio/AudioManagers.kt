package com.aiglass.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit

class AudioCaptureManager(
    private val onAudioChunk: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_MS = 20
        const val BYTES_PER_CHUNK = SAMPLE_RATE * CHUNK_MS / 1000 * 2 // 640 bytes
        private const val SILENCE_PEAK_THRESHOLD = 350
        private const val BYPASS_NOISE_GATE = true
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, CHANNEL, ENCODING
    ).coerceAtLeast(BYTES_PER_CHUNK * 4)

    fun startRecording() {
        if (isRecording) return
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL, ENCODING, bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                return
            }
            audioRecord?.startRecording()
            isRecording = true
            Log.i(TAG, "Audio recording started, buffer=$bufferSize, chunk=$BYTES_PER_CHUNK")

            scope.launch {
                val buffer = ByteArray(BYTES_PER_CHUNK)
                var chunkCount = 0L
                while (isActive && isRecording) {
                    val bytesRead = audioRecord?.read(buffer, 0, BYTES_PER_CHUNK) ?: -1
                    if (bytesRead > 0) {
                        chunkCount += 1
                        if (chunkCount <= 5 || chunkCount % 100L == 0L) {
                            Log.d(TAG, "Audio chunk #$chunkCount read: $bytesRead bytes")
                        }
                        val chunk = buffer.copyOf(bytesRead)
                        val outgoingChunk = if (BYPASS_NOISE_GATE) chunk else applyLightNoiseGate(chunk)
                        onAudioChunk(outgoingChunk)
                    } else if (bytesRead < 0) {
                        Log.w(TAG, "AudioRecord read returned error: $bytesRead")
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Audio permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Audio capture error: ${e.message}")
        }
    }

    private fun applyLightNoiseGate(chunk: ByteArray): ByteArray {
        var peak = 0
        var i = 0
        while (i + 1 < chunk.size) {
            val sample = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xff)).toShort().toInt()
            val abs = kotlin.math.abs(sample)
            if (abs > peak) peak = abs
            i += 2
        }
        return if (peak < SILENCE_PEAK_THRESHOLD) ByteArray(chunk.size) else chunk
    }

    fun stopRecording() {
        isRecording = false
        scope.cancel()
        audioRecord?.apply { stop(); release() }
        audioRecord = null
    }
}

class AudioPlaybackManager {
    companion object {
        private const val TAG = "AudioPlayback"
        // Server streams at 8000Hz, not 16000Hz
        const val SAMPLE_RATE = 8000
        private const val CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val PREBUFFER_BYTES = SAMPLE_RATE * 2 / 4 // 250ms of PCM16 mono
        private const val REBUFFER_GAP_MS = 350L
    }

    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamJob: Job? = null
    @Volatile private var keepStreamAlive = false
    @Volatile private var currentUrl: String? = null

    private val bufferSize = AudioTrack.getMinBufferSize(
        SAMPLE_RATE, CHANNEL, ENCODING
    ).coerceAtLeast(8192)

    /** Stream AI voice from server's /stream.wav endpoint */
    fun playStreamFromUrl(
        url: String,
        onStart: () -> Unit = {},
        onDone: () -> Unit = {},
        shouldReconnect: () -> Boolean = { false },
        reconnectDelayMs: Long = 1000L
    ) {
        if (streamJob?.isActive == true && currentUrl == url) {
            Log.d(TAG, "Audio stream already running for $url")
            return
        }
        stop()
        currentUrl = url
        keepStreamAlive = true
        streamJob = scope.launch {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // infinite stream
                .build()
            try {
                while (isActive && keepStreamAlive) {
                    try {
                        val request = Request.Builder().url(url).build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                Log.e(TAG, "Stream request failed: ${response.code}")
                                return@use
                            }

                            val body = response.body ?: return@use
                            val input = body.byteStream()
                            val buf = ByteArray(4096)

                            // Skip WAV header (44 bytes)
                            var headerRead = 0
                            while (headerRead < 44 && isActive && keepStreamAlive) {
                                val n = input.read(buf, 0, minOf(44 - headerRead, buf.size))
                                if (n < 0) break
                                headerRead += n
                            }

                            Log.i(TAG, "Stream playback started, WAV header skipped")

                            val prebuffer = java.io.ByteArrayOutputStream(PREBUFFER_BYTES)
                            while (
                                prebuffer.size() < PREBUFFER_BYTES &&
                                isActive &&
                                keepStreamAlive
                            ) {
                                val need = minOf(PREBUFFER_BYTES - prebuffer.size(), buf.size)
                                val n = input.read(buf, 0, need)
                                if (n < 0) break
                                if (n > 0) prebuffer.write(buf, 0, n)
                            }

                            initAudioTrack()
                            audioTrack?.play()
                            onStart()

                            val initialAudio = prebuffer.toByteArray()
                            if (initialAudio.isNotEmpty()) {
                                audioTrack?.write(initialAudio, 0, initialAudio.size)
                            }

                            var lastReadAt = System.currentTimeMillis()
                            while (isActive && keepStreamAlive) {
                                val n = input.read(buf)
                                if (n < 0) break
                                if (n > 0) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastReadAt > REBUFFER_GAP_MS) {
                                        try {
                                            audioTrack?.pause()
                                            audioTrack?.flush()
                                        } catch (_: Exception) {
                                        }
                                        val rebuffer = java.io.ByteArrayOutputStream(PREBUFFER_BYTES)
                                        rebuffer.write(buf, 0, n)
                                        while (
                                            rebuffer.size() < PREBUFFER_BYTES &&
                                            isActive &&
                                            keepStreamAlive
                                        ) {
                                            val need = minOf(PREBUFFER_BYTES - rebuffer.size(), buf.size)
                                            val rn = input.read(buf, 0, need)
                                            if (rn < 0) break
                                            if (rn > 0) rebuffer.write(buf, 0, rn)
                                        }
                                        audioTrack?.play()
                                        val reb = rebuffer.toByteArray()
                                        var rebWritten = 0
                                        while (rebWritten < reb.size && isActive && keepStreamAlive) {
                                            val w = audioTrack?.write(reb, rebWritten, reb.size - rebWritten) ?: 0
                                            if (w <= 0) break
                                            rebWritten += w
                                        }
                                        lastReadAt = System.currentTimeMillis()
                                        continue
                                    }
                                    lastReadAt = now
                                    var written = 0
                                    while (written < n && isActive && keepStreamAlive) {
                                        val w = audioTrack?.write(buf, written, n - written) ?: 0
                                        if (w <= 0) break
                                        written += w
                                    }
                                }
                            }

                            Log.i(TAG, "Stream playback ended")
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Stream error: ${e.message}")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Playback error: ${e.message}")
                    } finally {
                        try {
                            audioTrack?.stop()
                        } catch (_: Exception) {
                        }
                        onDone()
                    }

                    if (!isActive || !keepStreamAlive || !shouldReconnect()) {
                        break
                    }
                    Log.i(TAG, "Reconnecting audio stream in ${reconnectDelayMs}ms")
                    delay(reconnectDelayMs)
                }
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    private fun initAudioTrack() {
        audioTrack?.release()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(ENCODING)
            .setChannelMask(CHANNEL)
            .build()
        audioTrack = AudioTrack(
            attributes, format, bufferSize,
            AudioTrack.MODE_STREAM,
            android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }

    fun stop() {
        keepStreamAlive = false
        streamJob?.cancel()
        streamJob = null
        audioTrack?.apply {
            try {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) stop()
            } catch (_: Exception) {
            }
            release()
        }
        audioTrack = null
        currentUrl = null
    }

    fun release() {
        stop()
        scope.cancel()
        audioTrack?.release()
        audioTrack = null
    }
}
