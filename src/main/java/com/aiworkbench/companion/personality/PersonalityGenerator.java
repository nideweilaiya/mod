package com.aiworkbench.companion.personality;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.ai.OllamaClient;
import com.aiworkbench.companion.ai.OllamaClient;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 性格生成器 —— LLM 从玩家自然语言描述生成同伴性格档案。
 * <p>
 * 异步执行，不阻塞游戏主线程。生成完成后回调通知。
 */
public class PersonalityGenerator {

    private static final String SYSTEM_PROMPT = """
        你是一个角色性格分析器。根据玩家对Minecraft同伴的描述，提取性格参数。
        输出严格JSON格式，不要markdown包裹：
        {
          "curiosity": 0.0-1.0,   // 好奇心：是否爱探索
          "bravery": 0.0-1.0,     // 勇敢：是否敢战斗
          "sociability": 0.0-1.0, // 社交性：是否爱说话
          "carefulness": 0.0-1.0, // 细心：是否谨慎
          "speechStyle": "说话风格描述，15字以内",
          "appearance": "外貌描述，15字以内"
        }
        默认值0.5表示均衡。根据描述推断，不要全给0.5。""";

    /**
     * 异步生成性格档案。
     * @param player 玩家（用于获取模型配置）
     * @param description 玩家自然语言描述
     * @param callback 生成完成后的回调（在异步线程中调用，需自行切回主线程）
     */
    public static void generateAsync(ServerPlayer player, String description,
                                      Consumer<CompanionPersonality> callback) {
        String model = com.aiworkbench.companion.CompanionConfig.getModel(player.getUUID());

        CompletableFuture.runAsync(() -> {
            try {
                if (com.aiworkbench.companion.AICompanionMod.LLM_DISABLED) {
                    callback.accept(fallbackPersonality(description));
                    return;
                }
                LinkedHashMap<String, Object> opts = new LinkedHashMap<>();
                opts.put("temperature", OllamaClient.TEMP_BALANCED);
                opts.put("num_predict", 150);

                String response = OllamaClient.chat(model, SYSTEM_PROMPT, description, opts, 20000, 2);
                if (response == null || response.isEmpty()) {
                    callback.accept(fallbackPersonality(description));
                    return;
                }

                Map<String, Object> json = OllamaClient.extractJson(response);
                if (json == null) {
                    callback.accept(fallbackPersonality(description));
                    return;
                }

                CompanionPersonality p = new CompanionPersonality(
                    getFloat(json, "curiosity", 0.5f),
                    getFloat(json, "bravery", 0.5f),
                    getFloat(json, "sociability", 0.5f),
                    getFloat(json, "carefulness", 0.5f),
                    getString(json, "speechStyle", "友好自然"),
                    getString(json, "appearance", "")
                );
                p.generatedFrom = description;
                p.isGenerated = true;
                callback.accept(p);

                AICompanionMod.LOGGER.info("[PersonalityGen] Generated: {}", p);
            } catch (Exception e) {
                AICompanionMod.LOGGER.error("[PersonalityGen] Error: {}", e.getMessage());
                callback.accept(fallbackPersonality(description));
            }
        });
    }

    /** LLM不可用时的启发式fallback */
    static CompanionPersonality fallbackPersonality(String desc) {
        CompanionPersonality p = new CompanionPersonality();
        String d = desc.toLowerCase();
        if (d.contains("活泼") || d.contains("开朗") || d.contains("可爱")) {
            p.sociability = 0.8f; p.curiosity = 0.6f;
        }
        if (d.contains("勇敢") || d.contains("无畏") || d.contains("莽撞")) {
            p.bravery = 0.8f; p.carefulness = 0.2f;
        }
        if (d.contains("胆小") || d.contains("谨慎") || d.contains("小心")) {
            p.bravery = 0.2f; p.carefulness = 0.8f;
        }
        if (d.contains("沉默") || d.contains("安静") || d.contains("冷")) {
            p.sociability = 0.2f;
        }
        if (d.contains("细心") || d.contains("认真")) {
            p.carefulness = 0.8f;
        }
        if (d.contains("好奇") || d.contains("探索")) {
            p.curiosity = 0.8f;
        }
        p.speechStyle = d.length() > 15 ? d.substring(0, 15) : d;
        p.generatedFrom = desc;
        return p;
    }

    private static float getFloat(Map<String, Object> json, String key, float def) {
        Object v = json.get(key);
        if (v instanceof Number n) return Math.max(0f, Math.min(1f, n.floatValue()));
        return def;
    }

    private static String getString(Map<String, Object> json, String key, String def) {
        Object v = json.get(key);
        return v != null ? v.toString() : def;
    }
}
