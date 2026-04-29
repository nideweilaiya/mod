package com.aiworkbench.companion.ai;

import com.aiworkbench.companion.AICompanionMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.level.ServerPlayer;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * AI Brain for Companion NPC
 * Uses LLM to generate dialogue and behavior
 */
public class CompanionAI {
    private static final String OLLAMA_BASE = "http://127.0.0.1:11434";
    private static final int TIMEOUT_MS = 30000;
    private static final int MAX_RETRIES = 2;

    // Per-instance model (can be changed dynamically)
    private String model;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Gson gson = new GsonBuilder().create();

    // Context for this companion
    private final String companionId;
    private final String ownerName;
    private final List<ChatMessage> conversationHistory = new ArrayList<>();
    private final Map<String, Object> companionState = new HashMap<>();

    public CompanionAI(String companionId, String ownerName) {
        this.companionId = companionId;
        this.ownerName = ownerName;
        this.companionState.put("mood", "neutral");
        this.companionState.put("energy", 100);
        // Load model from config based on owner
        UUID ownerUUID = findOwnerUUID(ownerName);
        this.model = ownerUUID != null ?
            com.aiworkbench.companion.CompanionConfig.getModel(ownerUUID) :
            com.aiworkbench.companion.CompanionConfig.getDefaultModel();
    }

    private UUID findOwnerUUID(String ownerName) {
        if (com.aiworkbench.companion.AICompanionMod.server == null) return null;
        var player = com.aiworkbench.companion.AICompanionMod.server.getPlayerList().getPlayerByName(ownerName);
        return player != null ? player.getUUID() : null;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
        AICompanionMod.LOGGER.info("[CompanionAI] Model changed to: " + model);
    }

    /**
     * Send a chat message and get AI response
     */
    public CompletableFuture<String> sendMessage(String playerMessage) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Add player message to history
                conversationHistory.add(new ChatMessage("user", playerMessage));

                // Build context prompt
                String prompt = buildPrompt(playerMessage);

                // Call LLM
                String response = callLLM(prompt);

                // Add AI response to history
                conversationHistory.add(new ChatMessage("assistant", response));

                // Keep history manageable
                while (conversationHistory.size() > 20) {
                    conversationHistory.remove(0);
                }

