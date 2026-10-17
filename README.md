# Voice Assistant + Groq (Android)

Native Kotlin + Jetpack Compose voice assistant that controls contacts, calls, and SMS using the **Groq free API**.

## Features
- Push-to-talk speech recognition
- Groq (OpenAI-compatible) with tool calling
- Tools: lookup contact, make call, dial, send SMS, read recent messages
- Android Text-to-Speech replies
- Encrypted storage of your API key

## Setup

### 1. Get a free Groq API key
1. Go to https://console.groq.com/keys and create an account
2. Create an API key (no credit card)
3. Free tier is rate-limited (roughly 30 requests/min and ~1,000 requests/day on main models)

### 2. Open in Android Studio
1. File → Open → select the `VoiceAssistant` folder
2. Let Gradle sync
3. If icons are missing, right-click `res` → New → Image Asset → create a simple launcher icon

### 3. Build & install
- Connect your phone (USB debugging on)
- Run the app
- Or: `./gradlew assembleDebug` then sideload `app/build/outputs/apk/debug/app-debug.apk`

### 4. First launch
1. Paste your Groq API key. Default model is `openai/gpt-oss-20b` (fast). `openai/gpt-oss-120b` is stronger.
2. Grant all permissions when asked
3. **Android 15+**: App info → ⋮ menu → **Allow restricted settings**, then re-enable SMS/Phone permissions

## Example voice commands
- "Call Mom"
- "What's Dad's number?"
- "Text John that I'm running late"
- "Read my last 3 messages"

## Permissions note
Personal sideloading only. Google Play rejects apps that request SMS and Call permissions unless they are the default Phone/SMS handler.

## Project structure
```
app/src/main/java/com/example/voiceassistant/
  MainActivity.kt          – UI + speech + TTS
  data/SecurePrefs.kt      – encrypted API key storage
  llm/GroqClient.kt        – Groq client + tool calling
  tools/PhoneTools.kt      – contacts / call / SMS helpers
```
