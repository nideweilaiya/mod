package com.aiworkbench.companion.skill;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.ai.OllamaClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * LLM 自我验证器 —— 调用 Ollama 判断技能执行是否成功。
 * <p>
 * Voyager 架构的核心环节："执行 → 反馈 → 验证"。
 * 将原始任务描述 + {@link FeedbackCollector.SkillFeedback} 发给 LLM，
 * LLM 返回结构化 JSON 判断任务是否真正完成。
 * <p>
 * 使用方式：
 * <pre>
 *   VerificationResult result = SkillVerifier.verify(taskDesc, feedback, "qwen3.5");
 *   if (result.success) { ... } else { // 根据 result.suggestion 修正 }
 * </pre>
 * thread-safety: 无共享可变状态，可安全多线程调用。
 */
public class SkillVerifier {

    private static final Gson GSON = new GsonBuilder().create();

    private static final String SYSTEM_PROMPT = """
你是一个 Minecraft 任务验证器。你的工作是判断 AI 同伴是否成功完成了主人分配的任务。

# 输入
你将收到：
1. 原始任务描述（主人下达的指令）
2. 技能执行反馈（包含执行状态、同伴状态、破坏的方块、获取的物品等）

# 判断标准
- 成功：任务目标已达成（例如要求挖铁矿石，反馈中显示获得了铁矿石）
- 失败：执行被中断、找不到目标、工具不足、背包满等
- 部分成功：部分达成目标（例如要求挖10个铁矿石只挖到3个）

# 输出格式
仅输出JSON，不要输出任何其他内容、不要思考过程、不要markdown：
{
  "success": true/false,
  "critique": "对执行情况的简短评价（中文，20字以内）",
  "suggestion": "如果失败，给出改进建议（中文，30字以内）；如果成功可为空字符串"
}

# 规则
1. 严格根据反馈中的客观数据判断，不要猜测
2. critique 要简洁有建设性
3. 如果失败原因是 PATH_BLOCKED 或 NO_TOOL，suggestion 要针对性地提出解决方案
""";

    // ================ 公开方法 ================

    /**
     * 同步验证技能执行结果。
     * 阻塞调用，适合在后台线程中使用。
     *
     * @param taskDescription 原始任务描述（主人下达的指令）
     * @param feedback        技能执行后的环境反馈
     * @param model           使用的 Ollama 模型名（如 "qwen3.5", "llama3.2"）
     * @return 验证结果，失败时 success 为 false 且 suggestion 包含重试建议
     */
    public static VerificationResult verify(String taskDescription,
                                            FeedbackCollector.SkillFeedback feedback,
                                            String model) {
        if (taskDescription == null || taskDescription.isBlank()) {
            return new VerificationResult(false, "无法验证：任务描述为空", "请提供有效的任务描述");
        }
        if (feedback == null) {
            return new VerificationResult(false, "无法验证：反馈数据为空", "技能执行可能未完成");
        }
        if (model == null || model.isBlank()) {
            model = "llama3.2:latest";
        }

        // 构建 LLM prompt
        String prompt = buildVerificationPrompt(taskDescription, feedback);
        String response = callLLM(prompt, model);

        if (response == null || response.isEmpty()) {
            AICompanionMod.LOGGER.warn("[SkillVerifier] LLM returned empty response, fallback to heuristic");
            return heuristicVerify(taskDescription, feedback);
        }

        // 解析 JSON 响应
        VerificationResult result = parseResponse(response);
        if (result == null) {
            AICompanionMod.LOGGER.warn("[SkillVerifier] Failed to parse LLM response, fallback to heuristic");
            return heuristicVerify(taskDescription, feedback);
        }

        AICompanionMod.LOGGER.info("[SkillVerifier] Verification for '{}': success={}, critique='{}'",
                feedback.skillName, result.success, result.critique);
        return result;
    }

    /**
     * 异步验证技能执行结果。
     * 非阻塞调用，返回 CompletableFuture。
     *
     * @param taskDescription 原始任务描述
     * @param feedback        技能执行反馈
     * @param model           Ollama 模型名
     * @return CompletableFuture，在后台线程中完成验证
     */
    public static CompletableFuture<VerificationResult> verifyAsync(
            String taskDescription,
            FeedbackCollector.SkillFeedback feedback,
            String model) {
        return CompletableFuture.supplyAsync(() ->
                verify(taskDescription, feedback, model));
    }

    // ================ LLM 调用 ================

    /**
     * 构建发送给 LLM 的验证 prompt。
     */
    private static String buildVerificationPrompt(String taskDescription,
                                                   FeedbackCollector.SkillFeedback feedback) {
        String feedbackCtx = FeedbackCollector.toPromptContext(feedback);
        return "原始任务: " + taskDescription + "\n\n"
                + feedbackCtx + "\n"
                + "请根据以上信息判断任务是否成功完成。输出JSON:";
    }

    private static String callLLM(String prompt, String model) {
        if (com.aiworkbench.companion.AICompanionMod.LLM_DISABLED) return null;
        java.util.LinkedHashMap<String, Object> opts = new java.util.LinkedHashMap<>();
        opts.put("temperature", OllamaClient.TEMP_PRECISE);
        opts.put("num_predict", 200);
        return com.aiworkbench.companion.ai.OllamaClient.chat(
            model, SYSTEM_PROMPT, prompt, opts, 45000, 2);
    }

    // ================ 响应解析 ================

    /**
     * 解析 LLM 返回的 JSON。
     * 支持纯 JSON 和 markdown 代码块包裹的 JSON。
     */
    @SuppressWarnings("unchecked")
    private static VerificationResult parseResponse(String response) {
        // 去掉 markdown 代码标记
        String json = response;
        if (json.contains("```")) {
            json = json.replaceAll("```json\\s*", "");
            json = json.replaceAll("```\\s*", "");
        }
        json = json.trim();

        // 尝试找到 JSON 对象的起始和结束
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        }

        try {
            Map<String, Object> map = GSON.fromJson(json, Map.class);
            if (map == null) return null;

            boolean success = map.get("success") instanceof Boolean b ? b : false;
            String critique = map.getOrDefault("critique", "").toString();
            String suggestion = map.getOrDefault("suggestion", "").toString();

            return new VerificationResult(success, critique, suggestion);
        } catch (Exception e) {
            AICompanionMod.LOGGER.warn("[SkillVerifier] JSON parse error: {}", e.getMessage());
            return null;
        }
    }

    // ================ 启发式 fallback（无需 LLM） ================

    /**
     * 启发式验证 —— 当 LLM 不可用时基于规则判断。
     * 检查反馈中的基础指标来推断是否成功。
     */
    private static VerificationResult heuristicVerify(String taskDescription,
                                                      FeedbackCollector.SkillFeedback feedback) {
        boolean success = feedback.isSuccess();
        String critique;
        String suggestion;

        if (success) {
            critique = "技能执行完成（启发式判断）";
            suggestion = "";
        } else {
            critique = "执行失败: " + feedback.failureReason.getDescription();
            suggestion = switch (feedback.failureReason) {
                case PATH_BLOCKED -> "尝试从不同方向接近目标";
                case NO_TOOL -> "先在背包中准备合适的工具";
                case INVENTORY_FULL -> "清理背包腾出空间";
                case ENTITY_GONE -> "目标已不在，尝试搜索附近替代目标";
                case INTERRUPTED -> "检查是否被危险环境打断";
                default -> "重新执行技能";
            };
        }

        return new VerificationResult(success, critique, suggestion);
    }

    // ================ 数据类型 ================

    /**
     * 验证结果。
     */
    public static class VerificationResult {
        /** 任务是否成功完成 */
        public final boolean success;
        /** LLM 对执行情况的评价 */
        public final String critique;
        /** 改进建议（失败时提供） */
        public final String suggestion;

        public VerificationResult(boolean success, String critique, String suggestion) {
            this.success = success;
            this.critique = critique != null ? critique : "";
            this.suggestion = suggestion != null ? suggestion : "";
        }

        /**
         * 是否包含了可操作的改进建议。
         */
        public boolean hasSuggestion() {
            return suggestion != null && !suggestion.isEmpty();
        }

        @Override
        public String toString() {
            return String.format("VerificationResult{success=%s, critique='%s', suggestion='%s'}",
                    success, critique, suggestion);
        }

        /**
         * 序列化为 JSON。
         */
        public String toJson() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("success", success);
            map.put("critique", critique);
            map.put("suggestion", suggestion);
            return GSON.toJson(map);
        }

        /**
         * 从 JSON 反序列化。
         */
        public static VerificationResult fromJson(String json) {
            try {
                Map<String, Object> map = GSON.fromJson(json, Map.class);
                boolean success = map.get("success") instanceof Boolean b ? b : false;
                String critique = (String) map.getOrDefault("critique", "");
                String suggestion = (String) map.getOrDefault("suggestion", "");
                return new VerificationResult(success, critique, suggestion);
            } catch (Exception e) {
                return new VerificationResult(false, "JSON 解析失败", e.getMessage());
            }
        }
    }
}
