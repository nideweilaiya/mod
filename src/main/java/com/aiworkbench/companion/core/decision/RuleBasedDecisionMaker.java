package com.aiworkbench.companion.core.decision;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.core.action.TreeHarvestCursor;
import com.aiworkbench.companion.core.policy.ItemPickupPolicy;
import com.aiworkbench.companion.core.perception.PerceptionData;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rule-based decision maker for the new framework.
 */
public class RuleBasedDecisionMaker implements IDecisionMaker {

    private static final float HEALTH_CRITICAL = 8.0f;
    private static final int HUNGER_LOW = 10;
    private static final int WOOD_LOW = 4;
    private static final double THREAT_RANGE = 8.0;
    private static final double PICKUP_SCAN_RANGE = 8.0;
    private static final double PICKUP_CLUSTER_RANGE = 12.0;
    private static final double PICKUP_IMMEDIATE_RANGE = 3.0;
    private static final double PICKUP_WORKING_MODE_RANGE = 2.0;
    private static final double PICKUP_IDLE_FALLBACK_RANGE = 6.0;
    private static final int PICKUP_CLUSTER_THRESHOLD = 3;
    private static final long PICKUP_IDLE_FALLBACK_DELAY_MS = 5_000L;

    private String currentNeed = "idle";
    private BlockPos currentTarget;
    private boolean memoryInventoryFullLatched;
    private boolean proactiveWoodSeekEnabled;
    private boolean capabilityFocusedModeActive;
    private long firstLowSignalPickupSeenAtMs = -1L;

    @Override
    public ActionDecision decide(PerceptionData perception, MemorySnapshot memory) {
        memoryInventoryFullLatched = memory != null && memory.inventoryFullLatched;
        Map<String, Double> utilities = new LinkedHashMap<>();
        Set<String> auth = memory.authorizedCapabilities;
        boolean capabilityFocusedMode = !auth.isEmpty();
        capabilityFocusedModeActive = capabilityFocusedMode;
        proactiveWoodSeekEnabled = auth.contains("gather_logs");

        double safetyUrgency = evaluateSafety(perception);
        if (safetyUrgency > 0) {
            utilities.put("safety", safetyUrgency / 10.0);
        }

        // Explicit modes like chop/mine should focus on their authorized capability
        // instead of being hijacked by unrelated needs that do not dispatch actions.
        if (!capabilityFocusedMode) {
            double foodUrgency = evaluateFood(perception);
            if (foodUrgency > 0) {
                utilities.put("food", foodUrgency / 5.0);
            }
        }

        if (auth.isEmpty() || auth.contains("gather_logs")) {
            double woodUrgency = evaluateWood(perception);
            if (woodUrgency > 0) {
                utilities.put("wood", woodUrgency / 5.0);
            }
        }

        if (auth.contains("gather_ores")) {
            double oreUrgency = evaluateOre(perception);
            if (oreUrgency > 0) {
                utilities.put("ore", oreUrgency / 5.0);
            }
        }

        double pickupUrgency = evaluatePickup(perception, capabilityFocusedMode);
        if (pickupUrgency > 0) {
            utilities.put("pickup", pickupUrgency / 10.0);
        }

        if (!capabilityFocusedMode) {
            utilities.put("follow", 0.1);
        }

        if (utilities.isEmpty()) {
            currentNeed = "idle";
            AICompanionMod.LOGGER.info("[RuleBasedDecisionMaker] utilities empty -> idle auth={}", auth);
            return ActionDecision.IDLE;
        }

        String best = Collections.max(utilities.entrySet(), Map.Entry.comparingByValue()).getKey();
        currentNeed = best;
        AICompanionMod.LOGGER.info("[RuleBasedDecisionMaker] utilities={} best={}", utilities, best);

        return mapToAction(perception, best);
    }

    private double evaluateSafety(PerceptionData perception) {
        double urgency = 0;
        float health = perception.self != null ? perception.self.health : 20;
        if (health < HEALTH_CRITICAL) {
            urgency += Math.pow(HEALTH_CRITICAL - health, 2);
        }

        if (perception.threats != null && !perception.threats.isEmpty()) {
            double nearestDist = perception.threats.get(0).distance();
            if (nearestDist < THREAT_RANGE) {
                urgency += 20.0 * (1.0 - nearestDist / THREAT_RANGE);
            }
        }
        return urgency;
    }

    private double evaluateFood(PerceptionData perception) {
        int hunger = perception.self != null ? perception.self.hunger : 20;
        if (hunger > HUNGER_LOW) {
            return 0;
        }
        return Math.pow(HUNGER_LOW - hunger + 1, 2);
    }

