package com.better.heybox.hooks

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.LruCache
import com.better.heybox.App
import com.better.heybox.MainModule
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.LinkedHashMap

class AIClickbaitChecker private constructor() {

    fun interface VerdictCallback {
        fun onVerdicts(verdicts: Map<String, Boolean>)
    }

    fun interface TestCallback {
        fun onResult(ok: Boolean, message: String)
    }

    companion object {

        @JvmField
        val PROVIDER_IDS = arrayOf(
            "deepseek", "kimi", "qwen", "zhipu", "openai", "openrouter", "local", "custom"
        )

        @JvmField
        val PROVIDER_LABELS = arrayOf(
            "DeepSeek", "Kimi（月之暗面）", "通义千问", "智谱 GLM",
            "OpenAI", "OpenRouter", "本地模型（Ollama 等）", "自定义"
        )

        @JvmField
        val PROVIDER_BASE_URLS = arrayOf(
            "https://api.deepseek.com/v1",
            "https://api.moonshot.cn/v1",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://open.bigmodel.cn/api/paas/v4",
            "https://api.openai.com/v1",
            "https://openrouter.ai/api/v1",
            "http://127.0.0.1:11434/v1",
            "",
        )

        @JvmField
        val PROVIDER_MODELS = arrayOf(
            "deepseek-chat", "moonshot-v1-8k", "qwen-turbo", "glm-4-flash",
            "gpt-4o-mini", "openrouter/auto", "qwen2.5:3b", "",
        )

        const val DEFAULT_PROMPT =
            "你是社区帖子的标题党判定器。逐条判断给定帖子标题是否属于标题党：夸大其词、" +
                "制造悬念或恐慌、诱导点击或互动（例如「不看后悔」「速来」「惊了」「白嫖」" +
                "「最后一天」「求转发」、连续多个感叹号或问号等）。" +
                "普通的技术分享、攻略、新闻、个人经历、正常求助不算标题党；拿不准一律判 false。" +
                "只输出 JSON，格式：{\"verdicts\":[{\"id\":<编号>,\"clickbait\":<true|false>}]}，" +
                "不要输出任何其他文字。"

        private const val BATCH_SIZE = 8
        private const val BATCH_DELAY_MS = 400L
        private const val COOLDOWN_HTTP_5XX_MS = 30_000L
        private const val COOLDOWN_IO_MS = 60_000L
        private const val COOLDOWN_AUTH_MS = 300_000L

        @JvmField
        val MAX_TOKEN_OPTIONS = intArrayOf(300, 500, 700, 1000, 1500)

        private const val DEFAULT_MAX_TOKENS = 700

        private val sVerdictCache = LruCache<String, Boolean>(512)
        private val sLock = Any()
        private val sPending = LinkedHashMap<String, String>()

        private var sThread: HandlerThread? = null
        private var sWorkHandler: Handler? = null
        private val sMainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var sFlushScheduled = false

        @Volatile
        private var sCooldownUntil = 0L

        @Volatile
        private var sModule: MainModule? = null

        @Volatile
        private var sCallback: VerdictCallback? = null

        @JvmStatic
        fun getCached(key: String?): Boolean? {
            return if (key == null) null else sVerdictCache[key]
        }

        @JvmStatic
        fun maxTokens(module: MainModule): Int {
            try {
                val v = Integer.parseInt(module.getString(App.KEY_AI_MAX_TOKENS, "")!!.trim())
                if (v >= 100 && v <= 4000) {
                    return v
                }
            } catch (ignored: Throwable) {
            }
            return DEFAULT_MAX_TOKENS
        }

        @JvmStatic
        fun providerIndex(id: String?): Int {
            if (id == null) {
                return -1
            }
            for (i in PROVIDER_IDS.indices) {
                if (PROVIDER_IDS[i] == id) {
                    return i
                }
            }
            return -1
        }

        @JvmStatic
        fun providerLabel(id: String?): String {
            val idx = providerIndex(id)
            return if (idx >= 0) PROVIDER_LABELS[idx] else "未选择"
        }

        @JvmStatic
        fun requestVerdicts(
            module: MainModule,
            key: String,
            title: String,
            callback: VerdictCallback?
        ) {
            if (System.currentTimeMillis() < sCooldownUntil) {
                return
            }
            synchronized(sLock) {
                sPending[key] = title
                sCallback = callback
                sModule = module
            }
            ensureWorker()
            if (sFlushScheduled) {
                return
            }
            sFlushScheduled = true
            sWorkHandler!!.postDelayed({ flush() }, BATCH_DELAY_MS)
        }

        @JvmStatic
        fun testConnection(module: MainModule, callback: TestCallback?) {
            ensureWorker()
            sWorkHandler!!.post {
                try {
                    val base = module.getString(App.KEY_AI_BASE_URL, "")!!.trim()
                    val model = module.getString(App.KEY_AI_MODEL, "")!!.trim()
                    if (base.isEmpty() || model.isEmpty()) {
                        postTest(callback, false, "请先配置 API 地址与模型")
                        return@post
                    }
                    val body = JSONObject()
                    body.put("model", model)
                    body.put("max_tokens", 8)
                    val messages = JSONArray()
                    messages.put(JSONObject().put("role", "user").put("content", "回复OK"))
                    body.put("messages", messages)
                    val code = postChatCompletion(
                        base,
                        module.getString(App.KEY_AI_TOKEN, "")!!.trim(), body, null
                    )
                    postTest(
                        callback, code == 200,
                        if (code == 200) "模型 " + model else "HTTP " + code
                    )
                } catch (t: Throwable) {
                    postTest(callback, false, "${t.message}")
                }
            }
        }

        private fun postTest(callback: TestCallback?, ok: Boolean, message: String) {
            sMainHandler.post {
                try {
                    callback?.onResult(ok, message)
                } catch (ignored: Throwable) {
                }
            }
        }

        private fun ensureWorker() {
            synchronized(sLock) {
                val thread = sThread
                if (thread == null) {
                    val created = HandlerThread("bhx-ai-checker")
                    sThread = created
                    created.start()
                    sWorkHandler = Handler(created.looper)
                }
            }
        }

        private fun flush() {
            val batch = ArrayList<Array<String>>()
            synchronized(sLock) {
                sFlushScheduled = false
                if (sPending.isEmpty() || System.currentTimeMillis() < sCooldownUntil) {
                    return
                }
                val it = sPending.entries.iterator()
                while (it.hasNext() && batch.size < BATCH_SIZE) {
                    val e = it.next()
                    batch.add(arrayOf(e.key, e.value))
                    it.remove()
                }
            }
            val module = sModule
            if (module != null && batch.isNotEmpty()) {
                try {
                    performBatch(module, batch)
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "AI 判定请求异常: $t")
                }
            }
            synchronized(sLock) {
                if (sPending.isNotEmpty()) {
                    sWorkHandler!!.postDelayed({ flush() }, 100L)
                }
            }
        }

