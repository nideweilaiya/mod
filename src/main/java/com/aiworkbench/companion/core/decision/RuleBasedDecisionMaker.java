package com.aiworkbench.companion.core.decision;

import com.aiworkbench.companion.core.perception.PerceptionData;
import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * 基于效用评分的内置规则引擎（LLM 档次1：纯聊天）。
 *
 * <p>LLM 在此档次只负责生成对话文本，行为完全由此引擎驱动。
 * 每 tick 评估当前需求 → 计算效用分 → 选出最高分的动作。</p>
 *
 * <h3>效用分计算</h3>
 * <pre>
 *   utility = need_urgency × (1 / estimated_cost)
 *   need_urgency 是非线性的（如血量越低，逃逸需求指数增长）
 * </pre>
 *
 * <h3>阈值门控</h3>
 * 低于阈值的需求直接跳过（如血量 > 15 时不考虑逃跑）。
 */
public class RuleBasedDecisionMaker implements IDecisionMaker {

    // ---- 阈值 ----
    private static final float HEALTH_CRITICAL = 8.0f;
    private static final int HUNGER_LOW = 10;
    private static final int WOOD_LOW = 4;
    private static final double THREAT_RANGE = 8.0;

    // ---- 当前选中的目标（跨 tick 保持，防止决策摇摆） ----
    private String currentNeed = "idle";
    private BlockPos currentTarget;

    @Override
    public ActionDecision decide(PerceptionData perception, MemorySnapshot memory) {

        // ---- 需求评估（仅评估已授权的能力） ----
        Map<String, Double> utilities = new LinkedHashMap<>();
        Set<String> auth = memory.authorizedCapabilities;

        // 安全需求：始终评估（生存优先）
        double safetyUrgency = evaluateSafety(perception);
        if (safetyUrgency > 0) {
            utilities.put("safety", safetyUrgency / 10.0);
        }

        // 食物需求：始终评估
        double foodUrgency = evaluateFood(perception);
        if (foodUrgency > 0) {
            utilities.put("food", foodUrgency / 5.0);
        }

        // 木头需求：仅当 gather_logs 授权时评估
        if (auth.contains("gather_logs")) {
            double woodUrgency = evaluateWood(perception);
            if (woodUrgency > 0) {
                utilities.put("wood", woodUrgency / 5.0);
            }
        }

        // 矿石需求：仅当 gather_ores 授权时评估
        if (auth.contains("gather_ores")) {
            double oreUrgency = evaluateOre(perception);
            if (oreUrgency > 0) {
                utilities.put("ore", oreUrgency / 5.0);
            }
        }

        // 跟随主人（最低优先级，仅在无其他需求时生效）
        utilities.put("follow", 0.1);

        // ---- 选最高分 ----
        if (utilities.isEmpty()) {
            currentNeed = "idle";
            return ActionDecision.IDLE;
        }

        String best = Collections.max(utilities.entrySet(), Map.Entry.comparingByValue()).getKey();
        currentNeed = best;

        return mapToAction(perception, best);
    }

    // ==================== 需求评估 ====================

    /** 安全需求：非线性的威胁响应 */
    private double evaluateSafety(PerceptionData perception) {
        double urgency = 0;

        // 血量越低，逃逸需求指数增长
        float health = perception.self != null ? perception.self.health : 20;
        if (health < HEALTH_CRITICAL) {
            urgency += Math.pow(HEALTH_CRITICAL - health, 2); // 2²=4 at health=6, 6²=36 at health=2
        }

        // 附近有威胁
        if (perception.threats != null && !perception.threats.isEmpty()) {
            double nearestDist = perception.threats.get(0).distance();
            if (nearestDist < THREAT_RANGE) {
                urgency += 20.0 * (1.0 - nearestDist / THREAT_RANGE);
            }
        }

        return urgency;
    }

    /** 食物需求 */
    private double evaluateFood(PerceptionData perception) {
        int hunger = perception.self != null ? perception.self.hunger : 20;
        if (hunger > HUNGER_LOW) return 0;
        return Math.pow(HUNGER_LOW - hunger + 1, 2); // 非线性
    }

    /** 木头需求：背包不足 + 附近有树 */
    private double evaluateWood(PerceptionData perception) {
        int wood = 0;
        if (perception.inventorySummary != null) {
            for (var entry : perception.inventorySummary.entrySet()) {
                String name = entry.getKey();
                if (name.contains("_log") || name.contains("_stem")) {
                    wood += entry.getValue();
                }
            }
        }
        // 附近是否有树
        boolean treeNearby = false;
        if (perception.nearbyBlocks != null) {
            for (var b : perception.nearbyBlocks) {
                if (b.blockType().contains("_log") || b.blockType().contains("_stem")) {
                    treeNearby = true;
                    break;
                }
            }
        }
        if (!treeNearby) return 0;
        if (wood >= WOOD_LOW) return 2.0; // 有木头但附近有树 → 低急迫度
        return (WOOD_LOW - wood) * 10.0;
    }