    private double evaluateWood(PerceptionData perception) {
        if (memoryInventoryFullLatched) {
            return 0;
        }
        int wood = 0;
        if (perception.inventorySummary != null) {
            for (var entry : perception.inventorySummary.entrySet()) {
                String name = entry.getKey();
                if (name.contains("_log") || name.contains("_stem")) {
                    wood += entry.getValue();
                }
            }
        }

        boolean treeNearby = false;
        if (perception.nearbyBlocks != null) {
            for (var block : perception.nearbyBlocks) {
                if (block.blockType().contains("_log") || block.blockType().contains("_stem")) {
                    treeNearby = true;
                    break;
                }
            }
        }

        if (!treeNearby && proactiveWoodSeekEnabled && perception.treeAnchorHint != null) {
            treeNearby = true;
        }

        if (!treeNearby) {
            return 0;
        }
        if (wood >= WOOD_LOW) {
            return 2.0;
        }
        return (WOOD_LOW - wood) * 10.0;
    }

    private double evaluateOre(PerceptionData perception) {
        if (perception.inventorySummary == null) {
            return 0;
        }

        int ore = 0;
        for (var entry : perception.inventorySummary.entrySet()) {
            String name = entry.getKey();
            if (name.contains("_ore") || name.contains("raw_")) {
                ore += entry.getValue();
            }
        }
        if (ore >= 1) {
            return 0;
        }

        if (perception.nearbyBlocks != null) {
            for (var block : perception.nearbyBlocks) {
                if (block.blockType().contains("_ore")) {
                    return 20.0;
                }
            }
        }
        return 0;
    }

    private double evaluatePickup(PerceptionData perception, boolean capabilityFocusedMode) {
        if (memoryInventoryFullLatched) {
            firstLowSignalPickupSeenAtMs = -1L;
            return 0;
        }
        if (perception.nearbyItems == null || perception.nearbyItems.isEmpty()) {
            firstLowSignalPickupSeenAtMs = -1L;
            return 0;
        }

        int validItems = 0;
        boolean hasHighValue = false;
        double nearestValid = Double.MAX_VALUE;
        long now = perception.scanTimestamp > 0 ? perception.scanTimestamp : System.currentTimeMillis();
        for (var item : perception.nearbyItems) {
            if (item.distance() > PICKUP_CLUSTER_RANGE) {
                continue;
            }
            if (!ItemPickupPolicy.shouldPickup(
                item.itemType(),
                item.count(),
                perception.inventorySummary,
                ItemPickupPolicy.PROFILE_DEFAULT
            )) {
                continue;
            }
            validItems += Math.max(1, item.count());
            nearestValid = Math.min(nearestValid, item.distance());
            if (ItemPickupPolicy.isHighValue(item.itemType(), ItemPickupPolicy.PROFILE_DEFAULT)) {
                hasHighValue = true;
            }
        }

        if (validItems <= 0 || nearestValid > PICKUP_SCAN_RANGE) {
            firstLowSignalPickupSeenAtMs = -1L;
            return 0;
        }
        if (capabilityFocusedMode && nearestValid > PICKUP_WORKING_MODE_RANGE) {
            firstLowSignalPickupSeenAtMs = -1L;
            return 0;
        }
        if (hasHighValue || nearestValid <= PICKUP_IMMEDIATE_RANGE) {
            firstLowSignalPickupSeenAtMs = -1L;
            return 6.0;
        }
        if (validItems >= PICKUP_CLUSTER_THRESHOLD) {
            firstLowSignalPickupSeenAtMs = -1L;
            return 3.5;
        }
        if (!capabilityFocusedMode && nearestValid <= PICKUP_IDLE_FALLBACK_RANGE) {
            if (firstLowSignalPickupSeenAtMs < 0L) {
                firstLowSignalPickupSeenAtMs = now;
            } else if (now - firstLowSignalPickupSeenAtMs >= PICKUP_IDLE_FALLBACK_DELAY_MS) {
                return 1.5;
            }
        } else {
            firstLowSignalPickupSeenAtMs = -1L;
        }
        return 0;
    }

    private ActionDecision mapToAction(PerceptionData perception, String need) {
        return switch (need) {
            case "safety" -> handleSafety(perception);
            case "food" -> handleFood(perception);
            case "wood" -> handleWood(perception);
            case "ore" -> handleOre(perception);
            case "pickup" -> handlePickup(perception);
            case "follow" -> handleFollow(perception);
            default -> ActionDecision.IDLE;
        };
    }

    private ActionDecision handleSafety(PerceptionData perception) {
        if (perception.threats != null && !perception.threats.isEmpty()) {
            var nearest = perception.threats.get(0);
            if (nearest.distance() < 5.0) {
                return new ActionDecision(
                    "EquipItem",
                    Map.of("keyword", "sword"),
                    "threat nearby, equip weapon",
                    DecisionSource.RULE_ENGINE
                );
            }
        }

        return new ActionDecision(
            "MoveTo",
            Map.of("target", perception.self.position.offset(5, 0, 5)),
            "low health, retreat",
            DecisionSource.RULE_ENGINE
        );
    }

    private ActionDecision handleFood(PerceptionData perception) {
        if (perception.nearbyEntities != null) {
            for (var entity : perception.nearbyEntities) {
                if (entity.entityType().contains("cow")
                    || entity.entityType().contains("pig")
                    || entity.entityType().contains("sheep")
                    || entity.entityType().contains("chicken")) {
                    currentTarget = entity.pos();
                    return new ActionDecision(
                        "NavigateToInteract",
                        Map.of("target", entity.pos()),
                        "approach food source: " + entity.entityType(),
                        DecisionSource.RULE_ENGINE
                    );
                }
            }
        }
        return ActionDecision.IDLE;
    }

