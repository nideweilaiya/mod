package com.aiworkbench.companion.skill;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.atomic.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM 技能生成器 —— 根据自然语言描述，调用本地 LLM 生成可执行的技能。
 * <p>
 * 使用 Ollama API 与 llama3.2 通信，解析返回的 JSON 并映射为 Skill 对象。
 * 生成的技能包含一系列原子操作，可存入技能库供后续使用。
 */
public class SkillGenerator {

    private static final String OLLAMA_BASE = "http://127.0.0.1:11434";
    private static final int TIMEOUT_MS = 60000;
    private static final int MAX_RETRIES = 2;
    private static final String MODEL = "llama3.2:latest";

    private static final Gson gson = new GsonBuilder().create();

    /**
     * 原子操作模板定义（用于 LLM prompt）
     */
    private static final String ACTION_TEMPLATES = """
1. break_blocks
   参数: block (字符串) - 方块ID关键词, 如 "log", "stone", "coal_ore"
   功能: 搜索最近匹配方块并逐个破坏收集
   示例: {"action": "break_blocks", "args": {"block": "log"}}

2. collect_items
   参数: 无
   功能: 收集周围所有掉落物

3. attack_hostile
   参数: 无
   功能: 攻击附近的敌对生物

4. attack_type
   参数: type (字符串) - 生物类型ID, 如 "zombie", "skeleton", "spider"
   功能: 攻击指定类型的生物

5. move_to_block
   参数: block (字符串) - 方块ID关键词
   功能: 移动到最近的匹配方块旁

6. equip_tool
   参数: block (字符串) - 要挖掘的方块关键词
   功能: 从背包装备最适合该方块的工具

7. craft
   参数: item (字符串) - 物品ID, 如 "stick", "wooden_pickaxe", "furnace"
   参数: count (整数, 可选, 默认1) - 合成次数
   功能: 从背包找材料合成物品

8. place_block
   参数: block (字符串) - 方块ID
   功能: 在目标位置放置方块
""";

    private static final String SYSTEM_PROMPT = """
你是一个 Minecraft AI 同伴技能生成器。
你将用户的自然语言任务描述转换为可执行的技能程序。

# 可用的原子操作
以下是你可以使用的原子操作：
""" + ACTION_TEMPLATES + """
# 输出格式
仅输出JSON，不要输出任何其他内容、不要思考过程、不要markdown：
{
  "name": "camelCaseSkillName",
  "description": "中文描述",
  "steps": [
    {"action": "action_name", "args": {"key": "value"}}
  ]
}

# 规则
1. 按逻辑顺序排列步骤
2. 如果玩家要求合成但缺材料，先合成材料再合成目标
3. 不需要的步骤可以省略
4. 名称用英文驼峰格式

# 任务
""";

    /**
     * 根据自然语言描述生成技能。
     *
     * @param playerName  玩家名称（用于日志）
     * @param description 自然语言任务描述
     * @return 生成的 Skill，失败返回 null
     */
    public static Skill generate(String playerName, String description) {
        AICompanionMod.LOGGER.info("[SkillGen] Generating skill for: {}", description);

        String prompt = SYSTEM_PROMPT + description + "\n\nJSON:";
        String response = callLLM(prompt);

        if (response == null || response.isEmpty()) {
            AICompanionMod.LOGGER.warn("[SkillGen] LLM returned empty response");
            return null;
        }

        // 解析 JSON
        SkillSpec spec = parseResponse(response);
        if (spec == null) {
            AICompanionMod.LOGGER.warn("[SkillGen] Failed to parse LLM response");
            return null;
        }

        // 映射为原子操作
        List<AtomicAction> actions = mapActions(spec.steps);
        if (actions.isEmpty()) {
            AICompanionMod.LOGGER.warn("[SkillGen] No valid actions generated");
            return null;
        }

        SkillAction skillAction = new SkillAction(actions, false);
        Skill skill = new Skill(
            spec.name,
            spec.description,
            List.of(),
            skillAction,
            SkillCategory.INTERACTION,
            false
        );

        AICompanionMod.LOGGER.info("[SkillGen] Generated skill '{}' with {} steps",
            spec.name, actions.size());
        return skill;
    }

    /**
     * 调用 Ollama LLM。
     */
    private static String callLLM(String prompt) {
        java.util.LinkedHashMap<String, Object> opts = new java.util.LinkedHashMap<>();
        opts.put("temperature", 0.1);
        opts.put("num_predict", 500);
        return com.aiworkbench.companion.ai.OllamaClient.chat(
            MODEL, SYSTEM_PROMPT, prompt, opts, TIMEOUT_MS, MAX_RETRIES);
    }

    /**
     * 从 Ollama 聊天响应中提取文本内容。
     */
    private static String extractContent(Map<String, Object> resp) {
        if (resp == null) return null;

        Object msg = resp.get("message");
        if (msg instanceof Map<?, ?> msgMap) {
            Object content = msgMap.get("content");
            if (content instanceof String s && !s.isEmpty()) {
                return s.trim();
            }
        }
        return null;
    }

