package com.aiworkbench.companion.memory;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.ai.OllamaClient;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 记忆摘要器 — 调用 LLM 将多轮对话压缩为记忆摘要块。
 * <p>
 * 每个摘要块 ≤ 200 tokens（约 280 个中文字符）。
 * 异步执行，不阻塞游戏主线程。
 */
public class MemorySummarizer {

    private static final String OLLAMA_BASE = "http://127.0.0.1:11434";
    private static final int MAX_SUMMARY_CHARS = 280;
    private static final int TIMEOUT_MS = 15000;

    private final Gson gson = new Gson();

    /**
     * 将对话日志摘要为简短文字。
     * @param conversations 最多 20 轮对话的文本
     * @return 摘要（≤ 280 字符），失败返回 null
     */
    public String summarize(String conversations) {
        if (conversations == null || conversations.isEmpty()) return null;

        String prompt = "请将以下 Minecraft 同伴与主人的对话总结成一段 100 字以内的摘要，"
            + "重点记录：主人做了什么、同伴帮了什么、发现了什么重要事物、主人的偏好或习惯。"
            + "仅输出摘要文字，不要加任何前缀或格式。\n\n"
            + conversations;

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", com.aiworkbench.companion.CompanionConfig.getDefaultModel());
            request.put("stream", false);
            request.put("options", Map.of("temperature", OllamaClient.TEMP_PRECISE, "num_predict", 80));

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content", prompt));
            request.put("messages", messages);

            String json = gson.toJson(request);
            URL url = new URL(OLLAMA_BASE + "/api/chat");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                }
                Map<String, Object> resp = gson.fromJson(response.toString(), Map.class);
                String summary = extractContent(resp);
                if (summary != null && summary.length() > MAX_SUMMARY_CHARS) {
                    summary = summary.substring(0, MAX_SUMMARY_CHARS);
                }
                return summary;
            }
        } catch (Exception e) {
            AICompanionMod.LOGGER.warn("[MemorySummarizer] LLM call failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 提取 chat API 的 content 字段。
     */
    private String extractContent(Map<String, Object> resp) {
        if (resp.containsKey("message")) {
            Object msg = resp.get("message");
            if (msg instanceof Map) {
                Object content = ((Map<?, ?>) msg).get("content");
                if (content instanceof String s && !s.isEmpty()) return s.trim();
            }
        }
        return null;
    }
}
