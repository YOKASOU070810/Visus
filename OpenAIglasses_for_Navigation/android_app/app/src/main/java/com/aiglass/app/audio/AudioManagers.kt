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
                while (isActive && isRecording) {
                    val bytesRead = audioRecord?.read(buffer, 0, BYTES_PER_CHUNK) ?: -1
                    if (bytesRead > 0) {
                        val chunk = buffer.copyOf(bytesRead)
                        onAudioChunk(chunk)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Audio permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Audio capture error: ${e.message}")
        }
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
    }

    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamJob: Job? = null

    private val bufferSize = AudioTrack.getMinBufferSize(
        SAMPLE_RATE, CHANNEL, ENCODING
    ).coerceAtLeast(4096)

    /** Stream AI voice from server's /stream.wav endpoint */
    fun playStreamFromUrl(url: String, onStart: () -> Unit = {}, onDone: () -> Unit = {}) {
        stop()
        streamJob = scope.launch {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS) // infinite stream
                    .build()

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Stream request failed: ${response.code}")
                    onDone()
                    return@launch
                }

                initAudioTrack()
                audioTrack?.play()
                onStart()

                val body = response.body ?: return@launch
                val input = body.byteStream()
                val buf = ByteArray(4096)

                // Skip WAV header (44 bytes)
                var headerRead = 0
                while (headerRead < 44) {
                    val n = input.read(buf, 0, minOf(44 - headerRead, buf.size))
                    if (n < 0) break
                    headerRead += n
                }

                Log.i(TAG, "Stream playback started, WAV header skipped")

                // Play PCM data
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    audioTrack?.write(buf, 0, n)
                }

                Log.i(TAG, "Stream playback ended")
            } catch (e: IOException) {
                Log.e(TAG, "Stream error: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Playback error: ${e.message}")
            } finally {
                audioTrack?.stop()
                onDone()
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
        streamJob?.cancel()
        audioTrack?.apply {
            if (playState == AudioTrack.PLAYSTATE_PLAYING) stop()
        }
    }

    fun release() {
        stop()
        scope.cancel()
        audioTrack?.release()
        audioTrack = null
    }
}
