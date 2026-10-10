package com.lrechis.kittentts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.max

/**
 * Streams mono float PCM to the speaker through an [AudioTrack].
 *
 * Audio chunks are queued with [enqueue] (never blocks, so synthesis can run ahead) and written
 * by a dedicated playback thread. The track itself uses a small buffer: in streaming mode an
 * AudioTrack does not start playing until the written data reaches its start threshold, which
 * defaults to the whole buffer, so a large buffer would delay or (for short texts) swallow the
 * audio entirely.
 *
 * One instance plays one utterance: [enqueue] chunks, then [awaitPlaybackEnd] and [release].
 * [stopNow] may be called from any thread to abort.
 */
class PcmPlayer(private val sampleRate: Int) {

    private val track: AudioTrack
    private val queue = LinkedBlockingQueue<FloatArray>()
    private val playbackThread: Thread

    @Volatile
    private var stopped = false

    /** Frames handed to the track; only touched by the playback thread. */
    private var framesWritten: Long = 0

    init {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bufferBytes = max(minBytes, sampleRate * BYTES_PER_SAMPLE * BUFFER_MILLIS / 1000)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        track = AudioTrack.Builder()
            .setAudioAttributes(ATTRIBUTES)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("Could not open audio output at $sampleRate Hz")
        }
        playbackThread = Thread(::pump, "kitten-tts-playback")
        playbackThread.start()
    }

    /**
     * Queues [samples] for playback and returns immediately. The array must not be modified
     * afterwards.
     * @return false if playback has been stopped.
     */
    fun enqueue(samples: FloatArray): Boolean {
        if (stopped) return false
        queue.put(samples)
        return true
    }

    /** Signals that no more audio will come and blocks until it has all been played or stopped. */
    fun awaitPlaybackEnd() {
        queue.put(END_OF_STREAM)
        playbackThread.join()
    }

    /** Stops playback immediately and discards buffered audio. Safe to call from any thread. */
    fun stopNow() {
        stopped = true
        queue.clear()
        queue.offer(END_OF_STREAM)
        runCatching {
            track.pause()
            track.flush()
        }
    }

    fun release() {
        stopped = true
        queue.clear()
        queue.offer(END_OF_STREAM)
        runCatching { playbackThread.join(JOIN_TIMEOUT_MS) }
        runCatching { track.release() }
    }

    private fun pump() {
        try {
            track.play()
            while (true) {
                val chunk = queue.take()
                if (chunk === END_OF_STREAM || stopped) break
                writeBlocking(chunk)
            }
            if (!stopped) drain()
        } catch (_: InterruptedException) {
            // release() or an abandoned player: just exit.
        }
    }

    private fun writeBlocking(chunk: FloatArray) {
        var offset = 0
        while (offset < chunk.size && !stopped) {
            val written = track.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                // Track error: give up on this utterance.
                stopped = true
                return
            }
            if (written == 0) {
                Thread.sleep(POLL_INTERVAL_MS)
                continue
            }
            offset += written
            framesWritten += written
        }
    }

    /** Lets the queued audio play out, then returns once it should have been heard. */
    private fun drain() {
        val played = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        // stop() plays out whatever has been written, even when the start threshold was never
        // reached (very short texts); it also resets the head position, so time the remainder.
        runCatching { track.stop() }
        val remainingFrames = (framesWritten - played).coerceAtLeast(0)
        val remainingMillis = remainingFrames * 1000L / sampleRate + DRAIN_MARGIN_MS
        val deadline = System.nanoTime() + remainingMillis * 1_000_000L
        while (!stopped && System.nanoTime() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    companion object {
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        private val END_OF_STREAM = FloatArray(0)
        private const val BYTES_PER_SAMPLE = 4
        private const val BUFFER_MILLIS = 400
        private const val POLL_INTERVAL_MS = 20L
        private const val DRAIN_MARGIN_MS = 150L
        private const val JOIN_TIMEOUT_MS = 2_000L
    }
}