                return response;
            } catch (Exception e) {
                AICompanionMod.LOGGER.error("AI error for companion " + companionId + ": " + e.getMessage());
                return "...";
            }
        }, executor);
    }

    /**
     * Generate a spontaneous action/emotion without player input
     */
    public CompletableFuture<String> generateSpontaneousAction() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String prompt = buildSpontaneousPrompt();
                return callLLM(prompt);
            } catch (Exception e) {
                AICompanionMod.LOGGER.error("AI spontaneous action error: " + e.getMessage());
                return null;
            }
        }, executor);
    }

    private String buildPrompt(String playerMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是《我的世界》中的同伴NPC，名叫小助手。\n");
        sb.append("你的主人是 ").append(ownerName).append("。\n");
        sb.append("当前状态：").append(companionState).append("\n");
        sb.append("\n对话历史：\n");
        for (ChatMessage msg : conversationHistory) {
            sb.append(msg.role).append(": ").append(msg.content).append("\n");
        }
        sb.append("user: ").append(playerMessage).append("\n");
        sb.append("assistant: ");
        return sb.toString();
    }

    private String buildSpontaneousPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是《我的世界》中的同伴NPC，名叫小助手。\n");
        sb.append("你的主人是 ").append(ownerName).append("。\n");
        sb.append("当前状态：").append(companionState).append("\n");
        sb.append("\n请用一句话描述你现在的感受或想做的事（5-15字）。直接输出文字，不要加引号或格式。\n");
        return sb.toString();
    }

    private String callLLM(String prompt) throws Exception {
        // Use chat API for better multi-turn conversation support
        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("stream", false);
        request.put("options", Map.of("temperature", 0.7, "num_predict", 200));

        // Build messages for chat API
        List<Map<String, String>> messages = new ArrayList<>();

        // System message
        messages.add(Map.of("role", "system", "content",
            "你是《我的世界》中的同伴NPC，名叫小助手。你的主人是 " + ownerName + "。请用简洁友好的中文回复，不要输出思考过程，直接回复内容。"));

        // Add conversation history
        for (ChatMessage msg : conversationHistory) {
            String role = msg.role.equals("user") ? "user" : "assistant";
            messages.add(Map.of("role", role, "content", msg.content));
        }

        // Add current message
        messages.add(Map.of("role", "user", "content", prompt));

        request.put("messages", messages);

        String json = gson.toJson(request);

        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                URL url = new URL(OLLAMA_BASE + "/api/chat");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        Map<String, Object> resp = gson.fromJson(response.toString(), Map.class);
                        return extractChatResponse(resp);
                    }
                } else {
                    AICompanionMod.LOGGER.warn("LLM returned code: " + code);
                }
            } catch (Exception e) {
                AICompanionMod.LOGGER.warn("LLM attempt " + (i+1) + " failed: " + e.getClass().getName() + ": " + e.getMessage());
            }
        }

        return "...";
    }

    private String extractChatResponse(Map<String, Object> resp) {
        String response = "";
        String thinking = "";

        AICompanionMod.LOGGER.info("[CompanionAI] Chat resp keys: " + resp.keySet());

        // Chat API returns message.content
        if (resp.containsKey("message")) {
            Object msg = resp.get("message");
            AICompanionMod.LOGGER.info("[CompanionAI] message type: " + (msg == null ? "null" : msg.getClass().getName()));
            if (msg instanceof Map) {
                Map<?, ?> msgMap = (Map<?, ?>) msg;
                AICompanionMod.LOGGER.info("[CompanionAI] message keys: " + msgMap.keySet());

                // Get content - qwen3.5 chat API has content in message.content
                Object content = msgMap.get("content");
                AICompanionMod.LOGGER.info("[CompanionAI] content type: " + (content == null ? "null" : content.getClass().getName()));
                if (content instanceof String) {
                    response = ((String) content).trim();
                    AICompanionMod.LOGGER.info("[CompanionAI] Got content from message.content, len=" + response.length());
                }

                // If content is "..." or empty, check thinking field (qwen3.5)
                if (response.isEmpty() || response.equals("...")) {
                    Object think = msgMap.get("thinking");
                    if (think instanceof String) {
                        thinking = ((String) think).trim();
                        AICompanionMod.LOGGER.info("[CompanionAI] thinking field length: " + thinking.length());
                        // Strip think tags:<think>...</think>  and <think>...</think>
                        thinking = thinking.replaceAll("<think>[\\s\\S]*?</think>", "").trim();
                        if (!thinking.isEmpty()) {
                            response = thinking;
                            AICompanionMod.LOGGER.info("[CompanionAI] Using thinking as response");
                        }
                    }
                }
            }
        }

        AICompanionMod.LOGGER.info("[CompanionAI] Chat response length: " + response.length());
        if (response.length() > 0) {
            AICompanionMod.LOGGER.info("[CompanionAI] Chat response preview: " + response.substring(0, Math.min(50, response.length())));
        } else {
            AICompanionMod.LOGGER.warn("[CompanionAI] Chat response is empty");
        }

        return response.isEmpty() ? "..." : response;
    }

    private String extractResponse(Map<String, Object> resp) {
        String response = "";
        String thinking = "";

        AICompanionMod.LOGGER.info("[CompanionAI] Raw resp keys: " + resp.keySet());

        // Get response field
        if (resp.containsKey("response")) {
            response = ((String) resp.get("response")).trim();
            AICompanionMod.LOGGER.info("[CompanionAI] response field length=" + response.length());
        }

        // Get thinking field (qwen3.5)
        if (resp.containsKey("thinking")) {
            thinking = (String) resp.get("thinking");
            if (thinking != null) {
                AICompanionMod.LOGGER.info("[CompanionAI] thinking field length=" + thinking.length());
            }
        }

        // Strip ALL think tags:<think>...</think> and <think>...</think>
        response = response.replaceAll("<think>[\\s\\S]*?</think>", "").replaceAll("<think>[\\s\\S]*?</think>", "").replaceAll("<think>.*", "").replaceAll("<think>.*", "").trim();
        thinking = thinking.replaceAll("<think>[\\s\\S]*?</think>", "").replaceAll("<think>[\\s\\S]*?</think>", "").replaceAll("<think>.*", "").replaceAll("<think>.*", "").trim();

        AICompanionMod.LOGGER.info("[CompanionAI] After strip, response length=" + response.length() + ", thinking length=" + thinking.length());

        // Prefer response field, but if it's empty or very short, use thinking
        if (response.length() < 3 && !thinking.isEmpty()) {
            response = thinking;
            AICompanionMod.LOGGER.info("[CompanionAI] Using thinking content as response");
        }

        AICompanionMod.LOGGER.info("[CompanionAI] Final response length: " + response.length());
        if (response.length() > 0) {
            AICompanionMod.LOGGER.info("[CompanionAI] Response preview: " + response.substring(0, Math.min(50, response.length())));
        } else {
            AICompanionMod.LOGGER.warn("[CompanionAI] Response is empty");
        }

        return response;
    }

    public void updateState(String key, Object value) {
        companionState.put(key, value);
    }

    public Map<String, Object> getState() {
        return new HashMap<>(companionState);
    }

    public String getCompanionId() {
        return companionId;
    }

    public List<ChatMessage> getHistory() {
        return new ArrayList<>(conversationHistory);
    }

    public void clearHistory() {
        conversationHistory.clear();
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    // ==================== Inner Classes ====================

    public static class ChatMessage {
        public String role;
        public String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}
