package com.fishpet

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** DeepSeek 接口：/user/balance（余额）和 /chat/completions（流式聊天）。字段以官方文档为准。 */
object DeepSeekApi {
    private const val BASE = "https://api.deepseek.com"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // 流式不能有读超时；非流式请求另设 callTimeout
        .build()

    data class Balance(
        val ok: Boolean,
        val error: String? = null,
        val currency: String? = null,
        val total: Double? = null,
        val available: Boolean = true,
    )

    suspend fun balance(key: String): Balance = withContext(Dispatchers.IO) {
        if (key.isEmpty()) return@withContext Balance(false, "NO_KEY")
        try {
            val req = Request.Builder().url("$BASE/user/balance")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $key")
                .build()
            client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext Balance(false, "HTTP_${r.code}")
                val j = JSONObject(r.body?.string() ?: "{}")
                val info = j.optJSONArray("balance_infos")?.optJSONObject(0)
                Balance(
                    ok = true,
                    currency = info?.optString("currency"),
                    total = info?.optString("total_balance")?.toDoubleOrNull(),
                    available = j.optBoolean("is_available", true),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Balance(false, "NETWORK")
        }
    }

    fun describe(b: Balance): String {
        if (!b.ok) return when (b.error) {
            "NO_KEY" -> "还没设置 API Key，去主界面填一下。"
            "NETWORK" -> "网络不通，查不了余额。"
            "HTTP_401" -> "API Key 好像不对。"
            else -> "余额查询失败：${b.error}"
        }
        val sym = when (b.currency) { "CNY" -> "¥"; "USD" -> "$"; else -> "" }
        val total = b.total?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "?"
        return "余额 $sym$total" + if (b.available) "" else "（账户暂不可用）"
    }

    /**
     * 流式聊天（SSE）。每收到一段增量文本就回调 onDelta。
     * 返回 null 表示成功，否则是错误码（NO_KEY / HTTP_xxx / NETWORK / EMPTY）。
     */
    suspend fun chatStream(
        key: String,
        messages: List<Pair<String, String>>,
        onDelta: suspend (String) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        if (key.isEmpty()) return@withContext "NO_KEY"
        try {
            val arr = JSONArray()
            messages.forEach { (role, content) -> arr.put(JSONObject().put("role", role).put("content", content)) }
            val body = JSONObject()
                .put("model", "deepseek-chat")
                .put("stream", true)
                .put("messages", arr)
                .toString()
            val req = Request.Builder().url("$BASE/chat/completions")
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext "HTTP_${r.code}"
                val src = r.body?.source() ?: return@withContext "EMPTY"
                while (true) {
                    val line = src.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val content = try {
                        JSONObject(data).optJSONArray("choices")?.optJSONObject(0)
                            ?.optJSONObject("delta")?.opt("content")
                    } catch (_: Exception) { null }
                    if (content is String && content.isNotEmpty()) onDelta(content)
                }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "NETWORK"
        }
    }

    /**
     * 非流式补全，用于后台任务（例如从对话里提炼记忆）。
     * 返回 (文本, 错误码)；成功时错误码为 null。
     */
    suspend fun chatOnce(key: String, messages: List<Pair<String, String>>, maxTokens: Int = 300): Pair<String?, String?> =
        withContext(Dispatchers.IO) {
            if (key.isEmpty()) return@withContext null to "NO_KEY"
            try {
                val arr = JSONArray()
                messages.forEach { (role, content) -> arr.put(JSONObject().put("role", role).put("content", content)) }
                val body = JSONObject()
                    .put("model", "deepseek-chat")
                    .put("stream", false)
                    .put("temperature", 0)
                    .put("max_tokens", maxTokens)
                    .put("messages", arr)
                    .toString()
                val req = Request.Builder().url("$BASE/chat/completions")
                    .header("Authorization", "Bearer $key")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newBuilder().callTimeout(45, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null to "HTTP_${r.code}"
                    val text = JSONObject(r.body?.string() ?: "{}")
                        .optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content")
                    text to null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null to "NETWORK"
            }
        }
}
