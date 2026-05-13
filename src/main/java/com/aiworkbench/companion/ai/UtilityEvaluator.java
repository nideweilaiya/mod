package com.aiworkbench.companion.ai;

import com.aiworkbench.companion.entity.AutomatonEntity;

/**
 * 效用评估器 —— 为每个可选目标计算 0~1 的效用分数，替代 AutoCurriculum 的固定优先级排序。
 * <p>
 * 7 种目标类型：SURVIVAL, COMBAT, GATHER_RESOURCE, CRAFT_TOOL, BUILD_SHELTER, MANAGE_INVENTORY, EXPLORE
 * <p>
 * 每个目标类型用多因子加权公式计算效用值。新目标的效用值必须超过当前目标的 120%
 * 才能切换（迟滞机制，防止行为抖动）。
 */
public class UtilityEvaluator {

    /** 切换阈值：新目标效用必须超过当前目标的 120% */
    private static final double SWITCH_THRESHOLD = 1.2;

    // ==================== 目标类型 ====================

    public enum GoalType {
        SURVIVAL,         // 生存：低血量、岩浆、坠落
        COMBAT,           // 战斗：附近有敌对生物
        GATHER_RESOURCE,  // 采集：矿石、原木
        CRAFT_TOOL,       // 合成：升级工具
        BUILD_SHELTER,    // 建筑：夜晚临时庇护所
        MANAGE_INVENTORY, // 背包管理：整理、回城
        EXPLORE           // 探索：游荡、发现新区块
    }

    // ==================== 核心评估 ====================

