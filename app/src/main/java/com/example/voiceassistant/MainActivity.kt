package com.example.voiceassistant

import android.Manifest
import android.content.Intent
import android.provider.Settings
import com.example.voiceassistant.notify.MessageNotificationListener
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.voiceassistant.data.SecurePrefs
import com.example.voiceassistant.llm.GroqClient
import com.example.voiceassistant.tools.PhoneTools
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SecurePrefs
    private lateinit var phoneTools: PhoneTools
    private var groq: GroqClient? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(
                this,
                "Some permissions denied. Call/SMS features may be limited.\n" +
                "On Android 15+ go to App info → ⋮ → Allow restricted settings",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SecurePrefs(this)
        phoneTools = PhoneTools(this)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
            }
        }

        if (prefs.apiKey.isNotBlank()) {
            groq = GroqClient(prefs.apiKey, prefs.model, phoneTools)
        }

        requestNeededPermissions()

        setContent {
            MaterialTheme {
                VoiceAssistantScreen()
            }
        }
    }

    private fun requestNeededPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun VoiceAssistantScreen() {
        var apiKey by remember { mutableStateOf(prefs.apiKey) }
        var model by remember { mutableStateOf(prefs.model) }
        var showSettings by remember { mutableStateOf(apiKey.isBlank()) }
        var isListening by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Ready") }
        var transcript by remember { mutableStateOf("") }
        var reply by remember { mutableStateOf("") }
        var log by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        val scroll = rememberScrollState()

        fun speak(text: String) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt-${System.currentTimeMillis()}")
        }

        fun startListening() {
            if (speechRecognizer == null) {
                if (!SpeechRecognizer.isRecognitionAvailable(this@MainActivity)) {
                    status = "Speech recognition not available"
                    return
                }
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this@MainActivity)
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    status = "Listening…"
                    isListening = true
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    status = "Processing…"
                    isListening = false
                }
                override fun onError(error: Int) {
                    isListening = false
                    status = "Speech error: $error"
                }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = texts?.firstOrNull() ?: return
                    transcript = text
                    status = "Thinking…"
                    log += "\nYou: $text"

                    val client = groq
                    if (client == null) {
                        status = "Set API key first"
                        return
                    }

                    scope.launch {
                        try {
                            val answer = client.chat(text)
                            reply = answer
                            log += "\nAssistant: $answer\n"
                            status = "Ready"
                            speak(answer)
                        } catch (e: Exception) {
                            status = "Error: ${e.message}"
                            log += "\nError: ${e.message}\n"
                        }
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    transcript = texts?.firstOrNull() ?: ""
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer?.startListening(intent)
        }

        fun stopListening() {
            speechRecognizer?.stopListening()
            isListening = false
            status = "Ready"
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Voice Assistant + Groq") },
                    actions = {
                        IconButton(onClick = { showSettings = !showSettings }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize()
                    .verticalScroll(scroll),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (showSettings) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("Groq API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model (e.g. openai/gpt-oss-20b)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        prefs.apiKey = apiKey.trim()
                        prefs.model = model.trim()
                        groq = if (apiKey.isNotBlank()) {
                            GroqClient(apiKey.trim(), model.trim(), phoneTools)
                        } else null
                        showSettings = false
                        Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Save")
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Get a free key at console.groq.com/keys\n" +
                        "Default model openai/gpt-oss-20b. Free tier is rate-limited.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(24.dp))
                }

                Text(status, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                if (transcript.isNotEmpty()) {
                    Text("You said: $transcript", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                }
                if (reply.isNotEmpty()) {
                    Text("Assistant: $reply", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                }

                FloatingActionButton(
                    onClick = {
                        if (isListening) stopListening() else startListening()
                    },
                    containerColor = if (isListening) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isListening) "Stop" else "Speak"
                    )
                }

                Spacer(Modifier.height(16.dp))
                TextButton(onClick = {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) {
                    val on = MessageNotificationListener.isEnabled(this@MainActivity)
                    Text(if (on) "Message notifications: on" else "Enable message notifications (for RCS)")
                }

                Spacer(Modifier.height(24.dp))
                Text("Conversation log", style = MaterialTheme.typography.titleSmall)
                Text(log.ifBlank { "(empty)" }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
