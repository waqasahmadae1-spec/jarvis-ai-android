package com.example.llama

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class WakeWordManager(
    private val context: Context,
    private val onWakeWord: () -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var worker: Thread? = null
    private var spotter: KeywordSpotter? = null
    private var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null

    fun start() {
        if (running.get()) return
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            onError("Microphone permission is required.")
            return
        }

        try {
            val dir = File(context.filesDir, "wakeword")
            dir.mkdirs()
            listOf(
                "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                "tokens.txt",
                "keywords.txt"
            ).forEach { name ->
                val out = File(dir, name)
                if (!out.exists()) {
                    context.assets.open("wakeword/$name").use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }

            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx").absolutePath,
                        decoder = File(dir, "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx").absolutePath,
                        joiner = File(dir, "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx").absolutePath
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = 2,
                    provider = "cpu"
                ),
                keywordsFile = File(dir, "keywords.txt").absolutePath
            )
            spotter = KeywordSpotter(context.assets, config)
            stream = spotter!!.createStream("hey jarvis")
            running.set(true)
            worker = Thread { listen() }.also { it.start() }
        } catch (e: Exception) {
            stop()
            onError("Wake-word startup failed: ${e.message ?: "unknown error"}")
        }
    }

    private fun listen() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val sampleRate = 16000
        val minBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) {
            running.set(false)
            onError("Audio recorder is not available.")
            return
        }

        var audio: AudioRecord? = null
        try {
            audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBytes * 2, 6400)
            )
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                onError("Microphone could not be initialized.")
                return
            }
            recorder = audio
            audio.startRecording()
            val buffer = ShortArray(3200)

            while (running.get()) {
                val count = audio.read(buffer, 0, buffer.size)
                if (count <= 0) continue
                val samples = FloatArray(count) { buffer[it] / 32768.0f }
                val currentSpotter = spotter ?: break
                val currentStream = stream ?: break
                currentStream.acceptWaveform(samples, sampleRate)

                while (currentSpotter.isReady(currentStream)) {
                    currentSpotter.decode(currentStream)
                }

                val result = currentSpotter.getResult(currentStream)
                if (result.keyword.isNotBlank()) {
                    running.set(false)
                    onWakeWord()
                    break
                }
            }
        } catch (e: Exception) {
            if (running.get()) onError("Wake-word listener failed: ${e.message ?: "unknown error"}")
        } finally {
            running.set(false)
            try { audio?.stop() } catch (_: Exception) {}
            audio?.release()
            recorder = null
        }
    }

    fun stop() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        worker?.interrupt()
        worker = null
        try { stream?.release() } catch (_: Exception) {}
        stream = null
        try { spotter?.release() } catch (_: Exception) {}
        spotter = null
    }
}