    /** 矿石需求 */
    private double evaluateOre(PerceptionData perception) {
        if (perception.inventorySummary == null) return 0;
        int ore = 0;
        for (var entry : perception.inventorySummary.entrySet()) {
            String name = entry.getKey();
            if (name.contains("_ore") || name.contains("raw_")) {
                ore += entry.getValue();
            }
        }
        if (ore >= 1) return 0; // 至少有一种矿石
        // 附近有矿石 → 高急迫度
        if (perception.nearbyBlocks != null) {
            for (var b : perception.nearbyBlocks) {
                if (b.blockType().contains("_ore")) {
                    return 20.0; // 发现矿石，值得采集
                }
            }
        }
        return 0;
    }

    // ==================== 需求 → 动作映射 ====================

    private ActionDecision mapToAction(PerceptionData perception, String need) {
        return switch (need) {
            case "safety" -> handleSafety(perception);
            case "food" -> handleFood(perception);
            case "wood" -> handleWood(perception);
            case "ore" -> handleOre(perception);
            case "follow" -> handleFollow(perception);
            default -> ActionDecision.IDLE;
        };
    }

    private ActionDecision handleSafety(PerceptionData perception) {
        // 有威胁且距离近 → 装备武器
        if (perception.threats != null && !perception.threats.isEmpty()) {
            var nearest = perception.threats.get(0);
            if (nearest.distance() < 5.0) {
                return new ActionDecision("EquipItem",
                    Map.of("keyword", "sword"),
                    "threat nearby, equip weapon",
                    DecisionSource.RULE_ENGINE);
            }
        }
        // 血量低 → 逃跑（向远离威胁方向移动，此处简化为向主人方向）
        return new ActionDecision("MoveTo",
            Map.of("target", perception.self.position.offset(5, 0, 5)),
            "low health, retreat",
            DecisionSource.RULE_ENGINE);
    }

    private ActionDecision handleFood(PerceptionData perception) {
        // 找最近的可食用动物 → NavigateToInteract（走到动物旁边）
        if (perception.nearbyEntities != null) {
            for (var e : perception.nearbyEntities) {
                if (e.entityType().contains("cow") || e.entityType().contains("pig")
                    || e.entityType().contains("sheep") || e.entityType().contains("chicken")) {
                    currentTarget = e.pos();
                    return new ActionDecision("NavigateToInteract",
                        Map.of("target", e.pos()),
                        "approach food source: " + e.entityType(),
                        DecisionSource.RULE_ENGINE);
                }
            }
        }
        return ActionDecision.IDLE;
    }

    private ActionDecision handleWood(PerceptionData perception) {
        // 找最近的树 → EXECUTE_CAPABILITY: gather_logs
        if (perception.nearbyBlocks != null) {
            for (var b : perception.nearbyBlocks) {
                String name = b.blockType();
                if (name.contains("_log") || name.contains("_stem")) {
                    currentTarget = b.pos();
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("capability_id", "gather_logs");
                    params.put("$found_block.pos", b.pos());
                    // 传递 BFS 全树扫描结果（副本，避免执行层修改污染感知数据）
                    if (perception.treeCutList != null && !perception.treeCutList.isEmpty()) {
                        params.put("$tree_cut_list", new ArrayList<>(perception.treeCutList));
                    }
                    return new ActionDecision("EXECUTE_CAPABILITY", params,
                        "need wood → execute gather_logs on " + name,
                        DecisionSource.RULE_ENGINE);
                }
            }
        }
        return ActionDecision.IDLE;
    }

    private ActionDecision handleOre(PerceptionData perception) {
        // 找最近的矿石 → EXECUTE_CAPABILITY: gather_ores
        if (perception.nearbyBlocks != null) {
            for (var b : perception.nearbyBlocks) {
                String name = b.blockType();
                if (name.contains("_ore") || name.contains("deepslate_")) {
                    currentTarget = b.pos();
                    return new ActionDecision("EXECUTE_CAPABILITY",
                        Map.of("capability_id", "gather_ores",
                               "$found_block.pos", b.pos()),
                        "need ore → execute gather_ores on " + name,
                        DecisionSource.RULE_ENGINE);
                }
            }
        }
        return ActionDecision.IDLE;
    }

    private ActionDecision handleFollow(PerceptionData perception) {
        // 跟随：由 tickCoreFramework 的 idle fallback 处理
        // 此方法仅在评估层明确选中 "follow" 需求时被调用
        return ActionDecision.IDLE;
    }
}
