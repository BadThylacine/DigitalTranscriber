package com.example.offlinetranscriber

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var transcriber: Transcriber
    private var job: Job? = null
    private var transcript: String = ""      // text currently shown (copy/share/save use this)
    private var originalText: String = ""
    private var translatedText: String? = null
    private var lastPcm: Pcm? = null         // kept so translating doesn't re-decode the video
    private var lastLanguage: String = ""

    // Label -> Whisper language code ("" = auto-detect). Add more as needed.
    private val languages = linkedMapOf(
        "Auto-detect" to "", "English" to "en", "German" to "de", "Spanish" to "es",
        "French" to "fr", "Italian" to "it", "Portuguese" to "pt", "Turkish" to "tr",
        "Arabic" to "ar", "Russian" to "ru", "Chinese" to "zh", "Japanese" to "ja",
    )

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { start(it) }
    }

    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { it.write(transcript.toByteArray()) }
            toast("Saved")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        transcriber = Transcriber(applicationContext)

        b.languageSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages.keys.toList())

        b.pickButton.setOnClickListener { pickVideo.launch(arrayOf("video/*", "audio/*")) }
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

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
            if (uri != null) start(uri)
        }
    }

    private fun start(uri: Uri) {
        lastPcm = null
        translatedText = null
        run(uri, "transcribe")
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
            } catch (e: InterruptedException) {
                b.statusText.text = "Cancelled"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                b.statusText.text = "Error: ${e.message}"
            } finally {
                b.progressBar.visibility = View.INVISIBLE
                b.pickButton.isEnabled = true
                b.translateButton.isEnabled = lastPcm != null
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
        if (busy) listOf(b.copyButton, b.shareButton, b.saveButton, b.translateButton).forEach { it.isEnabled = false }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        transcriber.release()
    }
}
