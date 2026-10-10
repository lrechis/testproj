package com.lrechis.kittentts

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide coordinator for downloading models and speaking.
 *
 * All heavy work runs on a single background thread, so at most one task (download, delete or
 * speak) is active at a time. The current [State], including the outcome of the last task,
 * survives activity recreation: the activity re-attaches as [Listener] and renders it.
 */
object TtsController {

    enum class Status { IDLE, DOWNLOADING, LOADING, DELETING, SPEAKING }

    /** Outcome of the last finished task. [seq] lets the UI tell a new message from a replay. */
    data class Message(val text: String, val isError: Boolean, val seq: Long)

    data class State(
        val status: Status,
        val model: KittenModel,
        val installed: Boolean,
        val progress: ModelDownloader.Progress? = null,
        /** True once Stop was tapped and the current sentence is being wound down. */
        val stopping: Boolean = false,
        val message: Message? = null,
    ) {
        val busy: Boolean
            get() = status != Status.IDLE
    }

    interface Listener {
        /** Always called on the main thread, and once immediately on [attach]. */
        fun onStateChanged(state: State)
    }

    private const val TAG = "TtsController"
    private const val PROGRESS_INTERVAL_MS = 100L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "kitten-tts-worker") }
    private val messageSeq = AtomicLong()

    private lateinit var appContext: Context
    private lateinit var store: ModelStore
    private lateinit var downloader: ModelDownloader
    private var initialized = false

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var state: State = State(Status.IDLE, KittenModels.DEFAULT, installed = false)

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var stopRequested = false

    @Volatile
    private var player: PcmPlayer? = null

    // Touched only from the worker thread.
    private var engine: TtsEngine? = null
    private var engineModelId: String? = null
    private var lastProgressPublish = 0L

    /** Must be called once (e.g. from the activity's onCreate) before anything else. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        store = ModelStore(appContext)
        downloader = ModelDownloader(store)
        state = State(Status.IDLE, KittenModels.DEFAULT, store.isInstalled(KittenModels.DEFAULT))
        initialized = true
        // First job on the worker: remove leftovers of an install the process did not survive.
        executor.execute { runCatching { store.deleteLeftovers() } }
    }

    val currentState: State
        get() = state

    fun attach(newListener: Listener) {
        listener = newListener
        newListener.onStateChanged(state)
    }

    fun detach(oldListener: Listener) {
        if (listener === oldListener) listener = null
    }

    /** Switches the selected model. Returns false (and does nothing) while a task is running. */
    @Synchronized
    fun selectModel(model: KittenModel): Boolean {
        val current = state
        if (current.busy) return false
        if (current.model.id != model.id) {
            publish(State(Status.IDLE, model, store.isInstalled(model)))
        }
        return true
    }

    /** Downloads the selected model if needed. Returns false if another task is running. */
    fun download(): Boolean = startTask({ Status.DOWNLOADING }) { model ->
        ensureInstalled(model)
        appContext.getString(R.string.status_downloaded)
    }

    /** Deletes the selected model from disk. Returns false if another task is running. */
    fun deleteModel(): Boolean = startTask({ Status.DELETING }) { model ->
        if (engineModelId == model.id) unloadEngine()
        store.delete(model)
        appContext.getString(R.string.status_deleted)
    }

    /**
     * Speaks [text] with the selected model, downloading and loading it first when necessary.
     * Returns false if another task is running.
     */
    fun speak(text: String, speakerId: Int, speed: Float): Boolean =
        startTask({ installed -> if (installed) Status.LOADING else Status.DOWNLOADING }) { model ->
            ensureInstalled(model)
            val tts = ensureEngine(model)
            publish(State(Status.SPEAKING, model, installed = true))

            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(PcmPlayer.ATTRIBUTES)
                .setOnAudioFocusChangeListener({ change -> if (change < 0) stop() }, mainHandler)
                .build()
            audioManager.requestAudioFocus(focusRequest)

            val output = PcmPlayer(tts.sampleRate)
            player = output
            try {
                val sid = speakerId.coerceIn(0, maxOf(0, tts.numSpeakers - 1))
                tts.synthesize(text, sid, speed) { samples -> !stopRequested && output.enqueue(samples) }
                if (!stopRequested) output.awaitPlaybackEnd()
            } finally {
                player = null
                output.release()
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
            appContext.getString(if (stopRequested) R.string.status_stopped else R.string.status_done)
        }

    /** Interrupts speech as soon as possible. */
    fun stop() {
        stopRequested = true
        player?.stopNow()
        val current = state
        if (current.status == Status.SPEAKING && !current.stopping) {
            publish(current.copy(stopping = true))
        }
    }

    /** Aborts a running download; the partial files are removed. */
    fun cancelDownload() {
        cancelRequested = true
        downloader.abortConnection()
    }

    // ------------------------------------------------------------------------------------------

    /**
     * Runs [block] on the worker thread unless a task is already running. [initialStatus] picks
     * the busy state shown until the block publishes something more specific; the block returns
     * the message shown when it finishes.
     */
    private fun startTask(
        initialStatus: (installed: Boolean) -> Status,
        block: (KittenModel) -> String,
    ): Boolean {
        val model: KittenModel
        synchronized(this) {
            check(initialized) { "TtsController.init() has not been called" }
            val current = state
            if (current.busy) return false
            cancelRequested = false
            stopRequested = false
            model = current.model
            publish(State(initialStatus(current.installed), model, current.installed))
        }
        executor.execute {
            val outcome = try {
                Message(block(model), isError = false, seq = messageSeq.incrementAndGet())
            } catch (e: CancelledException) {
                Message(appContext.getString(R.string.status_cancelled), false, messageSeq.incrementAndGet())
            } catch (e: Exception) {
                Log.e(TAG, "Task failed", e)
                errorMessage(e)
            } catch (e: LinkageError) {
                // Typically UnsatisfiedLinkError: no native library for this device's ABI.
                Log.e(TAG, "Native library problem", e)
                errorMessage(e)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory", e)
                errorMessage(e)
            }
            publish(State(Status.IDLE, model, store.isInstalled(model), message = outcome))
        }
        return true
    }

    private fun ensureInstalled(model: KittenModel) {
        if (store.isInstalled(model)) return
        val initial = ModelDownloader.Progress(ModelDownloader.Phase.DOWNLOADING, 0, -1)
        publish(State(Status.DOWNLOADING, model, installed = false, progress = initial))
        downloader.install(model, shouldCancel = { cancelRequested }) { progress ->
            publishProgress(State(Status.DOWNLOADING, model, installed = false, progress = progress))
        }
    }

    private fun ensureEngine(model: KittenModel): TtsEngine {
        val loaded = engine
        if (loaded != null && engineModelId == model.id) return loaded
        unloadEngine()
        publish(State(Status.LOADING, model, installed = true))
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        val created = TtsEngine(store.installDir(model), model, threads)
        engine = created
        engineModelId = model.id
        return created
    }

    private fun unloadEngine() {
        engine?.close()
        engine = null
        engineModelId = null
    }

    /** Rate-limits progress updates so the UI thread is not flooded. */
    private fun publishProgress(newState: State) {
        val now = SystemClock.elapsedRealtime()
        val previous = state.progress
        val phaseChanged = previous?.phase != newState.progress?.phase
        if (phaseChanged || now - lastProgressPublish >= PROGRESS_INTERVAL_MS) {
            lastProgressPublish = now
            publish(newState)
        }
    }

    private fun publish(newState: State) {
        state = newState
        mainHandler.post { listener?.onStateChanged(newState) }
    }

    private fun errorMessage(error: Throwable): Message {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return Message(appContext.getString(R.string.error_prefix, detail), true, messageSeq.incrementAndGet())
    }
}