        private fun performBatch(module: MainModule, batch: List<Array<String>>) {
            val base = module.getString(App.KEY_AI_BASE_URL, "")!!.trim()
            val model = module.getString(App.KEY_AI_MODEL, "")!!.trim()
            if (base.isEmpty() || model.isEmpty()) {
                return
            }
            val user = StringBuilder("判断以下帖子标题：\n")
            for (i in batch.indices) {
                var title = batch[i][1]
                if (title.length > 120) {
                    title = title.substring(0, 120)
                }
                user.append(i + 1).append(". ").append(title).append('\n')
            }
            var prompt = module.getString(App.KEY_AI_PROMPT, "")!!.trim()
            if (prompt.isEmpty()) {
                prompt = DEFAULT_PROMPT
            }
            val response = StringBuilder()
            val code = try {
                val body = JSONObject()
                body.put("model", model)
                body.put("temperature", 0)
                body.put("max_tokens", maxTokens(module))
                val messages = JSONArray()
                messages.put(JSONObject().put("role", "system").put("content", prompt))
                messages.put(JSONObject().put("role", "user").put("content", user.toString()))
                body.put("messages", messages)
                postChatCompletion(
                    base, module.getString(App.KEY_AI_TOKEN, "")!!.trim(),
                    body, response
                )
            } catch (e: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "AI 判定网络异常，进入冷却: $e")
                enterCooldown(COOLDOWN_IO_MS)
                return
            }
            if (code != 200) {
                module.logd(Log.WARN, MainModule.TAG, "AI 判定失败 HTTP $code，进入冷却")
                enterCooldown(if (code == 401 || code == 403) COOLDOWN_AUTH_MS else COOLDOWN_HTTP_5XX_MS)
                return
            }
            val verdicts = parseVerdicts(response.toString(), batch)
            if (verdicts.isEmpty()) {
                val snippet = if (response.length > 160) {
                    response.substring(response.length - 160)
                } else {
                    response.toString()
                }
                module.logd(
                    Log.WARN, MainModule.TAG,
                    "AI 判定解析为空（batch=${batch.size}），响应尾: $snippet"
                )
            }
            for (item in batch) {
                val v = verdicts[item[0]]
                if (v != null) {
                    sVerdictCache.put(item[0], v)
                }
            }
            val callback = sCallback
            if (callback != null && verdicts.isNotEmpty()) {
                sMainHandler.post {
                    try {
                        callback.onVerdicts(verdicts)
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }

        private fun parseVerdicts(raw: String, batch: List<Array<String>>): Map<String, Boolean> {
            val out = LinkedHashMap<String, Boolean>()
            try {
                val content = extractContent(raw)
                if (content == null) {
                    return out
                }
                val arr = extractVerdictArray(content)
                if (arr == null) {
                    return out
                }
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i)
                    if (v == null) {
                        continue
                    }
                    val clickbait = readFlag(v)
                    if (clickbait == null) {
                        continue
                    }
                    val id = v.optInt("id", i + 1)
                    if (id >= 1 && id <= batch.size) {
                        out[batch[id - 1][0]] = clickbait
                    }
                }
            } catch (ignored: Throwable) {
            }
            return out
        }

