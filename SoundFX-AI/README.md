# SoundFX AI

Standalone Android Studio project for Wiro.ai ElevenLabs Sound Effects v3.

## Open
Open this folder in Android Studio and let Gradle sync. Build with **Build > Build APK(s)**.

## API behavior
The project follows the saved Wiro reference for:
- POST `https://api.wiro.ai/v1/Run/elevenlabs/sound-effects-v3`
- POST `https://api.wiro.ai/v1/Task/Detail`
- HMAC-SHA256 signature with API key as HMAC key and `API_SECRET + NONCE` as message
- Task status polling and `outputs[].url`

**Important:** the documentation's cURL example is ambiguous: it mentions `multipart/form-data` while showing a JSON body. This starter mirrors that example and flags the ambiguity in the app. Confirm the request encoding with Wiro before production use.

Credentials are stored in app-private SharedPreferences but are not encrypted in this starter. Do not distribute this build with personal credentials embedded.


This delivery is the Android Studio source project, not a compiled APK. A local Android SDK/Gradle build and a live Wiro API test are still required.
