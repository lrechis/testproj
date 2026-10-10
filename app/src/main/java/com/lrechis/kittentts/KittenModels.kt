package com.lrechis.kittentts

/**
 * A KittenTTS model as packaged for sherpa-onnx.
 *
 * Every bundle is a `<id>.tar.bz2` archive on the sherpa-onnx "tts-models" GitHub release. It
 * unpacks to a directory named [id] holding the ONNX model, `voices.bin`, `tokens.txt` and the
 * `espeak-ng-data` directory that sherpa-onnx uses to turn text into phonemes.
 */
data class KittenModel(
    /** Archive base name, directory name inside the archive and local install folder name. */
    val id: String,
    /** Short name shown in the model picker. */
    val displayName: String,
    /** File name of the ONNX model inside the bundle. */
    val modelFile: String,
    /** Rough size of the download in megabytes, for display only. */
    val approxDownloadMb: Int,
) {
    val archiveUrl: String
        get() = "${KittenModels.RELEASE_BASE_URL}/$id.tar.bz2"
}

object KittenModels {
    const val RELEASE_BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
    const val TOKENS_FILE = "tokens.txt"
    const val VOICES_FILE = "voices.bin"
    const val ESPEAK_DATA_DIR = "espeak-ng-data"

    /**
     * Speaker names in the order they are stored in `voices.bin`; the list index is the
     * sherpa-onnx speaker id. The order is the same for every KittenTTS bundle.
     */
    val VOICES: List<String> = listOf(
        "expr-voice-2-m",
        "expr-voice-2-f",
        "expr-voice-3-m",
        "expr-voice-3-f",
        "expr-voice-4-m",
        "expr-voice-4-f",
        "expr-voice-5-m",
        "expr-voice-5-f",
    )

    /** Human readable label for a speaker id, e.g. "expr-voice-2-f (female)". */
    fun voiceLabel(speakerId: Int): String {
        val name = VOICES[speakerId]
        val gender = if (name.endsWith("-f")) "female" else "male"
        return "$name ($gender)"
    }

    val ALL: List<KittenModel> = listOf(
        KittenModel("kitten-nano-en-v0_8-int8", "Nano 0.8 int8 (15M, smallest)", "model.int8.onnx", 30),
        KittenModel("kitten-nano-en-v0_8-fp32", "Nano 0.8 fp32 (15M)", "model.fp32.onnx", 60),
        KittenModel("kitten-micro-en-v0_8", "Micro 0.8 (40M)", "model.onnx", 50),
        KittenModel("kitten-mini-en-v0_8", "Mini 0.8 (80M, best quality)", "model.onnx", 90),
        KittenModel("kitten-nano-en-v0_1-fp16", "Nano 0.1 fp16 (original release)", "model.fp16.onnx", 40),
        KittenModel("kitten-nano-en-v0_2-fp16", "Nano 0.2 fp16", "model.fp16.onnx", 40),
    )

    val DEFAULT: KittenModel = ALL[0]

    fun byId(id: String?): KittenModel = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
