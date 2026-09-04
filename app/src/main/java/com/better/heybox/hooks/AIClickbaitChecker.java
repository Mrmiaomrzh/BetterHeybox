package com.better.heybox.hooks;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.better.heybox.App;
import com.better.heybox.MainModule;

/** AI 标题党判定：OpenAI 兼容协议，批量 + 缓存 + 冷却，fail-open */
public final class AIClickbaitChecker {

    public interface VerdictCallback {
        /** verdicts: 缓存键 → 是否标题党；主线程回调 */
        void onVerdicts(Map<String, Boolean> verdicts);
    }

    public interface TestCallback {
        /** ok=true 时 message 为成功描述；主线程回调 */
        void onResult(boolean ok, String message);
    }

    // ---------- 提供商预设 ----------

    public static final String[] PROVIDER_IDS = {
            "deepseek", "kimi", "qwen", "zhipu", "openai", "openrouter", "local", "custom"};
    public static final String[] PROVIDER_LABELS = {
            "DeepSeek", "Kimi（月之暗面）", "通义千问", "智谱 GLM",
            "OpenAI", "OpenRouter", "本地模型（Ollama 等）", "自定义"};
    public static final String[] PROVIDER_BASE_URLS = {
            "https://api.deepseek.com/v1",
            "https://api.moonshot.cn/v1",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://open.bigmodel.cn/api/paas/v4",
            "https://api.openai.com/v1",
            "https://openrouter.ai/api/v1",
            "http://127.0.0.1:11434/v1",
            "",
    };
    public static final String[] PROVIDER_MODELS = {
            "deepseek-chat", "moonshot-v1-8k", "qwen-turbo", "glm-4-flash",
            "gpt-4o-mini", "openrouter/auto", "qwen2.5:3b", "",
    };

    /** 默认判定提示词 */
    public static final String DEFAULT_PROMPT =
            "你是社区帖子的标题党判定器。逐条判断给定帖子标题是否属于标题党：夸大其词、"
                    + "制造悬念或恐慌、诱导点击或互动（例如「不看后悔」「速来」「惊了」「白嫖」"
                    + "「最后一天」「求转发」、连续多个感叹号或问号等）。"
                    + "普通的技术分享、攻略、新闻、个人经历、正常求助不算标题党；拿不准一律判 false。"
                    + "只输出 JSON，格式：{\"verdicts\":[{\"id\":<编号>,\"clickbait\":<true|false>}]}，"
                    + "不要输出任何其他文字。";

    private static final int BATCH_SIZE = 8;
    private static final long BATCH_DELAY_MS = 400;
    private static final long COOLDOWN_HTTP_5XX_MS = 30_000;
    private static final long COOLDOWN_IO_MS = 60_000;
    private static final long COOLDOWN_AUTH_MS = 300_000;

    private static final LruCache<String, Boolean> sVerdictCache = new LruCache<>(512);
    private static final Object sLock = new Object();
    private static final LinkedHashMap<String, String> sPending = new LinkedHashMap<>();

    private static HandlerThread sThread;
    private static Handler sWorkHandler;
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static volatile boolean sFlushScheduled;
    private static volatile long sCooldownUntil;
    private static volatile MainModule sModule;
    private static volatile VerdictCallback sCallback;

    private AIClickbaitChecker() {
    }

    public static Boolean getCached(String key) {
        return key == null ? null : sVerdictCache.get(key);
    }

