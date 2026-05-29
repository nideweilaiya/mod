package com.aiworkbench.companion.core.capability;

import com.aiworkbench.companion.AICompanionMod;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 能力注册中心 — 加载和索引 config/capabilities/*.json。
 *
 * <p>评估层通过 {@link #findByNeed} 查找满足需求的能力，
 * 执行层通过 {@link #get} 获取能力的原语序列。</p>
 *
 * <h3>加载顺序</h3>
 * <ol>
 *   <li>注册内置能力（硬编码兜底）</li>
 *   <li>扫描 config/capabilities/ 目录加载 JSON 定义</li>
 *   <li>JSON 定义覆盖同 ID 的内置能力</li>
 * </ol>
 */
public class CapabilityRegistry {

    private static final Gson GSON = new Gson();
    private static final Map<String, CapabilityDefinition> byId = new LinkedHashMap<>();
    private static final Map<String, List<CapabilityDefinition>> byNeed = new LinkedHashMap<>();
    private static boolean loaded = false;

    /** 注册内置能力（硬编码兜底，JSON 可覆盖） */
    private static void registerBuiltins() {
        // gather_logs (BFS全树扫描模式)
        {
            CapabilityDefinition def = new CapabilityDefinition();
            def.id = "gather_logs";
            def.name = "采集木材";
            def.need = "wood";
            def.block_keywords = List.of("_log", "_stem", "wood", "_hyphae");
            def.action_sequence = new ArrayList<>();
            def.action_sequence.add(new CapabilityDefinition.ActionStep("EquipItem",
                Map.of("keyword", "axe")));
            def.action_sequence.add(new CapabilityDefinition.ActionStep("NavigateToInteract",
                Map.of("target", "$found_block.pos")));
            // repeat: 列表驱动弹出下一个目标 → 走到 → 砍（内部排障） → 收集
            CapabilityDefinition.ActionStep repeat = new CapabilityDefinition.ActionStep();
            repeat.action = "repeat";
            repeat.condition = Map.of("list_not_empty", "$tree_cut_list");
            repeat.body = List.of(
                new CapabilityDefinition.ActionStep("SetNextTarget", Map.of()),
                new CapabilityDefinition.ActionStep("NavigateToInteract",
                    Map.of("target", "$current_target")),
                new CapabilityDefinition.ActionStep("BreakBlock",
                    Map.of("target", "$current_target")),
                new CapabilityDefinition.ActionStep("PickupItem",
                    Map.of("item_filter", "log"))
            );
            def.action_sequence.add(repeat);
            register(def);
        }
        // gather_ores
        {
            CapabilityDefinition def = new CapabilityDefinition();
            def.id = "gather_ores";
            def.name = "采集矿石";
            def.need = "ore";
            def.block_keywords = List.of("_ore", "deepslate_", "raw_");
            def.action_sequence = new ArrayList<>();
            def.action_sequence.add(new CapabilityDefinition.ActionStep("EquipItem",
                Map.of("keyword", "pickaxe")));
            def.action_sequence.add(new CapabilityDefinition.ActionStep("NavigateToInteract",
                Map.of("target", "$found_block.pos")));
            CapabilityDefinition.ActionStep repeat = new CapabilityDefinition.ActionStep();
            repeat.action = "repeat";
            repeat.condition = Map.of("block_matches", "_ore");
            repeat.body = List.of(
                new CapabilityDefinition.ActionStep("BreakBlock",
                    Map.of("target", "$current_target")),
                new CapabilityDefinition.ActionStep("PickupItem",
                    Map.of("item_filter", "")),
                new CapabilityDefinition.ActionStep("MoveUpTarget", Map.of())
            );
            def.action_sequence.add(repeat);
            register(def);
        }
    }

    /** 注册一个能力定义 */
    public static void register(CapabilityDefinition def) {
        byId.put(def.id, def);
        byNeed.computeIfAbsent(def.need, k -> new ArrayList<>()).add(def);
    }

    /** 按 ID 获取能力定义 */
    public static CapabilityDefinition get(String id) {
        ensureLoaded();
        return byId.get(id);
    }

    /** 按需求类型查找所有匹配的能力 */
    public static List<CapabilityDefinition> findByNeed(String need) {
        ensureLoaded();
        return byNeed.getOrDefault(need, Collections.emptyList());
    }

    /**
     * 在感知数据中匹配能力所需的方块。
     *
     * @param need 需求类型（wood, ore, ...）
     * @param blockTypes 感知数据中的方块类型名称列表
     * @return 第一个匹配的方块类型名称，或 null
     */
    public static String findMatchingBlockType(String need, List<String> blockTypes) {
        List<CapabilityDefinition> caps = findByNeed(need);
        if (caps.isEmpty() || blockTypes == null) return null;

        for (String blockType : blockTypes) {
            for (CapabilityDefinition cap : caps) {
                if (cap.block_keywords == null) continue;
                for (String keyword : cap.block_keywords) {
                    if (blockType.contains(keyword)) {
                        return blockType;
                    }
                }
            }
        }
        return null;
    }

    /** 获取所有已注册的能力 ID */
    public static Set<String> getAllIds() {
        ensureLoaded();
        return Collections.unmodifiableSet(byId.keySet());
    }

    /** 尝试从 config/capabilities/ 加载 JSON 文件 */
    public static void loadFromConfig(Path configDir) {
        Path capDir = configDir.resolve("capabilities");
        if (!Files.isDirectory(capDir)) {
            AICompanionMod.LOGGER.debug("[CapabilityRegistry] No capabilities dir at {}", capDir);
            return;
        }
        try (var files = Files.list(capDir)) {
            files.filter(f -> f.toString().endsWith(".json")).forEach(f -> {
                try (Reader r = Files.newBufferedReader(f)) {
                    CapabilityDefinition def = GSON.fromJson(r, CapabilityDefinition.class);
                    if (def != null && def.id != null) {
                        register(def);
                        AICompanionMod.LOGGER.info("[CapabilityRegistry] Loaded: {} from {}", def.id, f.getFileName());
                    }
                } catch (Exception e) {
                    AICompanionMod.LOGGER.warn("[CapabilityRegistry] Failed to load {}: {}", f.getFileName(), e.getMessage());
                }
            });
        } catch (Exception e) {
            AICompanionMod.LOGGER.warn("[CapabilityRegistry] Failed to scan capabilities dir: {}", e.getMessage());
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            registerBuiltins();
            loaded = true;
        }
    }
}
