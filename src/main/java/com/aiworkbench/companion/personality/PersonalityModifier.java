package com.aiworkbench.companion.personality;

import com.aiworkbench.companion.ai.UtilityEvaluator.GoalType;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.manager.CompanionRole;

/**
 * 性格×角色行为调制器 —— 3 层决策架构。
 * <p>
 * Layer 0: 硬性安全门控（性格不可覆盖）
 * Layer 1: 场景评估（基于游戏状态，性格不参与）
 * Layer 2: 性格×角色调制（仅在非危机决策中生效）
 */
public class PersonalityModifier {

    // ==================== Layer 0：硬性安全门控 ====================

    /**
     * 检查是否存在硬性安全约束，强制覆盖所有性格/角色偏好。
     * @return 强制目标类型，null 表示无安全约束
     */
    public static GoalType checkSafetyOverrides(AutomatonEntity entity) {
        float healthRatio = entity.getHealth() / entity.getMaxHealth();

        // 濒死：强制逃跑/治疗
        if (healthRatio < 0.2f) {
            return GoalType.SURVIVAL;
        }
        // 主人濒死：强制守护
        var owner = entity.getOwner();
        if (owner != null && owner.getHealth() / owner.getMaxHealth() < 0.15f) {
            return GoalType.SURVIVAL;
        }
        return null; // 无安全约束
    }

    // ==================== Layer 2：性格×角色调制 ====================

    /**
     * 根据性格和角色调制目标类型的效用分数。
     * 性格只影响"中性"决策，不影响硬性安全门控已覆盖的目标。
     */
    public static double modulate(double baseScore, GoalType goal,
                                   CompanionPersonality p, CompanionRole role) {
        double multiplier = 1.0;

        switch (goal) {
            case EXPLORE ->
                multiplier = traitFactor(p.curiosity, 2.0f) * traitFactor(p.bravery, 1.5f)
                    * roleFactor(role, GoalType.EXPLORE);
            case GATHER_RESOURCE ->
                multiplier = traitFactor(p.curiosity, 1.5f) * traitFactor(p.carefulness, 0.5f)
                    * roleFactor(role, GoalType.GATHER_RESOURCE);
            case COMBAT ->
                multiplier = traitFactor(p.bravery, 2.5f)
                    * roleFactor(role, GoalType.COMBAT);
            case SURVIVAL ->
                multiplier = traitFactor(p.carefulness, 2.0f)
                    * roleFactor(role, GoalType.SURVIVAL);
            case BUILD_SHELTER ->
                multiplier = traitFactor(p.carefulness, 2.0f)
                    * roleFactor(role, GoalType.BUILD_SHELTER);
            case MANAGE_INVENTORY ->
                multiplier = traitFactor(p.carefulness, 2.0f)
                    * roleFactor(role, GoalType.MANAGE_INVENTORY);
            case CRAFT_TOOL ->
                multiplier = traitFactor(p.carefulness, 1.5f) * traitFactor(p.curiosity, 0.5f)
                    * roleFactor(role, GoalType.CRAFT_TOOL);
        }
        return baseScore * multiplier;
    }

    /** 性格因子：trait × weight → 调制系数 */
    private static double traitFactor(float trait, float weight) {
        // trait=0.5 → factor=1.0（均衡不变）
        // trait=0.0 → factor=1 - 0.5*weight
        // trait=1.0 → factor=1 + 0.5*weight
        return 1.0 + (trait - 0.5) * weight;
    }

    /** 角色因子：特定角色对特定目标有偏好 */
    private static double roleFactor(CompanionRole role, GoalType goal) {
        if (role == null) return 1.0;
        return switch (role) {
            case MINER -> switch (goal) {
                case GATHER_RESOURCE -> 2.0;
                case EXPLORE -> 1.5;
                case COMBAT -> 0.5;
                default -> 1.0;
            };
            case GUARD -> switch (goal) {
                case COMBAT -> 2.5;
                case SURVIVAL -> 1.5;
                case GATHER_RESOURCE -> 0.3;
                default -> 1.0;
            };
            case FARMER -> switch (goal) {
                case GATHER_RESOURCE -> 0.5;
                case MANAGE_INVENTORY -> 1.5;
                default -> 1.0;
            };
            case BUILDER -> switch (goal) {
                case BUILD_SHELTER -> 3.0;
                case GATHER_RESOURCE -> 1.3;
                default -> 1.0;
            };
            case EXPLORER -> switch (goal) {
                case EXPLORE -> 3.0;
                case GATHER_RESOURCE -> 1.5;
                default -> 1.0;
            };
            default -> 1.0;
        };
    }

    // ==================== 对话频率控制 ====================

    /**
     * 根据社交性计算自发对话间隔（tick）。
     * sociability=0.9 → 60s间隔，sociability=0.1 → 300s间隔
     */
    public static int spontaneousDialogueInterval(CompanionPersonality p) {
        if (p == null) return 200; // 默认10秒
        // 映射：sociability 0→300s, 0.5→120s, 1.0→40s
        int sec = (int)(300 - p.sociability * 260);
        return Math.max(40, sec) * 20; // 转换为tick
    }
}
