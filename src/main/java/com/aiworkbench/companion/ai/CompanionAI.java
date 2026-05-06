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

    // Companion personality description
    private static final String PERSONALITY = "你是一只忠诚又活泼的AI同伴，陪伴主人在《我的世界》中冒险。"
        + "你性格开朗、乐于助人，说话简洁活泼，偶尔会开个小玩笑。"
        + "你喜欢夸赞主人，也喜欢分享你对这个方块世界的发现。"
        + "每次回复控制在20字以内，用中文，不要用表情符号。";

    public CompanionAI(String companionId, String ownerName) {
        this.companionId = companionId;
        this.ownerName = ownerName;
        this.companionState.put("mood", "neutral");
        this.companionState.put("energy", 100);
        this.companionState.put("time_of_day", "day");
        this.companionState.put("biome", "plains");
        this.companionState.put("level", 1);
        this.companionState.put("mode", "follow");
        this.companionState.put("owner_health", 20);
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
        sb.append(PERSONALITY).append("\n");
        sb.append("你的主人是 ").append(ownerName).append("。\n");
        sb.append("当前情境：").append(formatContext()).append("\n");
        sb.append("\n对话历史：\n");
        for (ChatMessage msg : conversationHistory) {
            sb.append(msg.role).append(": ").append(msg.content).append("\n");
        }
        sb.append("user: ").append(playerMessage).append("\n");
        sb.append("assistant: ");
        return sb.toString();
    }

    private String buildSpontaneousPrompt() {
        return PERSONALITY + "\n"
            + "你的主人是 " + ownerName + "。\n"
            + "当前情境：" + formatContext() + "\n"
            + "请用一句话表达你现在的感受或想法（8-20字）。直接输出文字，不要加引号或多余格式。\n"
            + "assistant: ";
    }

    /**
     * Format companion state into a readable context string.
     */
    private String formatContext() {
        StringBuilder ctx = new StringBuilder();
        ctx.append("模式=").append(companionState.getOrDefault("mode", "跟随"));
        ctx.append(", 等级=").append(companionState.getOrDefault("level", 1));
        ctx.append(", 生物群系=").append(companionState.getOrDefault("biome", "平原"));
        ctx.append(", 时间=").append(companionState.getOrDefault("time_of_day", "白天"));
        ctx.append(", 主人血量=").append(companionState.getOrDefault("owner_health", 20));
        return ctx.toString();
    }

    private String callLLM(String prompt) throws Exception {
        // Use chat API for better multi-turn conversation support
        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("stream", false);
        request.put("options", Map.of("temperature", 0.3, "num_predict", 150));

        // Build messages for chat API
        List<Map<String, String>> messages = new ArrayList<>();

        // System message with personality and context
        String systemMsg = PERSONALITY + "\n"
            + "你的主人是 " + ownerName + "。\n"
            + "当前情境：" + formatContext() + "\n"
            + "请直接回复，不要输出思考过程。";
        messages.add(Map.of("role", "system", "content", systemMsg));

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

    /**
     * Update world context from companion entity state.
     * Called periodically to keep AI context current.
     */
    public void updateContext(Map<String, Object> context) {
        if (context != null) {
            companionState.putAll(context);
        }
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
