package com.aiworkbench.companion.ai;

import com.aiworkbench.companion.AICompanionMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/**
 * 统一 Ollama HTTP 客户端 — 替换所有分散的 HTTP 调用。
 * <p>
 * 单例模式，支持 chat 和 embed 两种 API，内置重试、超时、think标签清除。
 *
 * <pre>
 *   // Chat
 *   String reply = OllamaClient.chat("llama3.2:latest",
 *       "你是AI同伴。", "帮我挖铁矿", Map.of("temperature", 0.3));
 *
 *   // Embed
 *   List&lt;Double&gt; vec = OllamaClient.embed("nomic-embed-text", "挖掘铁矿");
 * </pre>
 */
public final class OllamaClient {
    private static final String BASE = "http://127.0.0.1:11434";
    private static final Gson GSON = new GsonBuilder().create();
    private static final int DEFAULT_TIMEOUT = 30000;
    private static final int DEFAULT_RETRIES = 2;
    // v1.0.1: 温度常量，集中管理避免散落各处
    public static final double TEMP_CREATIVE = 0.8;
    public static final double TEMP_BALANCED = 0.3;
    public static final double TEMP_PRECISE = 0.1;
    private static final java.util.regex.Pattern THINK_PATTERN =
        java.util.regex.Pattern.compile("<think>[\\s\\S]*?</think>", java.util.regex.Pattern.DOTALL);

    private OllamaClient() {}

    // ==================== Chat API ====================

    /**
     * 简单对话 — 单条 user 消息，可附带 system prompt。
     */
    public static String chat(String model, String systemPrompt, String userPrompt,
                              Map<String, Object> options) {
        return chat(model, systemPrompt, userPrompt, options, DEFAULT_TIMEOUT, DEFAULT_RETRIES);
    }

    /**
     * 简单对话 + 自定义超时和重试次数。
     */
    public static String chat(String model, String systemPrompt, String userPrompt,
                              Map<String, Object> options, int timeoutMs, int maxRetries) {
        List<Map<String, String>> msgs = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty())
            msgs.add(Map.of("role", "system", "content", systemPrompt));
        msgs.add(Map.of("role", "user", "content", userPrompt));
        return chat(model, msgs, options, timeoutMs, maxRetries);
    }

    /**
     * 多轮对话 — 完整的消息列表。
     */
    public static String chat(String model, List<Map<String, String>> messages,
                              Map<String, Object> options) {
        return chat(model, messages, options, DEFAULT_TIMEOUT, DEFAULT_RETRIES);
    }

    /**
     * 多轮对话 + 自定义超时和重试次数。
     */
    public static String chat(String model, List<Map<String, String>> messages,
                              Map<String, Object> options, int timeoutMs, int maxRetries) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);
        if (options != null) body.put("options", options);

        String json = GSON.toJson(body);

        for (int i = 0; i < maxRetries; i++) {
            try {
                HttpURLConnection conn = post(BASE + "/api/chat", json, timeoutMs);
                int code = conn.getResponseCode();
                if (code == 200) {
                    String resp = readAll(conn);
                    return extractContent(resp);
                }
                if (i < maxRetries - 1) sleep(500);
            } catch (Exception e) {
                if (i == maxRetries - 1)
                    AICompanionMod.LOGGER.warn("[OllamaClient] Chat failed: {}", e.getMessage());
                else sleep(500);
            }
        }
        return null;
    }

    // ==================== Embed API ====================

    /**
     * 生成文本的嵌入向量。
     * @return 浮点数列表，失败返回空列表
     */
    public static List<Double> embed(String model, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", text);

        String json = GSON.toJson(body);
        int timeout = 45000; // embedding 模型较慢

        for (int i = 0; i < DEFAULT_RETRIES; i++) {
            try {
                HttpURLConnection conn = post(BASE + "/api/embed", json, timeout);
                if (conn.getResponseCode() == 200) {
                    String resp = readAll(conn);
                    return extractEmbedding(resp);
                }
                if (i < DEFAULT_RETRIES - 1) sleep(500);
            } catch (Exception e) {
                if (i == DEFAULT_RETRIES - 1)
                    AICompanionMod.LOGGER.warn("[OllamaClient] Embed failed: {}", e.getMessage());
                else sleep(500);
            }
        }
        return Collections.emptyList();
    }

    // ==================== 响应解析 ====================

    /** 从 chat 响应中提取 message.content，并清除 think 标签 */
    static String extractContent(String json) {
        try {
            Map<String, Object> resp = GSON.fromJson(json,
                new TypeToken<Map<String, Object>>(){}.getType());
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = (Map<String, Object>) resp.get("message");
            if (msg == null) return null;
            String content = (String) msg.get("content");
            if (content == null || content.isEmpty()) return null;
            return THINK_PATTERN.matcher(content).replaceAll("").trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 embed 响应中提取 embedding[0] */
    static List<Double> extractEmbedding(String json) {
        try {
            Map<String, Object> resp = GSON.fromJson(json,
                new TypeToken<Map<String, Object>>(){}.getType());
            @SuppressWarnings("unchecked")
            List<List<Double>> embeddings = (List<List<Double>>) resp.get("embeddings");
            if (embeddings != null && !embeddings.isEmpty())
                return embeddings.get(0);
        } catch (Exception e) {
            AICompanionMod.LOGGER.warn("[OllamaClient] Bad embed response");
        }
        return Collections.emptyList();
    }

    /**
     * 从 LLM 响应中提取 JSON 对象。
     * 清除 think 标签后，找到第一个 { 和最后一个 }，解析为 Map。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> extractJson(String content) {
        if (content == null || content.isEmpty()) return Collections.emptyMap();
        content = THINK_PATTERN.matcher(content).replaceAll("").trim();
        int s = content.indexOf('{');
        int e = content.lastIndexOf('}');
        if (s < 0 || e < 0 || s >= e) return Collections.emptyMap();
        try {
            return GSON.fromJson(content.substring(s, e + 1),
                new TypeToken<Map<String, Object>>(){}.getType());
        } catch (Exception ex) {
            AICompanionMod.LOGGER.warn("[OllamaClient] extractJson failed: {}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    // ==================== HTTP 工具 ====================

    private static HttpURLConnection post(String url, String json, int timeoutMs) throws Exception {
        URL u = URI.create(url).toURL();
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        c.setDoOutput(true);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        c.getOutputStream().flush();
        c.getOutputStream().close();
        return c;
    }

    private static String readAll(HttpURLConnection conn) throws Exception {
        try (var is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
