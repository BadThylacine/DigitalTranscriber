package com.example.offlinetranscriber

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.offlinetranscriber.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var modelManager: ModelManager
    private lateinit var transcriber: Transcriber
    private var job: Job? = null
    private var transcript: String = ""      // text currently shown (copy/share/save use this)
    private var originalText: String = ""
    private var translatedText: String? = null
    private var lastPcm: Pcm? = null         // kept so translating/subtitling doesn't re-decode the video
    private var lastLanguage: String = ""
    private var lastBaseName: String = "video" // source filename (no extension), for SRT/txt export names
    private var pendingIntent: Intent? = null // a share that arrived while no model was installed

    // Subtitle export cache, tied to lastPcm. All three .srt variants share the
    // same chunk boundaries so translated/original lines stay aligned in time.
    private var subtitleChunks: List<AudioChunk>? = null
    private var orgCueTexts: List<String>? = null   // transcribe pass, one entry per chunk
    private var engCueTexts: List<String>? = null   // translate pass, one entry per chunk
    private var pendingSrtContent: String? = null    // written by saveSrtFile's callback

    // Label -> Whisper language code ("" = auto-detect). Add more as needed.
    private val languages = linkedMapOf(
        "Auto-detect" to "", "English" to "en", "German" to "de", "Spanish" to "es",
        "French" to "fr", "Italian" to "it", "Portuguese" to "pt", "Turkish" to "tr",
        "Arabic" to "ar", "Russian" to "ru", "Chinese" to "zh", "Japanese" to "ja",
    )

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { start(it) }
    }

    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { it.write(transcript.toByteArray()) }
            toast("Saved")
        }
    }

    private val saveSrtFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        val content = pendingSrtContent
        pendingSrtContent = null
        if (uri != null && content != null) {
            contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            toast("Saved")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        modelManager = ModelManager(applicationContext)
        transcriber = Transcriber(modelManager)

        b.languageSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages.keys.toList())

        b.pickButton.setOnClickListener { pickVideo.launch("video/*") }
        b.manageModelButton.setOnClickListener { showModelDialog() }
        b.copyButton.setOnClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("transcript", transcript))
            toast("Copied")
        }
        b.shareButton.setOnClickListener {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, transcript)
                }, null))
        }
        b.saveButton.setOnClickListener { saveFile.launch("transcript.txt") }
        b.translateButton.setOnClickListener { onTranslateClicked() }
        b.exportSrtButton.setOnClickListener { showSubtitleDialog() }

        updateModelStatus()
        handleIntent(intent)
        if (modelManager.activeModel() == null) {
            toast("Download a model to get started")
            showModelDialog()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
            if (uri == null) return
            if (modelManager.activeModel() == null) {
                // Remember it; run it once a model finishes downloading.
                pendingIntent = intent
                toast("Download a model first, then this will run automatically")
                showModelDialog()
            } else {
                start(uri)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Model management
    // ---------------------------------------------------------------------

    private fun updateModelStatus() {
        val active = modelManager.activeModel()
        val info = active?.let { modelManager.infoFor(it.id) }
        b.modelText.text = if (info != null) "Model: ${info.label}" else "No model installed"
        val ready = active != null && job == null
        b.pickButton.isEnabled = ready
    }

    private fun showModelDialog() {
        val active = modelManager.activeModel()
        val catalog = ModelManager.CATALOG
        val labels = catalog.map { m ->
            if (m.id == active?.id) "\u2713 ${m.label} (installed)" else m.label
        }.toMutableList()
        if (active != null) labels.add("Remove current model (free space)")

        AlertDialog.Builder(this)
            .setTitle("Choose a model")
            .setItems(labels.toTypedArray()) { _, which ->
                if (active != null && which == catalog.size) {
                    confirmRemove()
                } else {
                    val chosen = catalog[which]
                    if (chosen.id == active?.id) {
                        toast("Already installed")
                    } else {
                        confirmDownload(chosen)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDownload(model: ModelInfo) {
        val replacing = modelManager.activeModel() != null
        val msg = "Downloads ~${model.approxDownloadMb} MB over the network." +
            if (replacing) " The currently installed model will be removed afterwards." else ""
        AlertDialog.Builder(this)
            .setTitle(model.label)
            .setMessage(msg)
            .setPositiveButton("Download") { _, _ -> downloadModel(model) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemove() {
        AlertDialog.Builder(this)
            .setTitle("Remove current model?")
            .setMessage("Frees up storage. You'll need to download a model again before transcribing.")
            .setPositiveButton("Remove") { _, _ ->
                modelManager.removeActiveModel()
                transcriber.invalidate()
                updateModelStatus()
                toast("Model removed")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadModel(model: ModelInfo) {
        job?.cancel()
        setBusy(true)
        b.manageModelButton.isEnabled = false
        b.statusText.text = "Downloading ${model.label}…"
        b.progressBar.isIndeterminate = false
        b.progressBar.progress = 0

        val thisJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    modelManager.download(model) { readBytes, totalBytes ->
                        runOnUiThread {
                            if (totalBytes > 0) {
                                b.progressBar.progress = (readBytes * 100 / totalBytes).toInt()
                                b.statusText.text =
                                    "Downloading ${model.label}… (${readBytes / 1_000_000} / ${totalBytes / 1_000_000} MB)"
                            } else {
                                b.statusText.text = "Downloading ${model.label}… (${readBytes / 1_000_000} MB)"
                            }
                        }
                    }
                }
                transcriber.invalidate()
                b.statusText.text = "Model ready"
                toast("${model.label} installed")
                updateModelStatus()
                val toRun = pendingIntent
                pendingIntent = null
                if (toRun != null) handleIntent(toRun)
            } catch (e: Exception) {
                b.statusText.text = "Download failed: ${e.message}"
            } catch (e: OutOfMemoryError) {
                b.statusText.text = "Download failed: out of memory"
            } finally {
                b.progressBar.visibility = View.INVISIBLE
                b.manageModelButton.isEnabled = true
                job = null
                updateModelStatus()
            }
        }
        job = thisJob
    }

    // ---------------------------------------------------------------------
    // Subtitle export
    // ---------------------------------------------------------------------

    private fun showSubtitleDialog() {
        if (lastPcm == null) { toast("Transcribe a video first"); return }
        val multilingual = modelManager.activeModel()?.let { modelManager.infoFor(it.id)?.multilingual } ?: false

        val options = listOf(
            Triple("org", "Original language", true),
            Triple("eng", "Translated (English)", multilingual),
            Triple("bi", "Bilingual (English + original)", multilingual),
        )
        val labels = options.map { (_, label, enabled) ->
            if (enabled) label else "$label — needs a multilingual model"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Export subtitles")
            .setItems(labels) { _, which ->
                val (kind, _, enabled) = options[which]
                if (!enabled) {
                    toast("This model is English-only, so it can't translate. Switch to a multilingual model (Manage model) to use this option.")
                } else {
                    exportSubtitles(kind)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportSubtitles(kind: String) {
        val pcm = lastPcm ?: return
        job?.cancel()
        setBusy(true)
        val chunks = subtitleChunks ?: AudioChunker.chunks(pcm.samples, pcm.sampleRate).also { subtitleChunks = it }

        val thisJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val running = coroutineContext[Job]!!
                    val cancelled = { !running.isActive }

                    if (kind != "eng" && orgCueTexts == null) {
                        ui("Transcribing for subtitles…", 0)
                        orgCueTexts = transcriber.transcribeChunks(pcm, lastLanguage, "transcribe", chunks,
                            onProgress = { done, total -> progressChunks(done, total) }, isCancelled = cancelled)
                    }
                    if (kind != "org" && engCueTexts == null) {
                        ui("Translating for subtitles…", 0)
                        engCueTexts = transcriber.transcribeChunks(pcm, lastLanguage, "translate", chunks,
                            onProgress = { done, total -> progressChunks(done, total) }, isCancelled = cancelled)
                    }
                }

                val (content, suffix) = when (kind) {
                    "org" -> SrtWriter.build(chunks, pcm.sampleRate, orgCueTexts!!) to "_org"
                    "eng" -> SrtWriter.build(chunks, pcm.sampleRate, engCueTexts!!) to "_eng"
                    else -> SrtWriter.build(chunks, pcm.sampleRate, primary = engCueTexts!!, secondary = orgCueTexts!!) to "_bi"
                }
                pendingSrtContent = content
                b.statusText.text = "Done"
                saveSrtFile.launch("$lastBaseName$suffix.srt")
            } catch (e: NoModelException) {
                b.statusText.text = "No model installed"
                showModelDialog()
            } catch (e: InterruptedException) {
                b.statusText.text = "Cancelled"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                b.statusText.text = "Error: ${e.message}"
            } finally {
                b.progressBar.visibility = View.INVISIBLE
                job = null
                updateModelStatus()
                b.translateButton.isEnabled = lastPcm != null
                b.exportSrtButton.isEnabled = lastPcm != null
            }
        }
        job = thisJob
    }

    private fun progressChunks(done: Int, total: Int) = runOnUiThread {
        b.progressBar.progress = 100 * done / total.coerceAtLeast(1)
        b.statusText.text = "Processing chunk ${minOf(done + 1, total)}/$total…"
    }

    // ---------------------------------------------------------------------
    // Transcription / translation
    // ---------------------------------------------------------------------

    private fun start(uri: Uri) {
        lastPcm = null
        translatedText = null
        subtitleChunks = null
        orgCueTexts = null
        engCueTexts = null
        lastBaseName = baseNameFor(uri)
        run(uri, "transcribe")
    }

    /** Source video's display name without its extension, sanitized for use as a filename. */
    private fun baseNameFor(uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            var cursor: Cursor? = null
            try {
                cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx)
                }
            } catch (_: Exception) {
                // fall through to "video"
            } finally {
                cursor?.close()
            }
        }
        if (name == null) name = uri.lastPathSegment
        val withoutExt = name?.substringBeforeLast('.', name)?.takeIf { it.isNotBlank() } ?: "video"
        return withoutExt.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
    }

    private fun onTranslateClicked() {
        val cached = translatedText
        when {
            cached != null && transcript == cached -> showText(originalText, translated = false)
            cached != null -> showText(cached, translated = true)
            else -> run(null, "translate")
        }
    }

    private fun showText(text: String, translated: Boolean) {
        transcript = text
        b.resultText.text = text.ifEmpty { "(no speech detected)" }
        b.translateButton.text = if (translated) "Show original" else "Translate to English"
    }

    /** uri != null: decode video first. uri == null: reuse the cached audio. */
    private fun run(uri: Uri?, task: String) {
        if (modelManager.activeModel() == null) {
            toast("No model installed")
            showModelDialog()
            return
        }
        job?.cancel()
        val language = if (uri != null) languages[b.languageSpinner.selectedItem as String] ?: "" else lastLanguage
        setBusy(true)
        if (uri != null) b.resultText.text = ""

        val thisJob = lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.Default) {
                    val running = coroutineContext[Job]!!
                    val cancelled = { !running.isActive }

                    val pcm = if (uri != null) {
                        ui("Extracting audio…", 0)
                        AudioDecoder.decode(applicationContext, uri,
                            onProgress = { p -> runOnUiThread { b.progressBar.progress = (p * 30).toInt() } },
                            isCancelled = cancelled).also { lastPcm = it }
                    } else lastPcm!!

                    val verb = if (task == "translate") "Translating" else "Transcribing"
                    ui("$verb…", 30)
                    transcriber.transcribe(pcm, language, task,
                        onProgress = { done, total ->
                            runOnUiThread {
                                b.progressBar.progress = 30 + 70 * done / total.coerceAtLeast(1)
                                b.statusText.text = "$verb… (${minOf(done + 1, total)}/$total)"
                            }
                        }, isCancelled = cancelled)
                }
                lastLanguage = language
                if (task == "translate") {
                    translatedText = text
                    showText(text, translated = true)
                } else {
                    originalText = text
                    showText(text, translated = false)
                }
                b.statusText.text = "Done"
                val has = text.isNotEmpty()
                listOf(b.copyButton, b.shareButton, b.saveButton).forEach { it.isEnabled = has }
            } catch (e: NoModelException) {
                b.statusText.text = "No model installed"
                showModelDialog()
            } catch (e: InterruptedException) {
                b.statusText.text = "Cancelled"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                b.statusText.text = "Error: ${e.message}"
            } finally {
                b.progressBar.visibility = View.INVISIBLE
                job = null
                updateModelStatus()
                b.translateButton.isEnabled = lastPcm != null
                b.exportSrtButton.isEnabled = lastPcm != null
            }
        }
        job = thisJob
    }

    private suspend fun ui(msg: String, pct: Int) = withContext(Dispatchers.Main) {
        b.statusText.text = msg
        b.progressBar.progress = pct
    }

    private fun setBusy(busy: Boolean) {
        b.pickButton.isEnabled = !busy
        b.progressBar.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        b.progressBar.progress = 0
        if (busy) listOf(b.copyButton, b.shareButton, b.saveButton, b.translateButton, b.exportSrtButton).forEach { it.isEnabled = false }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        transcriber.release()
    }
}