    /**
     * 解析 LLM 返回的 JSON。
     * 支持纯 JSON 和 markdown 代码块包裹的 JSON。
     */
    private static SkillSpec parseResponse(String response) {
        // 去掉 markdown 代码标记
        String json = response;
        if (json.contains("```")) {
            json = json.replaceAll("```json\\s*", "");
            json = json.replaceAll("```\\s*", "");
        }
        json = json.trim();

        try {
            Map<String, Object> map = gson.fromJson(json, Map.class);
            if (map == null) return null;

            String name = (String) map.getOrDefault("name", "generatedSkill");
            String description = (String) map.getOrDefault("description", "");
            List<Map<String, Object>> steps = (List<Map<String, Object>>) map.get("steps");

            if (steps == null || steps.isEmpty()) return null;

            List<StepSpec> stepSpecs = new ArrayList<>();
            for (Map<String, Object> step : steps) {
                String action = (String) step.get("action");
                Map<String, Object> args = (Map<String, Object>) step.getOrDefault("args", Map.of());
                stepSpecs.add(new StepSpec(action, args));
            }

            return new SkillSpec(name, description, stepSpecs);
        } catch (Exception e) {
            AICompanionMod.LOGGER.warn("[SkillGen] JSON parse error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将 LLM 生成的步骤规范映射为实际的 AtomicAction 实例。
     */
    private static List<AtomicAction> mapActions(List<StepSpec> steps) {
        List<AtomicAction> actions = new ArrayList<>();

        for (StepSpec step : steps) {
            AtomicAction action = mapSingleAction(step);
            if (action != null) {
                actions.add(action);
            } else {
                AICompanionMod.LOGGER.warn("[SkillGen] Unknown action: {}", step.action);
            }
        }

        return actions;
    }

    /**
     * 将单个步骤规范映射为 AtomicAction。
     */
    private static AtomicAction mapSingleAction(StepSpec step) {
        return switch (step.action) {
            case "break_blocks", "break_block" -> {
                String block = getArg(step.args, "block");
                yield block != null ? new BreakBlockAction(BlockMatcher.contains(block)) : null;
            }
            case "collect_items", "collect" -> CollectItemsAction.all();
            case "attack_hostile", "attack" -> AttackEntityAction.allHostile();
            case "attack_type" -> {
                String type = getArg(step.args, "type");
                yield "zombie".equalsIgnoreCase(type) ? AttackEntityAction.ofType(Zombie.class) : null;
            }
            case "move_to_block", "move" -> {
                String block = getArg(step.args, "block");
                yield block != null ? new MoveToBlockAction(BlockMatcher.contains(block)) : null;
            }
            case "equip_tool", "equip" -> {
                String block = getArg(step.args, "block");
                yield block != null ? EquipItemAction.bestToolFor(block) : null;
            }
            case "craft" -> {
                String item = getArg(step.args, "item");
                int count = getIntArg(step.args, "count", 1);
                if (item == null) yield null;
                yield mapCraftItem(item, count);
            }
            case "place_block", "place" -> {
                // PlaceBlockAction 需要 BlockPos，动态技能不知道位置
                // 跳过放置类操作
                AICompanionMod.LOGGER.info("[SkillGen] Skipping place_block (needs dynamic position)");
                yield null;
            }
            default -> null;
        };
    }

    /**
     * 将物品名称字符串映射为 CraftItemAction。
     */
    private static AtomicAction mapCraftItem(String itemName, int count) {
        // 常见物品映射
        return switch (itemName.toLowerCase()) {
            case "stick" -> new CraftItemAction(Items.STICK, count);
            case "wooden_pickaxe", "wood_pickaxe", "木镐" ->
                new CraftItemAction(Items.WOODEN_PICKAXE, count);
            case "stone_pickaxe", "石镐" ->
                new CraftItemAction(Items.STONE_PICKAXE, count);
            case "iron_pickaxe", "铁镐" ->
                new CraftItemAction(Items.IRON_PICKAXE, count);
            case "furnace", "熔炉" ->
                new CraftItemAction(Items.FURNACE, count);
            case "crafting_table", "工作台" ->
                new CraftItemAction(Items.CRAFTING_TABLE, count);
            case "chest", "箱子" ->
                new CraftItemAction(Items.CHEST, count);
            case "torch", "火把" ->
                new CraftItemAction(Items.TORCH, count);
            default -> {
                AICompanionMod.LOGGER.warn("[SkillGen] Unknown item: {}", itemName);
                yield null;
            }
        };
    }

    private static String getArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString() : null;
    }

    private static int getIntArg(Map<String, Object> args, String key, int defaultVal) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultVal; }
        }
        return defaultVal;
    }

    // ==================== 内部数据结构 ====================

    /** LLM 返回的完整技能规范 */
    private record SkillSpec(String name, String description, List<StepSpec> steps) {}

    /** 单步操作规范 */
    private record StepSpec(String action, Map<String, Object> args) {}

    /**
     * 将生成的技能注册到技能库并返回技能名。
     */
    public static String registerGeneratedSkill(SkillLibrary library, Skill skill, AutomatonEntity entity) {
        if (skill == null) return null;

        // 确保名称唯一
        String baseName = skill.getName();
        String finalName = baseName;
        int suffix = 1;
        while (library.getPreset(finalName) != null) {
            finalName = baseName + suffix;
            suffix++;
        }

        // 注册为预制技能
        Skill registered = new Skill(
            finalName, skill.getDescription(), List.of(),
            skill.getAction(), skill.getCategory(), false
        );
        library.registerPreset(registered);

        // 自动学习
        UUID ownerUUID = entity.getOwnerUUID();
        if (ownerUUID != null) {
            library.learnSkill(ownerUUID, finalName);
        }

        AICompanionMod.LOGGER.info("[SkillGen] Registered generated skill '{}'", finalName);
        return finalName;
    }
}
