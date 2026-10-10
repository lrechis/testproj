package com.lrechis.kittentts

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), TtsController.Listener {

    private lateinit var prefs: SharedPreferences
    private lateinit var modelSpinner: Spinner
    private lateinit var modelActionButton: Button
    private lateinit var modelStatusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var voiceSpinner: Spinner
    private lateinit var speedLabel: TextView
    private lateinit var speedSeekBar: SeekBar
    private lateinit var inputText: EditText
    private lateinit var speakButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView

    private var deleteDialog: AlertDialog? = null

    /** Sequence number of the last controller message shown as a toast (errors only). */
    private var lastToastedSeq = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        TtsController.init(this)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        modelSpinner = findViewById(R.id.modelSpinner)
        modelActionButton = findViewById(R.id.modelActionButton)
        modelStatusText = findViewById(R.id.modelStatusText)
        progressBar = findViewById(R.id.progressBar)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        speedLabel = findViewById(R.id.speedLabel)
        speedSeekBar = findViewById(R.id.speedSeekBar)
        inputText = findViewById(R.id.inputText)
        speakButton = findViewById(R.id.speakButton)
        stopButton = findViewById(R.id.stopButton)
        statusText = findViewById(R.id.statusText)

        // A message that already exists when this activity is created (e.g. after rotation) is
        // shown in the status line but not toasted again.
        lastToastedSeq = savedInstanceState?.getLong(STATE_LAST_TOASTED_SEQ)
            ?: (TtsController.currentState.message?.seq ?: 0L)

        setUpModelSpinner()
        setUpVoiceSpinner()
        setUpSpeedSeekBar()

        speakButton.setOnClickListener { onSpeakClicked() }
        stopButton.setOnClickListener { TtsController.stop() }
        modelActionButton.setOnClickListener { onModelActionClicked() }

        if (savedInstanceState?.getBoolean(STATE_CONFIRMING_DELETE) == true) {
            val state = TtsController.currentState
            if (state.status == TtsController.Status.IDLE && state.installed) confirmDelete(state.model)
        }
    }

    override fun onStart() {
        super.onStart()
        TtsController.attach(this)
    }

    override fun onStop() {
        TtsController.detach(this)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_LAST_TOASTED_SEQ, lastToastedSeq)
        outState.putBoolean(STATE_CONFIRMING_DELETE, deleteDialog?.isShowing == true)
    }

    override fun onDestroy() {
        deleteDialog?.dismiss()
        deleteDialog = null
        super.onDestroy()
    }

    // -- TtsController.Listener ---------------------------------------------------------------

    override fun onStateChanged(state: TtsController.State) {
        val idle = state.status == TtsController.Status.IDLE
        modelSpinner.isEnabled = idle
        voiceSpinner.isEnabled = idle
        speakButton.isEnabled = idle
        stopButton.isEnabled = state.status == TtsController.Status.SPEAKING && !state.stopping

        // Keep the picker in sync with the controller (e.g. after the activity was recreated).
        val modelIndex = KittenModels.ALL.indexOfFirst { it.id == state.model.id }
        if (modelIndex >= 0 && modelSpinner.selectedItemPosition != modelIndex) {
            modelSpinner.setSelection(modelIndex)
        }

        when (state.status) {
            TtsController.Status.IDLE -> {
                modelActionButton.isEnabled = true
                modelActionButton.text = getString(
                    if (state.installed) R.string.action_delete else R.string.action_download,
                )
                modelStatusText.text = if (state.installed) {
                    getString(R.string.model_status_ready)
                } else {
                    getString(R.string.model_status_missing, state.model.approxDownloadMb)
                }
                progressBar.visibility = View.GONE
            }
            TtsController.Status.DOWNLOADING -> {
                modelActionButton.isEnabled = true
                modelActionButton.text = getString(R.string.action_cancel)
                renderProgress(state.progress)
            }
            TtsController.Status.LOADING -> {
                modelActionButton.isEnabled = false
                modelStatusText.text = getString(R.string.model_status_loading)
                showIndeterminateProgress()
            }
            TtsController.Status.DELETING -> {
                modelActionButton.isEnabled = false
                modelStatusText.text = getString(R.string.model_status_deleting)
                showIndeterminateProgress()
            }
            TtsController.Status.SPEAKING -> {
                modelActionButton.isEnabled = false
                modelStatusText.text = getString(R.string.model_status_speaking)
                progressBar.visibility = View.GONE
            }
        }

        // The download is lost if the system kills the app, so keep the screen on meanwhile.
        if (state.status == TtsController.Status.DOWNLOADING) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        renderStatusLine(state)
    }

    private fun renderStatusLine(state: TtsController.State) {
        statusText.text = when (state.status) {
            TtsController.Status.IDLE -> state.message?.text ?: getString(R.string.status_idle)
            TtsController.Status.DOWNLOADING -> getString(R.string.status_downloading_model)
            TtsController.Status.LOADING -> getString(R.string.status_loading_model)
            TtsController.Status.DELETING -> getString(R.string.status_deleting_model)
            TtsController.Status.SPEAKING ->
                getString(if (state.stopping) R.string.status_stopping else R.string.status_generating)
        }
        val message = state.message
        if (message != null && message.isError && message.seq > lastToastedSeq) {
            lastToastedSeq = message.seq
            Toast.makeText(this, message.text, Toast.LENGTH_LONG).show()
        }
    }

    // -- UI setup -----------------------------------------------------------------------------

    private fun setUpModelSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            KittenModels.ALL.map { it.displayName },
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modelSpinner.adapter = adapter

        // Restore the last choice, unless a task is already running for another model.
        val saved = KittenModels.byId(prefs.getString(PREF_MODEL, null))
        TtsController.selectModel(saved)
        val current = TtsController.currentState.model
        modelSpinner.setSelection(KittenModels.ALL.indexOfFirst { it.id == current.id }.coerceAtLeast(0))

        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = KittenModels.ALL[position]
                if (TtsController.selectModel(chosen)) {
                    prefs.edit().putString(PREF_MODEL, chosen.id).apply()
                } else {
                    // Busy: snap back to the model that is actually in use.
                    onStateChanged(TtsController.currentState)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setUpVoiceSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            KittenModels.VOICES.indices.map { KittenModels.voiceLabel(it) },
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        voiceSpinner.adapter = adapter
        voiceSpinner.setSelection(prefs.getInt(PREF_VOICE, 0).coerceIn(0, KittenModels.VOICES.size - 1))
        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt(PREF_VOICE, position).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setUpSpeedSeekBar() {
        speedSeekBar.progress = prefs.getInt(PREF_SPEED, DEFAULT_SPEED_PROGRESS)
        updateSpeedLabel(speedSeekBar.progress)
        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSpeedLabel(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.edit().putInt(PREF_SPEED, speedSeekBar.progress).apply()
            }
        })
    }

    // -- Actions ------------------------------------------------------------------------------

    private fun onSpeakClicked() {
        val text = inputText.text.toString().trim()
        if (text.isEmpty()) {
            showLocalError(getString(R.string.error_empty_text))
            return
        }
        hideKeyboard()
        if (!TtsController.speak(text, voiceSpinner.selectedItemPosition, currentSpeed())) {
            showLocalError(getString(R.string.error_busy))
        }
    }

    private fun onModelActionClicked() {
        val state = TtsController.currentState
        when (state.status) {
            TtsController.Status.DOWNLOADING -> TtsController.cancelDownload()
            TtsController.Status.IDLE -> if (state.installed) confirmDelete(state.model) else TtsController.download()
            else -> Unit
        }
    }

    private fun confirmDelete(model: KittenModel) {
        deleteDialog?.dismiss()
        deleteDialog = AlertDialog.Builder(this)
            .setTitle(R.string.action_delete)
            .setMessage(model.displayName)
            .setPositiveButton(R.string.action_delete) { _, _ -> TtsController.deleteModel() }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { deleteDialog = null }
            .show()
    }

    // -- Helpers ------------------------------------------------------------------------------

    private fun showLocalError(text: String) {
        statusText.text = text
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun renderProgress(progress: ModelDownloader.Progress?) {
        if (progress == null) {
            showIndeterminateProgress()
            modelStatusText.text = getString(R.string.model_status_downloading, "")
            return
        }
        val done = Formatter.formatShortFileSize(this, progress.done)
        when (progress.phase) {
            ModelDownloader.Phase.DOWNLOADING -> {
                if (progress.total > 0) {
                    val total = Formatter.formatShortFileSize(this, progress.total)
                    modelStatusText.text = getString(R.string.model_status_downloading_of, done, total)
                    progressBar.visibility = View.VISIBLE
                    progressBar.isIndeterminate = false
                    progressBar.progress = (progress.done * progressBar.max / progress.total).toInt()
                } else {
                    modelStatusText.text = getString(R.string.model_status_downloading, done)
                    showIndeterminateProgress()
                }
            }
            ModelDownloader.Phase.EXTRACTING -> {
                modelStatusText.text = getString(R.string.model_status_extracting, done)
                showIndeterminateProgress()
            }
        }
    }

    private fun showIndeterminateProgress() {
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
    }

    private fun currentSpeed(): Float = speedFromProgress(speedSeekBar.progress)

    private fun updateSpeedLabel(progress: Int) {
        speedLabel.text = getString(R.string.speed_label, speedFromProgress(progress))
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(inputText.windowToken, 0)
    }

    companion object {
        private const val PREFS_NAME = "kitten_tts"
        private const val PREF_MODEL = "model_id"
        private const val PREF_VOICE = "voice"
        private const val PREF_SPEED = "speed_progress"
        private const val STATE_LAST_TOASTED_SEQ = "last_toasted_seq"
        private const val STATE_CONFIRMING_DELETE = "confirming_delete"

        /** SeekBar progress 0..150 maps to speed 0.5x..2.0x; 50 is 1.0x. */
        private const val DEFAULT_SPEED_PROGRESS = 50

        internal fun speedFromProgress(progress: Int): Float = 0.5f + progress / 100f
    }
}
