package com.example.llama

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
        micButton.setOnClickListener { startVoiceInput() }

        // Initialize engine first, then restore the last successfully loaded model.
        userActionFab.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                engine = AiChat.getInferenceEngine(applicationContext)

                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val savedName = prefs.getString(PREF_MODEL_NAME, null)
                val savedFile = savedName?.let { File(ensureModelsDirectory(), it) }

                if (savedFile != null && savedFile.isFile && savedFile.length() > 0L) {
                    withContext(Dispatchers.Main) {
                        ggufTv.text = "JARVIS AI • Loading saved model..."
                        userInputEt.hint = "Loading your saved model..."
                    }
                    engine.loadModel(savedFile.absolutePath)
                    withContext(Dispatchers.Main) {
                        isModelReady = true
                        ggufTv.text = "Model ready • ${savedFile.name}"
                        userInputEt.hint = "Message JARVIS..."
                        userInputEt.isEnabled = true
                        userActionFab.setImageResource(R.drawable.outline_send_24)
                        userActionFab.isEnabled = true
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        ggufTv.text = "JARVIS AI • Select a GGUF model"
                        userInputEt.hint = "Tap the button to import a GGUF model"
                        userActionFab.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Engine initialization or model restore failed", e)
                withContext(Dispatchers.Main) {
                    isModelReady = false
                    ggufTv.text = "JARVIS AI • Model not loaded"
                    userInputEt.hint = "Tap to select your GGUF model"
                    userInputEt.isEnabled = false
                    userActionFab.isEnabled = true
                    Toast.makeText(
                        this@MainActivity,
                        "Could not load model. Please select it again.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // Upon CTA button tapped
        userActionFab.setOnClickListener {
            if (isModelReady) {
                // If model is ready, validate input and send to engine
                handleUserInput()
            } else {
                // Otherwise, prompt user to select a GGUF metadata on the device
                getContent.launch(arrayOf("*/*"))
            }
        }
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

    private fun handleUserInput() {
        userInputEt.text.toString().also { userMsg ->
            if (userMsg.isEmpty()) {
                Toast.makeText(this, "Input message is empty!", Toast.LENGTH_SHORT).show()
            } else {
                userInputEt.text = null
                userInputEt.isEnabled = false
                userActionFab.isEnabled = false

                // Update message states
                messages.add(Message(UUID.randomUUID().toString(), userMsg, true))
                lastAssistantMsg.clear()
                messages.add(Message(UUID.randomUUID().toString(), lastAssistantMsg.toString(), false))

                generationJob = lifecycleScope.launch(Dispatchers.Default) {
                    engine.sendUserPrompt(userMsg)
                        .onCompletion {
                            withContext(Dispatchers.Main) {
                                userInputEt.isEnabled = true
                                userActionFab.isEnabled = true
                            }
                        }.collect { token ->
                            withContext(Dispatchers.Main) {
                                val messageCount = messages.size
                                check(messageCount > 0 && !messages[messageCount - 1].isUser)

                                messages.removeAt(messageCount - 1).copy(
                                    content = lastAssistantMsg.append(token).toString()
                                ).let { messages.add(it) }

                                messageAdapter.notifyItemChanged(messages.size - 1)
                            }
                        }
                }
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

    override fun onDestroy() {
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