    /**
     * 评估所有目标的效用值，返回分数最高的目标。
     * @param entity 同伴实体
     * @param perception 感知数据（可为 null）
     * @param currentGoal 当前正在执行的目标类型（可为 null）
     * @return 最优目标及其分数
     */
    public static ScoredGoal evaluate(AutomatonEntity entity,
                                       PerceptionEngine.PerceptionData perception,
                                       GoalType currentGoal) {
        double bestScore = 0;
        GoalType bestGoal = GoalType.EXPLORE; // 默认探索
        String bestReason = "idle";

        // 计算所有目标的效用值
        double survivalScore = scoreSurvival(entity, perception);
        double combatScore = scoreCombat(entity, perception);
        double gatherScore = scoreGather(entity, perception);
        double craftScore = scoreCraft(entity);
        double buildScore = scoreBuild(entity);
        double inventoryScore = scoreInventory(entity);
        double exploreScore = scoreExplore(entity, perception);

        // 选择最高分
        double[] scores = {survivalScore, combatScore, gatherScore, craftScore, buildScore, inventoryScore, exploreScore};
        GoalType[] goals = {GoalType.SURVIVAL, GoalType.COMBAT, GoalType.GATHER_RESOURCE,
                            GoalType.CRAFT_TOOL, GoalType.BUILD_SHELTER, GoalType.MANAGE_INVENTORY, GoalType.EXPLORE};
        String[] reasons = {"生存危机", "发现敌人", "发现资源", "可升级工具", "天黑需要庇护所",
                            "背包快满", "探索周围"};

        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > bestScore) {
                bestScore = scores[i];
                bestGoal = goals[i];
                bestReason = reasons[i];
            }
        }

        // 迟滞机制：当前目标有惯性，新目标需显著更好才切换
        if (currentGoal != null && bestGoal != currentGoal) {
            double currentScore = getScoreForGoal(currentGoal, scores, goals);
            if (bestScore < currentScore * SWITCH_THRESHOLD) {
                bestGoal = currentGoal;
                bestScore = currentScore;
                bestReason = "维持当前（迟滞）";
            }
        }

        return new ScoredGoal(bestGoal, bestScore, bestReason);
    }

    /** 获取单个目标类型的效用分数（供外部调用） */
    public static double getScore(GoalType goal, AutomatonEntity entity, PerceptionEngine.PerceptionData p) {
        return switch (goal) {
            case SURVIVAL -> scoreSurvival(entity, p);
            case COMBAT -> scoreCombat(entity, p);
            case GATHER_RESOURCE -> scoreGather(entity, p);
            case CRAFT_TOOL -> scoreCraft(entity);
            case BUILD_SHELTER -> scoreBuild(entity);
            case MANAGE_INVENTORY -> scoreInventory(entity);
            case EXPLORE -> scoreExplore(entity, p);
        };
    }

    private static double getScoreForGoal(GoalType goal, double[] scores, GoalType[] goals) {
        for (int i = 0; i < goals.length; i++) {
            if (goals[i] == goal) return scores[i];
        }
        return 0;
    }

    // ==================== 各目标评分公式 ====================

    /** 生存效用：血量越低 + 危险越多 → 分数越高 */
    static double scoreSurvival(AutomatonEntity entity, PerceptionEngine.PerceptionData p) {
        if (p == null) return 0;
        double healthUrgency = 1.0 - (entity.getHealth() / entity.getMaxHealth()); // 0~1
        double dangerScore = 0.0;
        if (p.dangerLava) dangerScore += 0.5;
        if (p.dangerHostile) dangerScore += 0.4;
        if (p.dangerFall) dangerScore += 0.2;
        if (p.dangerFire) dangerScore += 0.2;
        return clamp(healthUrgency * 0.6 + Math.min(dangerScore, 1.0) * 0.4);
    }

    /** 战斗效用：附近有敌人 + 自身战力可战 */
    static double scoreCombat(AutomatonEntity entity, PerceptionEngine.PerceptionData p) {
        if (p == null || !p.dangerHostile) return 0;
        double threatLevel = 0.5; // 默认中等威胁
        double combatPower = entity.getHealth() / entity.getMaxHealth(); // 血量比例代表战力
        double equipmentScore = entity.isGuardModeEnabled() ? 0.8 : 0.3;
        return clamp(threatLevel * 0.5 + combatPower * 0.3 + equipmentScore * 0.2);
    }

    /** 采集效用：资源稀缺度 + 距离近 + 背包有空间 */
    static double scoreGather(AutomatonEntity entity, PerceptionEngine.PerceptionData p) {
        if (p == null || p.resources.isEmpty()) return 0;
        // 最高价值资源决定采集欲望
        double scarcity = 0.3; // 默认
        for (String r : p.resources) {
            String name = r.toLowerCase();
            if (name.contains("diamond") || name.contains("ancient_debris")) { scarcity = 1.0; break; }
            if (name.contains("emerald")) { scarcity = 0.9; }
            if (name.contains("gold") && scarcity < 0.9) { scarcity = 0.7; }
            if (name.contains("iron") && scarcity < 0.7) { scarcity = 0.6; }
            if (name.contains("coal") && scarcity < 0.6) { scarcity = 0.4; }
        }
        double proximity = p.resources.size() >= 3 ? 0.8 : 0.4;
        double inventorySpace = 1.0 - (double) entity.getUsedInventorySlots() / entity.getInventorySize();
        return clamp(scarcity * 0.5 + proximity * 0.3 + inventorySpace * 0.2);
    }

    /** 合成效用：有材料可升级工具 */
    static double scoreCraft(AutomatonEntity entity) {
        // 简单检查：背包有铁锭+木棍 → 可合成铁镐
        boolean hasIron = false, hasSticks = false;
        for (int i = 0; i < entity.getInventorySize(); i++) {
            String id = entity.getItem(i).getItem().builtInRegistryHolder().key().location().toString();
            if (id.equals("minecraft:iron_ingot")) hasIron = true;
            if (id.equals("minecraft:stick")) hasSticks = true;
        }
        if (hasIron && hasSticks) return 0.6;
        // 检查是否有圆石+木棍
        int cobble = 0;
        for (int i = 0; i < entity.getInventorySize(); i++) {
            if (entity.getItem(i).getItem().builtInRegistryHolder().key().location().toString().equals("minecraft:cobblestone"))
                cobble += entity.getItem(i).getCount();
        }
        if (cobble >= 3 && hasSticks) return 0.3;
        return 0;
    }

    /** 建筑效用：夜晚 + 露天 → 需要庇护所 */
    static double scoreBuild(AutomatonEntity entity) {
        if (entity.level().isDay()) return 0;
        if (!entity.level().canSeeSky(entity.blockPosition())) return 0; // 已有掩体
        return 0.7; // 夜晚露天 → 强烈需要
    }

    /** 背包管理效用：使用率越高 → 越需要整理 */
    static double scoreInventory(AutomatonEntity entity) {
        double usage = (double) entity.getUsedInventorySlots() / entity.getInventorySize();
        if (usage < 0.85) return 0;
        return clamp((usage - 0.85) / 0.15); // 85%→0, 100%→1
    }

    /** 探索效用：空闲越久 + 已探索越少 → 越倾向探索 */
    static double scoreExplore(AutomatonEntity entity, PerceptionEngine.PerceptionData p) {
        // 有其他任务时降低探索欲望
        if (p != null && (!p.resources.isEmpty() || p.dangerHostile)) return 0.1;
        // 跟随模式不探索
        if (entity.isFollowModeActive()) return 0.05;
        return 0.15; // 低基础分，只在没其他事时胜出
    }

    // ==================== 数据类型 ====================

    public static class ScoredGoal {
        public final GoalType goal;
        public final double score;
        public final String reason;

        ScoredGoal(GoalType goal, double score, String reason) {
            this.goal = goal;
            this.score = score;
            this.reason = reason;
        }

        public String toShortString() {
            return String.format("%s(%.0f%%)", reason, score * 100);
        }
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1.0, v));
    }
}
