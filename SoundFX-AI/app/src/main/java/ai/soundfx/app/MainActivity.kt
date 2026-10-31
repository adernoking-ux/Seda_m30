package ai.soundfx.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("soundfx_local", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SoundFxScreen(
                    initialKey = prefs.getString("key", "") ?: "",
                    initialSecret = prefs.getString("secret", "") ?: "",
                    onSaveCredentials = { key, secret ->
                        // Stored in this app's private preferences; not encrypted.
                        prefs.edit().putString("key", key).putString("secret", secret).apply()
                    }
                )
            }
        }
    }
}

private class WiroClient(private val apiKey: String, private val apiSecret: String) {
    private val http = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun headers(): Map<String, String> {
        val nonce = Random.nextLong(1, Long.MAX_VALUE).toString()
        val message = apiSecret + nonce
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return mapOf(
            "x-api-key" to apiKey,
            "x-nonce" to nonce,
            "x-signature" to signature
        )
    }

    suspend fun submit(prompt: String, duration: Int, loop: Boolean, influence: Double): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val json = JSONObject()
                .put("prompt", prompt)
                .put("duration", duration)
                .put("outputFormat", "mp3_44100_128")
                .put("loop", loop)
                .put("promptInfluence", influence)
                .toString()

            // The published cURL example describes multipart/form-data while showing
            // a JSON body. This mirrors that example; the documentation is ambiguous
            // about the exact wire encoding, so this may need adjustment if Wiro rejects it.
            val body = json.toRequestBody("multipart/form-data".toMediaType())
            val builder = Request.Builder()
                .url("https://api.wiro.ai/v1/Run/elevenlabs/sound-effects-v3")
                .post(body)
            headers().forEach { (k, v) -> builder.addHeader(k, v) }
            builder.addHeader("Content-Type", "multipart/form-data")

            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("HTTP ${response.code}: $text")
                val obj = JSONObject(text)
                if (!obj.optBoolean("result")) error("Wiro submission error: $text")
                val taskId = obj.optString("taskid")
                val token = obj.optString("socketaccesstoken")
                if (taskId.isBlank() || token.isBlank()) error("Missing taskid/socketaccesstoken: $text")
                taskId to token
            }
        }

    suspend fun detail(taskToken: String): JSONObject = withContext(Dispatchers.IO) {
        val json = JSONObject().put("tasktoken", taskToken).toString()
        val body = json.toRequestBody("multipart/form-data".toMediaType())
        val builder = Request.Builder()
            .url("https://api.wiro.ai/v1/Task/Detail")
            .post(body)
        headers().forEach { (k, v) -> builder.addHeader(k, v) }
        builder.addHeader("Content-Type", "multipart/form-data")
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: $text")
            val obj = JSONObject(text)
            if (!obj.optBoolean("result")) error("Task detail error: $text")
            val list = obj.optJSONArray("tasklist") ?: error("Missing tasklist: $text")
            if (list.length() == 0) error("Empty tasklist: $text")
            list.getJSONObject(0)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoundFxScreen(
    initialKey: String,
    initialSecret: String,
    onSaveCredentials: (String, String) -> Unit
) {
    var apiKey by remember { mutableStateOf(initialKey) }
    var apiSecret by remember { mutableStateOf(initialSecret) }
    var prompt by remember { mutableStateOf("Heavy rain falling on a metal roof, with distant thunder") }
    var duration by remember { mutableFloatStateOf(10f) }
    var loop by remember { mutableStateOf(false) }
    val influence = 0.3f // documented default; no supported range is stated in the reference
    var status by remember { mutableStateOf("آماده تولید") }
    var taskId by remember { mutableStateOf("") }
    var outputUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    CompositionLocalProvider(LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("SoundFX AI") })
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("تولید افکت صوتی با هوش مصنوعی", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("Wiro API Key") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = apiSecret, onValueChange = { apiSecret = it },
                    label = { Text("Wiro API Secret") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, visualTransformation = PasswordVisualTransformation()
                )
                Button(
                    onClick = { onSaveCredentials(apiKey.trim(), apiSecret.trim()); status = "کلیدها در حافظه خصوصی برنامه ذخیره شدند" },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("ذخیره تنظیمات API") }

                HorizontalDivider()
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it },
                    label = { Text("توضیح افکت صوتی (ترجیحاً انگلیسی)") },
                    modifier = Modifier.fillMaxWidth(), minLines = 3
                )
                Text("مدت صدا: ${duration.toInt()} ثانیه")
                Slider(value = duration, onValueChange = { duration = it }, valueRange = 1f..30f, steps = 28)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Loop")
                    Switch(checked = loop, onCheckedChange = { loop = it })
                }
                Text("Prompt Influence: 0.30 (مقدار پیش‌فرض مستندات)")
                Button(
                    enabled = !busy && prompt.isNotBlank() && apiKey.isNotBlank() && apiSecret.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            busy = true
                            outputUrl = ""
                            try {
                                val client = WiroClient(apiKey.trim(), apiSecret.trim())
                                status = "در حال ارسال درخواست..."
                                val (id, token) = client.submit(prompt.trim(), duration.toInt(), loop, influence.toDouble())
                                taskId = id
                                status = "در صف تولید..."
                                val running = setOf(
                                    "task_queue", "task_accept", "task_assign",
                                    "task_preprocess_start", "task_preprocess_end",
                                    "task_start", "task_output"
                                )
                                while (true) {
                                    val task = client.detail(token)
                                    val s = task.optString("status")
                                    status = "وضعیت: $s"
                                    if (s == "task_postprocess_end") {
                                        val outputs = task.optJSONArray("outputs")
                                        if (outputs != null && outputs.length() > 0) {
                                            outputUrl = outputs.getJSONObject(0).optString("url")
                                        }
                                        status = if (outputUrl.isNotBlank()) "تولید با موفقیت تمام شد" else "تکمیل شد؛ لینک خروجی در پاسخ نبود"
                                        break
                                    }
                                    if (s == "task_cancel") {
                                        status = "تولید لغو شد"
                                        break
                                    }
                                    if (s !in running) error("وضعیت ناشناخته: $s")
                                    delay(2500)
                                }
                            } catch (e: Exception) {
                                status = "خطا: ${e.message ?: "خطای نامشخص"}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text("ساخت افکت صوتی")
                }
                if (taskId.isNotBlank()) Text("Task ID: $taskId", style = MaterialTheme.typography.bodySmall)
                Text(status)
                if (outputUrl.isNotBlank()) {
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(outputUrl), "audio/*")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Icon(Icons.Default.PlayArrow, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("باز کردن / پخش خروجی") }
                    Text(outputUrl, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "نکته: مستندات Wiro در نمونه cURL درباره multipart/form-data و بدنه JSON ابهام دارد. " +
                    "اتصال واقعی باید با حساب Wiro آزمایش شود. کلیدها در حافظه خصوصی برنامه ذخیره می‌شوند، اما در این نسخه رمزگذاری نشده‌اند.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
