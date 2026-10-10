package com.lrechis.kittentts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/**
 * Thin wrapper around sherpa-onnx's [OfflineTts] configured for a KittenTTS bundle.
 *
 * Creating an instance loads the ONNX model and espeak-ng data from [modelDir]; this takes a
 * moment and must not run on the main thread.
 */
class TtsEngine(modelDir: File, model: KittenModel, numThreads: Int) : AutoCloseable {

    private val tts: OfflineTts

    init {
        val kitten = OfflineTtsKittenModelConfig(
            model = File(modelDir, model.modelFile).absolutePath,
            voices = File(modelDir, KittenModels.VOICES_FILE).absolutePath,
            tokens = File(modelDir, KittenModels.TOKENS_FILE).absolutePath,
            dataDir = File(modelDir, KittenModels.ESPEAK_DATA_DIR).absolutePath,
        )
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kitten = kitten,
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
            ),
            // sherpa-onnx synthesises Kitten text one sentence at a time and reports each
            // sentence through the callback, so playback can start before the text is finished.
            maxNumSentences = 1,
        )
        tts = OfflineTts(assetManager = null, config = config)
    }

    val sampleRate: Int
        get() = tts.sampleRate()

    val numSpeakers: Int
        get() = tts.numSpeakers()

    /**
     * Generates speech for [text], chunk by chunk (see [TextChunker]). [onAudio] receives each
     * piece of mono float samples as soon as it is ready; return false from it to stop.
     *
     * @param speed 1.0 is normal; larger values speak faster.
     * @return true if the whole text was synthesised, false if [onAudio] asked to stop.
     */
    fun synthesize(
        text: String,
        speakerId: Int,
        speed: Float,
        onAudio: (FloatArray) -> Boolean,
    ): Boolean {
        val callback = AudioCallback(onAudio)
        for (chunk in TextChunker.chunk(text)) {
            tts.generateWithCallback(text = chunk, sid = speakerId, speed = speed, callback = callback)
            if (callback.stopped) return false
        }
        return true
    }

    override fun close() {
        tts.release()
    }

    /**
     * The callback handed to native code. sherpa-onnx's JNI layer does not call it through the
     * Function1 interface: it looks up a method named `invoke` with the exact signature
     * `([F)Ljava/lang/Integer;` on the object's own class and silently never calls it when that
     * lookup fails. A Kotlin 2.x lambda compiles to an invokedynamic/desugared class that only
     * has the erased `invoke(Object)`, so an explicit class with a typed `invoke` is required
     * (proguard-rules.pro keeps that method in release builds).
     */
    private class AudioCallback(private val onAudio: (FloatArray) -> Boolean) : (FloatArray) -> Int {
        @Volatile
        var stopped = false
            private set

        override fun invoke(samples: FloatArray): Int {
            if (stopped) return STOP
            return if (onAudio(samples)) {
                CONTINUE
            } else {
                stopped = true
                STOP
            }
        }
    }

    private companion object {
        // Return values expected by sherpa-onnx's generation callback.
        const val CONTINUE = 1
        const val STOP = 0
    }
}
