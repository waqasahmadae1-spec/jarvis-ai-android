package com.example.llama

import androidx.core.content.ContextCompat
import android.speech.tts.TextToSpeech
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private var wakeWordManager: WakeWordManager? = null
    private var wakeWordTts: TextToSpeech? = null
    private var wakeTtsReady = false

    private val wakePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            android.util.Log.i("JARVIS-DIAG", "Microphone permission result: $granted; wake-word auto-start disabled for diagnosis.")
        }


    // Android views
    private lateinit var ggufTv: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var userActionFab: FloatingActionButton
    private lateinit var micButton: Button
    private var speechRecognizer: SpeechRecognizer? = null

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startVoiceRecognition()
            } else {
                Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
            }
        }

    // Arm AI Chat inference engine
    private lateinit var engine: InferenceEngine
    private var generationJob: Job? = null

    // Conversation states
    private var isModelReady = false
    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        // View model boilerplate and state management is out of this basic sample's scope
        onBackPressedDispatcher.addCallback { Log.w(TAG, "Ignore back press for simplicity") }

        // Find views
        ggufTv = findViewById(R.id.gguf)
        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter
        userInputEt = findViewById(R.id.user_input)
        userActionFab = findViewById(R.id.fab)
        micButton = findViewById(R.id.mic_button)

        wakeWordTts = TextToSpeech(this) { status ->
            wakeTtsReady = status == TextToSpeech.SUCCESS
            if (wakeTtsReady) wakeWordTts?.language = java.util.Locale.US
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            wakePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            android.util.Log.i("JARVIS-DIAG", "Microphone permission already granted; wake-word auto-start disabled for diagnosis.")
        }
        micButton.setOnClickListener { startVoiceInput() }

        // Configure cloud AI; no local GGUF model is loaded at startup.
        configureCloudAi()

        // Send a message, or open cloud setup if no API key is configured.
        userActionFab.setOnClickListener {
            if (isModelReady) handleUserInput() else showApiKeyDialog()
        }
    }

    private fun configureCloudAi() {
        val key = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString("OPENROUTER_API_KEY", null)
        isModelReady = !key.isNullOrBlank()
        userInputEt.isEnabled = isModelReady
        userActionFab.isEnabled = true
        ggufTv.text = if (isModelReady) "JARVIS AI • Cloud ready" else "JARVIS AI • Cloud setup required"
        userInputEt.hint = if (isModelReady) "Message JARVIS..." else "Set up your cloud API key"
        if (!isModelReady) showApiKeyDialog()
    }

    private fun showApiKeyDialog() {
        val field = android.widget.EditText(this).apply {
            hint = "OpenRouter API key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString("OPENROUTER_API_KEY", ""))
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Set up JARVIS Cloud AI")
            .setMessage("Enter your OpenRouter API key. The key is stored on this device, not in the GitHub repository.")
            .setView(field)
            .setPositiveButton("Save") { _, _ ->
                val key = field.text.toString().trim()
                if (!key.startsWith("sk-or-") || key.length < 20) {
                    Toast.makeText(this, "Please enter a valid OpenRouter API key.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString("OPENROUTER_API_KEY", key).apply()
                isModelReady = true
                ggufTv.text = "JARVIS AI • Cloud ready"
                userInputEt.hint = "Message JARVIS..."
                userInputEt.isEnabled = true
                userActionFab.isEnabled = true
                Toast.makeText(this, "Cloud AI configured.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        Log.i(TAG, "Selected file uri:\n $uri")
        uri?.let { handleSelectedModel(it) }
    }

    /**
     * Handles the file Uri from [getContent] result
     */
    private fun handleSelectedModel(uri: Uri) {
        // Update UI states
        userActionFab.isEnabled = false
        userInputEt.hint = "Parsing GGUF..."
        ggufTv.text = "Parsing metadata from selected file \n$uri"

        lifecycleScope.launch(Dispatchers.IO) {
            // Parse GGUF metadata
            Log.i(TAG, "Parsing GGUF metadata...")
            contentResolver.openInputStream(uri)?.use {
                GgufMetadataReader.create().readStructuredMetadata(it)
            }?.let { metadata ->
                // Update UI to show GGUF metadata to user
                Log.i(TAG, "GGUF parsed: \n$metadata")
                withContext(Dispatchers.Main) {
                    ggufTv.text = "JARVIS AI • Importing ${metadata.filename()}$FILE_EXTENSION_GGUF"
                }

                // Ensure the model file is available
                val modelName = metadata.filename() + FILE_EXTENSION_GGUF
                contentResolver.openInputStream(uri)?.use { input ->
                    ensureModelFile(modelName, input)
                }?.let { modelFile ->
                    loadModel(modelName, modelFile)

                    withContext(Dispatchers.Main) {
                        isModelReady = true
                        userInputEt.hint = "Type and send a message!"
                        userInputEt.isEnabled = true
                        userActionFab.setImageResource(R.drawable.outline_send_24)
                        userActionFab.isEnabled = true
                    }
                }
            }
        }
    }

    /**
     * Prepare the model file within app's private storage
     */
    private suspend fun ensureModelFile(modelName: String, input: InputStream) =
        withContext(Dispatchers.IO) {
            File(ensureModelsDirectory(), modelName).also { file ->
                // Copy the file into local storage if not yet done
                if (!file.exists()) {
                    Log.i(TAG, "Start copying file to $modelName")
                    withContext(Dispatchers.Main) {
                        userInputEt.hint = "Copying file..."
                    }

                    FileOutputStream(file).use { input.copyTo(it) }
                    Log.i(TAG, "Finished copying file to $modelName")
                } else {
                    Log.i(TAG, "File already exists $modelName")
                }
            }
        }

    /**
     * Load the model file from the app private storage
     */
    private suspend fun loadModel(modelName: String, modelFile: File) =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Loading model $modelName")
            withContext(Dispatchers.Main) {
                userInputEt.hint = "Loading model..."
            }
            engine.loadModel(modelFile.absolutePath)
            engine.setSystemPrompt(
                "You are JARVIS, a helpful AI assistant. Always answer in clear English. " +
                "Be accurate, direct, and concise. For simple questions, use 1-4 short sentences. " +
                "Do not repeat the question or add unnecessary introductions. " +
                "Clearly distinguish verified facts, assumptions, and unknown information. " +
                "Never invent facts, sources, citations, URLs, or evidence. " +
                "Never claim to have searched or verified information unless you actually did. " +
                "If you cannot verify a claim, say so clearly. " +
                "When reliable evidence is available, explain and correct your mistakes. " +
                "If evidence is unavailable, do not invent a correction. " +
                "Never claim perfect accuracy or guarantee that errors will never happen. " +
                "If uncertain, explain what is missing and suggest a safe way to verify it."
            )
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_MODEL_NAME, modelName)
                .apply()
        }

    /**
     * Validate and send the user message into [InferenceEngine]
     */
    private fun startVoiceInput() {
        if (!isModelReady) {
            Toast.makeText(this, "Please load a model first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition is unavailable on this device.", Toast.LENGTH_LONG).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startVoiceRecognition()
    }

    private fun startVoiceRecognition() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        micButton.text = "..."
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        micButton.text = "🎙"
                    }
                    override fun onError(error: Int) {
                        micButton.text = "🎙"
                        Toast.makeText(this@MainActivity, "Voice input failed. Please try again.", Toast.LENGTH_SHORT).show()
                    }
                    override fun onResults(results: Bundle?) {
                        micButton.text = "🎙"
                        val spokenText = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!spokenText.isNullOrBlank()) {
                            userInputEt.setText(spokenText)
                            if (isModelReady) handleUserInput()
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            micButton.text = "..."
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            micButton.text = "🎙"
            Log.e(TAG, "Voice recognition could not start", e)
            Toast.makeText(this, "Could not start voice input.", Toast.LENGTH_LONG).show()
        }
    }

    private fun handlePhoneCommand(command: String): Boolean {
        val text = command.trim().lowercase(java.util.Locale.ROOT)
        val target = when {
            text.matches(Regex("(please )?(open|launch|start)( the)? youtube(app)?[.! ]*")) ->
                "youtube"
            text.matches(Regex("(please )?(open|launch|start)( the)? chrome(app)?[.! ]*")) ->
                "chrome"
            text.matches(Regex("(please )?(open|search|go to)( google| google.com)?[.! ]*")) ||
                text == "google" || text == "open google.com" ->
                "google"
            else -> return false
        }

        try {
            if (target == "chrome" || target == "youtube") {
                val pkg = if (target == "chrome") {
                    "com.android.chrome"
                } else {
                    "com.google.android.youtube"
                }
                val launch = packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    startActivity(launch)
                    return true
                }
            }

            val url = when (target) {
                "youtube" -> "https://www.youtube.com"
                "google" -> "https://www.google.com"
                else -> "https://www.google.com"
            }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Log.e(TAG, "Could not open $target", e)
            Toast.makeText(this, "Could not open $target.", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun handleUserInput() {
        val userMsg = userInputEt.text.toString().trim()
        if (userMsg.isEmpty()) {
            Toast.makeText(this, "Input message is empty!", Toast.LENGTH_SHORT).show()
            return
        }
        if (handlePhoneCommand(userMsg)) {
            userInputEt.text = null
            return
        }

        // Keep JARVIS application identity separate from its underlying AI model.
        val normalizedQuestion = userMsg.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val identityPhrases = listOf(
            "who created you",
            "who made you",
            "who built you",
            "who developed you",
            "who is your creator",
            "who is your developer",
            "who created jarvis",
            "who made jarvis",
            "who developed jarvis",
            "are you created by nvidia",
            "did nvidia create you",
            "is nvidia your creator"
        )

        if (identityPhrases.any { normalizedQuestion.contains(it) }) {
            val identityAnswer = "I am JARVIS AI, created by Waqas Ahmad. " +
                "I use NVIDIA Nemotron 3 Ultra as my underlying AI model. " +
                "NVIDIA developed the model, not the JARVIS application."

            userInputEt.text = null
            messages.add(Message(UUID.randomUUID().toString(), userMsg, true))
            messages.add(Message(UUID.randomUUID().toString(), identityAnswer, false))
            messageAdapter.notifyDataSetChanged()
            return
        }

        val apiKey = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString("OPENROUTER_API_KEY", null)
        if (apiKey.isNullOrBlank()) {
            isModelReady = false
            showApiKeyDialog()
            return
        }

        userInputEt.text = null
        userInputEt.isEnabled = false
        userActionFab.isEnabled = false

        messages.add(Message(UUID.randomUUID().toString(), userMsg, true))
        val assistantId = UUID.randomUUID().toString()
        messages.add(Message(assistantId, "Thinking...", false))
        messageAdapter.notifyDataSetChanged()

        val history = messages.dropLast(1).takeLast(12).mapNotNull { message ->
            val content = message.content.trim()
            if (content.isBlank()) null
            else Pair(if (message.isUser) "user" else "assistant", content)
        }

        generationJob = lifecycleScope.launch(Dispatchers.IO) {
            val answer = try {
                CloudAiClient.reply(apiKey, history)
            } catch (e: Exception) {
                Log.e(TAG, "Cloud AI request failed", e)
                "Cloud AI error: ${e.message ?: "Connection failed"}. Check your internet and API key, then try again."
            }

            withContext(Dispatchers.Main) {
                val index = messages.indexOfLast { it.id == assistantId }
                if (index >= 0) {
                    messages[index] = messages[index].copy(content = answer)
                    messageAdapter.notifyItemChanged(index)
                }
                userInputEt.isEnabled = true
                userActionFab.isEnabled = true
                userInputEt.requestFocus()
            }
        }
    }

    /**
     * Run a benchmark with the model file
     */
    @Deprecated("This benchmark doesn't accurately indicate GUI performance expected by app developers")
    private suspend fun runBenchmark(modelName: String, modelFile: File) =
        withContext(Dispatchers.Default) {
            Log.i(TAG, "Starts benchmarking $modelName")
            withContext(Dispatchers.Main) {
                userInputEt.hint = "Running benchmark..."
            }
            engine.bench(
                pp=BENCH_PROMPT_PROCESSING_TOKENS,
                tg=BENCH_TOKEN_GENERATION_TOKENS,
                pl=BENCH_SEQUENCE,
                nr=BENCH_REPETITION
            ).let { result ->
                messages.add(Message(UUID.randomUUID().toString(), result, false))
                withContext(Dispatchers.Main) {
                    messageAdapter.notifyItemChanged(messages.size - 1)
                }
            }
        }

    /**
     * Create the `models` directory if not exist.
     */
    private fun ensureModelsDirectory() =
        File(filesDir, DIRECTORY_MODELS).also {
            if (it.exists() && !it.isDirectory) { it.delete() }
            if (!it.exists()) { it.mkdir() }
        }

    override fun onStop() {
        generationJob?.cancel()
        super.onStop()
    }


    private fun startWakeWordListener() {
        if (wakeWordManager != null) return
        wakeWordManager = WakeWordManager(
            context = this,
            onWakeWord = {
                runOnUiThread {
                    val speakAndListen = {
                        micButton.performClick()
                    }
                    if (wakeTtsReady && wakeWordTts != null) {
                        wakeWordTts?.speak(
                            "Yes, sir. What's the new order?",
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "jarvis_wake_ack"
                        )
                        android.os.Handler(mainLooper).postDelayed({ speakAndListen(); Unit }, 1600)
                    } else {
                        speakAndListen()
                    }
                }
            },
            onError = { message ->
                android.util.Log.e("JARVIS-WakeWord", message)
            }
        )
        wakeWordManager?.start()
    }

    override fun onDestroy() {
        wakeWordManager?.stop()
        wakeWordManager = null
        wakeWordTts?.stop()
        wakeWordTts?.shutdown()
        wakeWordTts = null

        speechRecognizer?.destroy()
        speechRecognizer = null
        if (this::engine.isInitialized) engine.destroy()
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        private const val DIRECTORY_MODELS = "models"
        private const val PREFS_NAME = "jarvis_model_preferences"
        private const val PREF_MODEL_NAME = "saved_model_name"
        private const val FILE_EXTENSION_GGUF = ".gguf"

        private const val BENCH_PROMPT_PROCESSING_TOKENS = 512
        private const val BENCH_TOKEN_GENERATION_TOKENS = 128
        private const val BENCH_SEQUENCE = 1
        private const val BENCH_REPETITION = 3
    }
}

fun GgufMetadata.filename() = when {
    basic.name != null -> {
        basic.name?.let { name ->
            basic.sizeLabel?.let { size ->
                "$name-$size"
            } ?: name
        }
    }
    architecture?.architecture != null -> {
        architecture?.architecture?.let { arch ->
            basic.uuid?.let { uuid ->
                "$arch-$uuid"
            } ?: "$arch-${System.currentTimeMillis()}"
        }
    }
    else -> {
        "model-${System.currentTimeMillis().toHexString()}"
    }
}
