package com.example.voiceassistant.llm

import com.example.voiceassistant.tools.PhoneTools
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Groq client (OpenAI-compatible).
 * Base URL: https://api.groq.com/openai/v1
 * Free key: https://console.groq.com/keys
 */
class GroqClient(
    private val apiKey: String,
    private val model: String = "openai/gpt-oss-20b",
    private val phoneTools: PhoneTools
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    private val systemPrompt = """
You are a helpful voice assistant running on the user's Android phone.
You can control the phone using tools. Always respond in a short, natural spoken style.
When the user asks to text someone by name, call send_sms with name and message. If they also want their location, set include_location to true. Do not invent coordinates.
When the user asks what someone texted, or to read recent messages, call read_recent_messages. That includes message notifications, which is how RCS from Google Messages is visible. Do not invent message text.
Do not invent phone numbers or message content.
""".trimIndent()

    private val toolsJson = """
[
  {
    "type": "function",
    "function": {
      "name": "lookup_contact",
      "description": "Find phone numbers in the Android contacts database used by the default Contacts app",
      "parameters": {
        "type": "object",
        "properties": {
          "name": {"type": "string", "description": "Contact name or part of name"}
        },
        "required": ["name"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "open_contact",
      "description": "Open a person in the phone's default Contacts app",
      "parameters": {
        "type": "object",
        "properties": {
          "name": {"type": "string", "description": "Contact name or part of name"}
        },
        "required": ["name"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "make_call",
      "description": "Place a phone call to a number (requires CALL_PHONE permission)",
      "parameters": {
        "type": "object",
        "properties": {
          "number": {"type": "string", "description": "Phone number to call"}
        },
        "required": ["number"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "dial",
      "description": "Open the phone dialer with a number pre-filled (safer, no special permission)",
      "parameters": {
        "type": "object",
        "properties": {
          "number": {"type": "string", "description": "Phone number"}
        },
        "required": ["number"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "send_sms",
      "description": "Open a draft in Google Messages for a contact name or a phone number. Set include_location true when the user wants their current location attached. Does not send silently.",
      "parameters": {
        "type": "object",
        "properties": {
          "name": {"type": "string", "description": "Contact name, if a number is not already known"},
          "number": {"type": "string", "description": "Recipient phone number, if already known"},
          "message": {"type": "string", "description": "Text of the message"},
          "include_location": {"type": "boolean", "description": "Append a Google Maps link for the user's current location"}
        },
        "required": ["message"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "read_recent_messages",
      "description": "Read recent SMS plus message notifications captured from Google Messages, including RCS notifications.",
      "parameters": {
        "type": "object",
        "properties": {
          "limit": {"type": "integer", "description": "How many messages to return (default 5)"}
        }
      }
    }
  }
]
""".trimIndent()

    data class ChatMessage(val role: String, val content: String)

    private val history = mutableListOf<ChatMessage>()

    suspend fun chat(userText: String): String = withContext(Dispatchers.IO) {
        history.add(ChatMessage("user", userText))

        val messages = JsonArray()
        messages.add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", systemPrompt)
        })
        history.forEach { msg ->
            messages.add(JsonObject().apply {
                addProperty("role", msg.role)
                addProperty("content", msg.content)
            })
        }

        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            add("tools", JsonParser.parseString(toolsJson).asJsonArray)
            addProperty("stream", false)
        }

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: return@withContext "Empty response from Groq"

        if (!response.isSuccessful) {
            return@withContext "Groq error ${response.code}: $responseBody"
        }

        val root = JsonParser.parseString(responseBody).asJsonObject
        val choice = root.getAsJsonArray("choices")?.get(0)?.asJsonObject
            ?: return@withContext "No choices in response"

        val message = choice.getAsJsonObject("message")
        val toolCalls = message.getAsJsonArray("tool_calls")

        if (toolCalls != null && toolCalls.size() > 0) {
            val toolResults = mutableListOf<String>()
            for (i in 0 until toolCalls.size()) {
                val tc = toolCalls[i].asJsonObject
                val fn = tc.getAsJsonObject("function")
                val name = fn.get("name").asString
                val argsStr = fn.get("arguments").asString
                val args = JsonParser.parseString(argsStr).asJsonObject

                val result = when (name) {
                    "lookup_contact" -> phoneTools.lookupContact(args.get("name").asString)
                    "open_contact" -> phoneTools.openContact(args.get("name").asString)
                    "make_call" -> phoneTools.makeCall(args.get("number").asString)
                    "dial" -> phoneTools.dial(args.get("number").asString)
                    "send_sms" -> phoneTools.sendMessage(
                        number = if (args.has("number")) args.get("number").asString else null,
                        name = if (args.has("name")) args.get("name").asString else null,
                        message = args.get("message").asString,
                        includeLocation = args.has("include_location") && args.get("include_location").asBoolean
                    )
                    "read_recent_messages" -> {
                        val limit = if (args.has("limit")) args.get("limit").asInt else 5
                        phoneTools.readRecentMessages(limit)
                    }
                    else -> "Unknown tool: $name"
                }
                toolResults.add("$name → $result")
            }

            val followUp = "Tool results:\n${toolResults.joinToString("\n")}\nPlease give a short spoken confirmation to the user."
            history.add(ChatMessage("assistant", message.get("content")?.asString ?: ""))
            history.add(ChatMessage("user", followUp))

            val messages2 = JsonArray()
            messages2.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", systemPrompt)
            })
            history.forEach { msg ->
                messages2.add(JsonObject().apply {
                    addProperty("role", msg.role)
                    addProperty("content", msg.content)
                })
            }

            val body2 = JsonObject().apply {
                addProperty("model", model)
                add("messages", messages2)
                addProperty("stream", false)
            }

            val req2 = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body2.toString().toRequestBody(jsonMedia))
                .build()

            val resp2 = client.newCall(req2).execute()
            val bodyStr2 = resp2.body?.string() ?: return@withContext toolResults.joinToString("\n")
            val root2 = JsonParser.parseString(bodyStr2).asJsonObject
            val content = root2.getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
                ?: toolResults.joinToString("\n")

            history.add(ChatMessage("assistant", content))
            content
        } else {
            val content = message.get("content")?.asString ?: "No response"
            history.add(ChatMessage("assistant", content))
            content
        }
    }

    fun clearHistory() {
        history.clear()
    }
}
