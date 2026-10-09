package com.mihan.englishaitutor.v2

import android.content.Context
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Java-friendly bridge to the suspend-based whisper.cpp Android API. */
object WhisperBridge {
    data class Segment(val startMs: Long, val endMs: Long, val text: String)

    interface Callback {
        fun onSuccess(segments: List<Segment>, processingTimeMs: Long)
        fun onError(message: String)
    }

    @JvmStatic
    fun transcribe(
        context: Context,
        modelPath: String,
        audioPath: String,
        callback: Callback,
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                // Keep ALL large Whisper model operations on the IO dispatcher.
                // In particular, releasing a native model on the main/UI thread
                // can block Android long enough to trigger an ANR.
                val result = withContext(Dispatchers.IO) {
                    val model = Whisper.loadModel(context.applicationContext, modelPath)
                    try {
                        val cpuCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                        Whisper.transcribe(
                            model,
                            audioPath,
                            WhisperConfig(
                                language = "en",
                                translate = false,
                                threads = cpuCount,
                                maxSegmentLength = 100,
                                printTimestamps = true,
                            ),
                        )
                    } finally {
                        try {
                            Whisper.releaseModel(model)
                        } catch (_: Throwable) {
                            // Preserve the transcript even if native cleanup fails.
                        }
                    }
                }
                callback.onSuccess(
                    result.segments.map { Segment(it.startMs, it.endMs, it.text.trim()) },
                    result.processingTimeMs,
                )
            } catch (t: Throwable) {
                callback.onError(t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
