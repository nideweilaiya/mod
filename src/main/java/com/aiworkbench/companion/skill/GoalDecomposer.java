package com.aiworkbench.companion.skill;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.skill.RecipeResolver.RecipeStep;

import java.util.*;

/**
 * 目标分解器 —— 将自然语言目标描述分解为有序子任务链。
 * <p>
 * 工作流程：
 * <ol>
 *   <li>接收高层目标（如 "做一个铁镐"、"获取全套铁甲"）</li>
 *   <li>匹配到已知配方链（RecipeResolver）</li>
 *   <li>跳过已完成的中间步骤（材料已足够）</li>
 *   <li>返回 CompositeTask 或 null（无法解析）</li>
 * </ol>
 */
public class GoalDecomposer {

    /**
     * 分解高层目标为复合任务。
     * @param goalDescription 目标描述（支持中文），如 "铁镐"、"铁甲全套"、"钻石镐"
     * @return 可执行的 CompositeTask，无法解析返回 null
     */
    public static CompositeTask decompose(String goalDescription) {
        if (goalDescription == null || goalDescription.trim().isEmpty()) return null;

        String goal = goalDescription.trim();

        // 提取核心目标词
        String target = extractTargetItem(goal);
        if (target == null) return null;

        // 查询配方
        List<RecipeStep> steps = RecipeResolver.resolve(target);
        if (steps == null || steps.isEmpty()) return null;

        return new CompositeTask(
            "composite_" + target.replace(' ', '_'),
            "制作" + target,
            steps,
            CompositeTask.FailureStrategy.RETRY_ONCE
        );
    }

    /**
     * 从自然语言描述中提取目标物品名。
     */
    private static String extractTargetItem(String text) {
        // 去掉动作词
        String cleaned = text.toLowerCase().trim()
            .replace("做一个", "").replace("做一个", "").replace("合成", "")
            .replace("制作", "").replace("造一个", "").replace("获取", "")
            .replace("得到", "").replace("我需要", "").replace("帮我", "")
            .replace("要", "").replace("个", "").replace("一把", "")
            .replace("一套", "").replace("一个", "").replace("一件", "")
            .replace("全套", "铁甲全套") // "铁甲" → "铁甲全套"
            .trim();

        if (cleaned.isEmpty()) return null;

        // 直接检查是否是已知配方
        if (RecipeResolver.hasRecipe(cleaned)) return cleaned;

        // 尝试前缀匹配
        for (String known : RecipeResolver.getKnownRecipes()) {
            if (known.contains(cleaned) || cleaned.contains(known)) {
                return known;
            }
        }

        return null;
    }

    /**
     * 获取目标分解后的可读描述（用于向玩家展示）。
     */
    public static String describeDecomposition(CompositeTask task) {
        if (task == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("§6🎯 ").append(task.goalDescription).append("§7:\n");
        for (String desc : task.getRemainingStepDescriptions()) {
            sb.append("§7  ").append(desc).append("\n");
        }
        int total = task.getTotalSteps();
        sb.append("§7共").append(total).append("步");
        return sb.toString();
    }
}