    private ActionDecision handleWood(PerceptionData perception) {
        TreeHarvestCursor cursor = perception.treeHarvestCursor;
        if (cursor == null && perception.treeStructure != null && !perception.treeStructure.isEmpty()) {
            cursor = new TreeHarvestCursor(perception.treeStructure);
        }
        if (cursor != null && cursor.treeStructure() != null && !cursor.treeStructure().isEmpty()) {
            List<BlockPos> remainingTargets = perception.treeCutList != null
                ? new ArrayList<>(perception.treeCutList)
                : cursor.remainingTargets();
            if (remainingTargets.isEmpty() && perception.treeStructure != null && !perception.treeStructure.isEmpty()) {
                cursor = new TreeHarvestCursor(perception.treeStructure);
                remainingTargets = cursor.remainingTargets();
            }
            if (!remainingTargets.isEmpty()) {
                BlockPos target = perception.treeStructure != null
                    ? perception.treeStructure.treeAnchor()
                    : cursor.treeStructure().treeAnchor();
                if (target == null) {
                    target = remainingTargets.get(0);
                }
                BlockPos approachTarget = perception.treeAnchorHint != null
                    ? perception.treeAnchorHint
                    : target;
                currentTarget = target;
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("capability_id", "gather_logs");
                params.put("$found_block.pos", approachTarget);
                params.put("$tree_cut_list", remainingTargets);
                params.put("$tree_harvest_cursor", cursor);
                params.put("$tree_structure", cursor.treeStructure());
                params.put("$tree_anchor", target);
                return new ActionDecision(
                    "EXECUTE_CAPABILITY",
                    params,
                    "need wood -> execute gather_logs on structured tree",
                    DecisionSource.RULE_ENGINE
                );
            }
        }

        if (proactiveWoodSeekEnabled && perception.treeAnchorHint != null) {
            currentTarget = perception.treeAnchorHint;
            return new ActionDecision(
                "MoveTo",
                Map.of("target", perception.treeAnchorHint),
                "need wood -> approach distant tree staging point",
                DecisionSource.RULE_ENGINE
            );
        }

        // Fallback: when full-tree scan is unavailable, still provide a singleton cut list
        // so capability repeat won't collapse into "list empty/invalid" no-op loops.
        if (perception.nearbyBlocks != null) {
            for (var block : perception.nearbyBlocks) {
                String name = block.blockType();
                if (name.contains("_log") || name.contains("_stem")) {
                    currentTarget = block.pos();
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("capability_id", "gather_logs");
                    params.put("$found_block.pos", block.pos());
                    params.put("$tree_cut_list", new ArrayList<>(java.util.List.of(block.pos())));
                    params.put("$tree_anchor", block.pos());
                    return new ActionDecision(
                        "EXECUTE_CAPABILITY",
                        params,
                        "need wood -> execute gather_logs with fallback singleton list on " + name,
                        DecisionSource.RULE_ENGINE
                    );
                }
            }
        }
        return ActionDecision.IDLE;
    }

    private ActionDecision handleOre(PerceptionData perception) {
        if (perception.nearbyBlocks != null) {
            for (var block : perception.nearbyBlocks) {
                String name = block.blockType();
                if (name.contains("_ore") || name.contains("deepslate_")) {
                    currentTarget = block.pos();
                    return new ActionDecision(
                        "EXECUTE_CAPABILITY",
                        Map.of("capability_id", "gather_ores", "$found_block.pos", block.pos()),
                        "need ore -> execute gather_ores on " + name,
                        DecisionSource.RULE_ENGINE
                    );
                }
            }
        }
        return ActionDecision.IDLE;
    }

    private ActionDecision handlePickup(PerceptionData perception) {
        if (perception.nearbyItems == null || perception.nearbyItems.isEmpty()) {
            return ActionDecision.IDLE;
        }

        PerceptionData.NearbyItem nearest = null;
        for (var item : perception.nearbyItems) {
            if (item.distance() > PICKUP_SCAN_RANGE) {
                continue;
            }
            if (capabilityFocusedModeActive && item.distance() > PICKUP_WORKING_MODE_RANGE) {
                continue;
            }
            if (!ItemPickupPolicy.shouldPickup(
                item.itemType(),
                item.count(),
                perception.inventorySummary,
                ItemPickupPolicy.PROFILE_DEFAULT
            )) {
                continue;
            }
            nearest = item;
            break;
        }
        if (nearest == null) {
            return ActionDecision.IDLE;
        }

        return new ActionDecision(
            "PickupItem",
            Map.of(),
            "nearby drop detected: " + nearest.itemType() + " x" + nearest.count(),
            DecisionSource.RULE_ENGINE
        );
    }

    private ActionDecision handleFollow(PerceptionData perception) {
        return ActionDecision.IDLE;
    }
}