        private fun extractContent(raw: String): String? {
            try {
                val root = JSONObject(raw)
                val choices = root.optJSONArray("choices")
                if (choices == null || choices.length() == 0) {
                    return null
                }
                val message = choices.optJSONObject(0).optJSONObject("message")
                return if (message == null) null else message.optString("content", "")
            } catch (t: Throwable) {
                return null
            }
        }

        private fun extractVerdictArray(content: String): JSONArray? {
            val c = content.trim()
            val arrIdx = c.indexOf('[')
            val objIdx = c.indexOf('{')
            if (arrIdx >= 0 && (objIdx < 0 || arrIdx < objIdx)) {
                try {
                    return JSONArray(c.substring(arrIdx, c.lastIndexOf(']') + 1))
                } catch (t: Throwable) {
                    return null
                }
            }
            if (objIdx < 0) {
                return null
            }
            try {
                val obj = JSONObject(c.substring(objIdx, c.lastIndexOf('}') + 1))
                val verdicts = obj.optJSONArray("verdicts")
                if (verdicts != null) {
                    return verdicts
                }
                val arr = JSONArray()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    arr.put(
                        JSONObject()
                            .put("id", Integer.parseInt(k.trim()))
                            .put("clickbait", obj.optBoolean(k))
                    )
                }
                return arr
            } catch (t: Throwable) {
                return null
            }
        }

        private fun readFlag(v: JSONObject): Boolean? {
            for (key in arrayOf("clickbait", "block", "is_clickbait")) {
                if (v.has(key)) {
                    return v.optBoolean(key)
                }
            }
            return null
        }

        private fun enterCooldown(durationMs: Long) {
            sCooldownUntil = System.currentTimeMillis() + durationMs
        }

        @Throws(IOException::class)
        private fun postChatCompletion(
            base: String,
            token: String,
            body: JSONObject,
            responseOut: StringBuilder?
        ): Int {
            var url = if (base.startsWith("http")) base else "https://" + base
            if (url.endsWith("/")) {
                url = url.substring(0, url.length - 1)
            }
            url += "/chat/completions"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            if (token.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token)
            }
            conn.doOutput = true
            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }
            val code = conn.responseCode
            val input: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
            val sb = StringBuilder()
            if (input != null) {
                val buf = ByteArray(4096)
                while (true) {
                    val len = input.read(buf)
                    if (len == -1) {
                        break
                    }
                    sb.append(String(buf, 0, len, StandardCharsets.UTF_8))
                }
                input.close()
            }
            if (responseOut != null) {
                responseOut.append(sb)
            }
            conn.disconnect()
            return code
        }
    }
}
