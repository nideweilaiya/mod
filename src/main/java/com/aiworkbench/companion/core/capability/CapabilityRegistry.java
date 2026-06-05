package com.aiworkbench.companion.core.capability;

import com.aiworkbench.companion.AICompanionMod;
import com.google.gson.Gson;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Central capability registry.
 *
 * <p>Builtins are registered first as a safe fallback. JSON definitions from
 * {@code config/capabilities/*.json} are then loaded on top and override any
 * builtin with the same id. This order matters because the runtime tree
 * harvesting flow is driven by the loaded capability shape.</p>
 */
public class CapabilityRegistry {

    private static final Gson GSON = new Gson();
    private static final Map<String, CapabilityDefinition> byId = new LinkedHashMap<>();
    private static final Map<String, List<CapabilityDefinition>> byNeed = new LinkedHashMap<>();
    private static final Map<String, String> sourceById = new LinkedHashMap<>();
    private static boolean loaded = false;

    private CapabilityRegistry() {
    }

    private static void registerBuiltins() {
        register(createBuiltinGatherLogs(), "builtin");
        register(createBuiltinGatherOres(), "builtin");
    }

    private static CapabilityDefinition createBuiltinGatherLogs() {
        CapabilityDefinition def = new CapabilityDefinition();
        def.id = "gather_logs";
        def.name = "采集木材";
        def.need = "wood";
        def.block_keywords = List.of("_log", "_stem", "wood", "_hyphae");
        def.action_sequence = new ArrayList<>();
        def.action_sequence.add(new CapabilityDefinition.ActionStep("EquipItem", Map.of("keyword", "axe")));
        def.action_sequence.add(new CapabilityDefinition.ActionStep("NavigateToInteract", Map.of("target", "$found_block.pos")));

        CapabilityDefinition.ActionStep repeat = new CapabilityDefinition.ActionStep();
        repeat.action = "repeat";
        repeat.condition = Map.of("list_not_empty", "$tree_cut_list");
        repeat.body = List.of(
            new CapabilityDefinition.ActionStep("SetNextTarget", Map.of()),
            new CapabilityDefinition.ActionStep("NavigateToInteract", Map.of("target", "$current_target")),
            new CapabilityDefinition.ActionStep("EnsureReachBlock", Map.of("target", "$current_target")),
            new CapabilityDefinition.ActionStep("BreakBlock", Map.of("target", "$current_target"))
        );

        def.action_sequence.add(repeat);
        def.action_sequence.add(new CapabilityDefinition.ActionStep("CleanupTemporaryBlocks", Map.of()));
        def.action_sequence.add(new CapabilityDefinition.ActionStep(
            "PickupItem",
            Map.of("item_filter", "", "profile", "wood_harvest")
        ));
        return def;
    }

    private static CapabilityDefinition createBuiltinGatherOres() {
        CapabilityDefinition def = new CapabilityDefinition();
        def.id = "gather_ores";
        def.name = "采集矿石";
        def.need = "ore";
        def.block_keywords = List.of("_ore", "deepslate_", "raw_");
        def.action_sequence = new ArrayList<>();
        def.action_sequence.add(new CapabilityDefinition.ActionStep("EquipItem", Map.of("keyword", "pickaxe")));
        def.action_sequence.add(new CapabilityDefinition.ActionStep("NavigateToInteract", Map.of("target", "$found_block.pos")));

        CapabilityDefinition.ActionStep repeat = new CapabilityDefinition.ActionStep();
        repeat.action = "repeat";
        repeat.condition = Map.of("block_matches", "_ore");
        repeat.body = List.of(
            new CapabilityDefinition.ActionStep("BreakBlock", Map.of("target", "$current_target")),
            new CapabilityDefinition.ActionStep("PickupItem", Map.of("item_filter", "")),
            new CapabilityDefinition.ActionStep("MoveUpTarget", Map.of())
        );

        def.action_sequence.add(repeat);
        return def;
    }

    public static void register(CapabilityDefinition def) {
        register(def, "runtime");
    }

    public static void register(CapabilityDefinition def, String source) {
        if (def == null || def.id == null) {
            return;
        }

        CapabilityDefinition previous = byId.put(def.id, def);
        if (previous != null && previous.need != null) {
            List<CapabilityDefinition> previousBucket = byNeed.get(previous.need);
            if (previousBucket != null) {
                previousBucket.remove(previous);
                if (previousBucket.isEmpty()) {
                    byNeed.remove(previous.need);
                }
            }
        }

        sourceById.put(def.id, source);
        if (def.need != null) {
            byNeed.computeIfAbsent(def.need, key -> new ArrayList<>()).add(def);
        }
    }

    public static CapabilityDefinition get(String id) {
        ensureLoaded();
        return byId.get(id);
    }

    public static List<CapabilityDefinition> findByNeed(String need) {
        ensureLoaded();
        return byNeed.getOrDefault(need, Collections.emptyList());
    }

    public static String findMatchingBlockType(String need, List<String> blockTypes) {
        List<CapabilityDefinition> caps = findByNeed(need);
        if (caps.isEmpty() || blockTypes == null) {
            return null;
        }

        for (String blockType : blockTypes) {
            for (CapabilityDefinition cap : caps) {
                if (cap.block_keywords == null) {
                    continue;
                }
                for (String keyword : cap.block_keywords) {
                    if (blockType.contains(keyword)) {
                        return blockType;
                    }
                }
            }
        }
        return null;
    }

    public static Set<String> getAllIds() {
        ensureLoaded();
        return Collections.unmodifiableSet(byId.keySet());
    }

    public static String getSource(String id) {
        ensureLoaded();
        return sourceById.getOrDefault(id, "unknown");
    }

    public static String describe(String id) {
        ensureLoaded();
        CapabilityDefinition def = byId.get(id);
        if (def == null) {
            return id + " missing";
        }

        int preRepeat = 0;
        int repeatBody = 0;
        int postRepeat = 0;
        boolean afterRepeat = false;

        if (def.action_sequence != null) {
            for (CapabilityDefinition.ActionStep step : def.action_sequence) {
                if (step == null) {
                    continue;
                }
                if (step.isRepeat()) {
                    repeatBody = step.body != null ? step.body.size() : 0;
                    afterRepeat = true;
                    continue;
                }
                if (afterRepeat) {
                    postRepeat++;
                } else {
                    preRepeat++;
                }
            }
        }

        return String.format(
            "%s source=%s preRepeat=%d repeatBody=%d postRepeat=%d",
            id,
            getSource(id),
            preRepeat,
            repeatBody,
            postRepeat
        );
    }

    public static void loadFromConfig(Path configDir) {
        ensureLoaded();

        Path capDir = configDir.resolve("capabilities");
        if (!Files.isDirectory(capDir)) {
            AICompanionMod.LOGGER.debug("[CapabilityRegistry] No capabilities dir at {}", capDir);
            return;
        }

        try (var files = Files.list(capDir)) {
            files.filter(file -> file.toString().endsWith(".json")).forEach(file -> {
                try (Reader reader = Files.newBufferedReader(file)) {
                    CapabilityDefinition def = GSON.fromJson(reader, CapabilityDefinition.class);
                    if (def != null && def.id != null) {
                        register(def, file.getFileName().toString());
                        AICompanionMod.LOGGER.info(
                            "[CapabilityRegistry] Loaded {} from {} ({})",
                            def.id,
                            file.getFileName(),
                            describe(def.id)
                        );
                    }
                } catch (Exception e) {
                    AICompanionMod.LOGGER.warn(
                        "[CapabilityRegistry] Failed to load {}: {}",
                        file.getFileName(),
                        e.getMessage()
                    );
                }
            });
        } catch (Exception e) {
            AICompanionMod.LOGGER.warn("[CapabilityRegistry] Failed to scan capabilities dir: {}", e.getMessage());
        }
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }

        byId.clear();
        byNeed.clear();
        sourceById.clear();
        registerBuiltins();
        loaded = true;
    }
}