    public static int providerIndex(String id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < PROVIDER_IDS.length; i++) {
            if (PROVIDER_IDS[i].equals(id)) {
                return i;
            }
        }
        return -1;
    }

    public static String providerLabel(String id) {
        int idx = providerIndex(id);
        return idx >= 0 ? PROVIDER_LABELS[idx] : "未选择";
    }

    /** 入队待判定；冷却期直接丢弃 */
    public static void requestVerdicts(MainModule module, String key, String title,
                                       VerdictCallback callback) {
        if (System.currentTimeMillis() < sCooldownUntil) {
            return;
        }
        synchronized (sLock) {
            sPending.put(key, title);
            sCallback = callback;
            sModule = module;
        }
        ensureWorker();
        if (sFlushScheduled) {
            return;
        }
        sFlushScheduled = true;
        sWorkHandler.postDelayed(AIClickbaitChecker::flush, BATCH_DELAY_MS);
    }

    /** 测试连接，主线程回调 */
    public static void testConnection(MainModule module, TestCallback callback) {
        ensureWorker();
        sWorkHandler.post(() -> {
            try {
                String base = module.getString(App.KEY_AI_BASE_URL, "").trim();
                String model = module.getString(App.KEY_AI_MODEL, "").trim();
                if (base.isEmpty() || model.isEmpty()) {
                    postTest(callback, false, "请先配置 API 地址与模型");
                    return;
                }
                JSONObject body = new JSONObject();
                body.put("model", model);
                body.put("max_tokens", 8);
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "user").put("content", "回复OK"));
                body.put("messages", messages);
                int code = postChatCompletion(base,
                        module.getString(App.KEY_AI_TOKEN, "").trim(), body, null);
                postTest(callback, code == 200,
                        code == 200 ? "模型 " + model : "HTTP " + code);
            } catch (Throwable t) {
                postTest(callback, false, String.valueOf(t.getMessage()));
            }
        });
    }

    private static void postTest(TestCallback callback, boolean ok, String message) {
        sMainHandler.post(() -> {
            try {
                callback.onResult(ok, message);
            } catch (Throwable ignored) {
            }
        });
    }

    private static void ensureWorker() {
        synchronized (sLock) {
            if (sThread == null) {
                sThread = new HandlerThread("bhx-ai-checker");
                sThread.start();
                sWorkHandler = new Handler(sThread.getLooper());
            }
        }
    }

    private static void flush() {
        List<String[]> batch = new ArrayList<>();
        synchronized (sLock) {
            sFlushScheduled = false;
            if (sPending.isEmpty() || System.currentTimeMillis() < sCooldownUntil) {
                return;
            }
            Iterator<Map.Entry<String, String>> it = sPending.entrySet().iterator();
            while (it.hasNext() && batch.size() < BATCH_SIZE) {
                Map.Entry<String, String> e = it.next();
                batch.add(new String[]{e.getKey(), e.getValue()});
                it.remove();
            }
        }
        MainModule module = sModule;
        if (module != null && !batch.isEmpty()) {
            try {
                performBatch(module, batch);
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "AI 判定请求异常: " + t);
            }
        }
        synchronized (sLock) {
            if (!sPending.isEmpty()) {
                sWorkHandler.postDelayed(AIClickbaitChecker::flush, 100);
            }
        }
    }

    private static void performBatch(MainModule module, List<String[]> batch) {
        String base = module.getString(App.KEY_AI_BASE_URL, "").trim();
        String model = module.getString(App.KEY_AI_MODEL, "").trim();
        if (base.isEmpty() || model.isEmpty()) {
            return;
        }
        StringBuilder user = new StringBuilder("判断以下帖子标题：\n");
        for (int i = 0; i < batch.size(); i++) {
            String title = batch.get(i)[1];
            if (title.length() > 120) {
                title = title.substring(0, 120);
            }
            user.append(i + 1).append(". ").append(title).append('\n');
        }
        String prompt = module.getString(App.KEY_AI_PROMPT, "").trim();
        if (prompt.isEmpty()) {
            prompt = DEFAULT_PROMPT;
        }
        StringBuilder response = new StringBuilder();
        int code;
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("temperature", 0);
            body.put("max_tokens", 400);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", prompt));
            messages.put(new JSONObject().put("role", "user").put("content", user.toString()));
            body.put("messages", messages);
            code = postChatCompletion(base, module.getString(App.KEY_AI_TOKEN, "").trim(),
                    body, response);
        } catch (Throwable e) {
            module.logd(Log.WARN, module.TAG, "AI 判定网络异常，进入冷却: " + e);
            enterCooldown(COOLDOWN_IO_MS);
            return;
        }
        if (code != 200) {
            module.logd(Log.WARN, module.TAG, "AI 判定失败 HTTP " + code + "，进入冷却");
            enterCooldown(code == 401 || code == 403 ? COOLDOWN_AUTH_MS : COOLDOWN_HTTP_5XX_MS);
            return;
        }
        // 解析失败放行
        Map<String, Boolean> verdicts = parseVerdicts(response.toString(), batch);
        for (String[] item : batch) {
            Boolean v = verdicts.get(item[0]);
            if (v != null) {
                sVerdictCache.put(item[0], v);
            }
        }
        VerdictCallback callback = sCallback;
        if (callback != null && !verdicts.isEmpty()) {
            sMainHandler.post(() -> {
                try {
                    callback.onVerdicts(verdicts);
                } catch (Throwable ignored) {
                }
            });
        }
    }

    /** 尽力解析模型输出，失败返回空 */
    private static Map<String, Boolean> parseVerdicts(String raw, List<String[]> batch) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        try {
            String content = extractContent(raw);
            if (content == null) {
                return out;
            }
            JSONArray arr = extractVerdictArray(content);
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject v = arr.optJSONObject(i);
                if (v == null) {
                    continue;
                }
                Boolean clickbait = readFlag(v);
                if (clickbait == null) {
                    continue;
                }
                int id = v.optInt("id", i + 1);
                if (id >= 1 && id <= batch.size()) {
                    out.put(batch.get(id - 1)[0], clickbait);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** choices[0].message.content */
    private static String extractContent(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return null;
            }
            JSONObject message = choices.optJSONObject(0).optJSONObject("message");
            return message == null ? null : message.optString("content", "");
        } catch (Throwable t) {
            return null;
        }
    }

        private static JSONArray extractVerdictArray(String content) {
        String c = content.trim();
        int arrIdx = c.indexOf('[');
        int objIdx = c.indexOf('{');
        if (arrIdx >= 0 && (objIdx < 0 || arrIdx < objIdx)) {
            try {
                return new JSONArray(c.substring(arrIdx, c.lastIndexOf(']') + 1));
            } catch (Throwable t) {
                return null;
            }
        }
        if (objIdx < 0) {
            return null;
        }
        try {
            JSONObject obj = new JSONObject(c.substring(objIdx, c.lastIndexOf('}') + 1));
            JSONArray verdicts = obj.optJSONArray("verdicts");
            if (verdicts != null) {
                return verdicts;
            }
            // 兜底：{"1":true,"2":false} 形态
            JSONArray arr = new JSONArray();
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                arr.put(new JSONObject()
                        .put("id", Integer.parseInt(k.trim()))
                        .put("clickbait", obj.optBoolean(k)));
            }
            return arr;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 兼容常见判定键名 */
    private static Boolean readFlag(JSONObject v) {
        for (String key : new String[]{"clickbait", "block", "is_clickbait"}) {
            if (v.has(key)) {
                return v.optBoolean(key);
            }
        }
        return null;
    }

    private static void enterCooldown(long durationMs) {
        sCooldownUntil = System.currentTimeMillis() + durationMs;
    }

    /** @return HTTP 状态码 */
    private static int postChatCompletion(String base, String token, JSONObject body,
                                          StringBuilder responseOut) throws java.io.IOException {
        String url = base.startsWith("http") ? base : "https://" + base;
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        url += "/chat/completions";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(20000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        if (!token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) {
                sb.append(new String(buf, 0, len, StandardCharsets.UTF_8));
            }
            is.close();
        }
        if (responseOut != null) {
            responseOut.append(sb);
        }
        conn.disconnect();
        return code;
    }
}
