package com.aiworkbench.companion.skill;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 环境反馈收集器 —— 在技能执行完成后收集结构化的环境反馈。
 * <p>
 * Voyager 架构的核心创新："执行 → 反馈 → 修正" 循环。
 * 本类收集技能执行后的可量化结果，供 LLM 自我验证和技能修正使用。
 * <p>
 * 使用方式：
 * <pre>
 *   SkillFeedback feedback = FeedbackCollector.collect(skill, entity);
 *   String promptCtx = FeedbackCollector.toPromptContext(feedback);
 * </pre>
 * thread-safety: 仅在服务器主线程调用。
 */
public class FeedbackCollector {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ================ 公开方法 ================

    /**
     * 收集技能执行后的环境反馈。
     * <p>
     * 调用时机：SkillEngine 中的技能完成（completeSkill）或取消（cancelSkill）后。
     *
     * @param skill  刚执行的技能
     * @param entity 同伴实体
     * @return 结构化的反馈数据
     */
    public static SkillFeedback collect(Skill skill, AutomatonEntity entity) {
        SkillFeedback feedback = new SkillFeedback();

        // 基础信息
        feedback.skillName = skill.getName();
        feedback.skillDescription = skill.getDescription();
        feedback.companionId = entity.getUUID().toString();
        feedback.timestamp = System.currentTimeMillis();

        // 同伴状态
        feedback.finalHealth = entity.getHealth();
        feedback.finalMaxHealth = entity.getMaxHealth();
        feedback.finalPosition = entity.blockPosition();

        // 执行时间（从 entity.tickCount 推断，由调用方设置）
        feedback.executionTicks = 0; // 由 SkillEngine 设置实际值

        // 背包状态
        feedback.inventoryUsed = countNonEmptySlots(entity);
        feedback.inventoryTotal = entity.getInventorySize();

        // 持有工具
        ItemStack mainHand = entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        feedback.equippedTool = mainHand.isEmpty() ? "空手" : mainHand.getDisplayName().getString();

        // 默认成功
        feedback.failureReason = FailureReason.SUCCESS;

        // 工作模式
        feedback.workingMode = entity.getWorkingMode();

        return feedback;
    }

    /**
     * 重载：指定失败原因和中断时的反馈收集。
     */
    public static SkillFeedback collect(Skill skill, AutomatonEntity entity, FailureReason reason) {
        SkillFeedback feedback = collect(skill, entity);
        feedback.failureReason = reason;
        return feedback;
    }

    /**
     * 将反馈转为 LLM 可读的上下文文本。
     * 用于拼入 Ollama 的 prompt 中作为验证依据。
     *
     * @param feedback 收集到的反馈数据
     * @return 适合嵌入 prompt 的自然语言文本
     */
    public static String toPromptContext(SkillFeedback feedback) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 技能执行反馈 ===\n");
        sb.append("技能名称: ").append(feedback.skillName).append("\n");
        sb.append("技能描述: ").append(feedback.skillDescription).append("\n");
        sb.append("同伴ID: ").append(feedback.companionId).append("\n");

        sb.append("\n--- 执行结果 ---\n");
        if (feedback.failureReason == FailureReason.SUCCESS) {
            sb.append("执行状态: 成功\n");
        } else {
            sb.append("执行状态: 失败\n");
            sb.append("失败原因: ").append(feedback.failureReason.getDescription()).append("\n");
        }

        sb.append("\n--- 同伴状态 ---\n");
        sb.append(String.format("生命值: %.0f/%.0f\n", feedback.finalHealth, feedback.finalMaxHealth));
        sb.append(String.format("位置: (%d, %d, %d)\n",
                feedback.finalPosition.getX(),
                feedback.finalPosition.getY(),
                feedback.finalPosition.getZ()));
        sb.append("手持工具: ").append(feedback.equippedTool).append("\n");
        sb.append("工作模式: ").append(feedback.workingMode).append("\n");
        sb.append(String.format("背包: %d/%d 已用\n",
                feedback.inventoryUsed, feedback.inventoryTotal));

        // 破坏的方块
        if (feedback.brokenBlocks != null && !feedback.brokenBlocks.isEmpty()) {
            sb.append("\n--- 破坏的方块 ---\n");
            for (Map.Entry<String, Integer> entry : feedback.brokenBlocks.entrySet()) {
                sb.append("  ").append(entry.getKey()).append(" x").append(entry.getValue()).append("\n");
            }
        }

        // 获取的物品
        if (feedback.collectedItems != null && !feedback.collectedItems.isEmpty()) {
            sb.append("\n--- 获取的物品 ---\n");
            for (Map.Entry<String, Integer> entry : feedback.collectedItems.entrySet()) {
                sb.append("  ").append(entry.getKey()).append(" x").append(entry.getValue()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 将反馈序列化为 JSON（用于网络传输或持久化）。
     */
    public static String toJson(SkillFeedback feedback) {
        return GSON.toJson(feedback);
    }

    // ================ 内部工具方法 ================

    /**
     * 统计背包中非空槽位数。
     */
    private static int countNonEmptySlots(AutomatonEntity entity) {
        int count = 0;
        for (int i = 0; i < entity.getInventorySize(); i++) {
            if (!entity.getItem(i).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 根据方块注册表名称生成友好名称。
     * 例如 "minecraft:iron_ore" → "铁矿石"
     */
    private static String toFriendlyName(String blockId) {
        if (blockId == null) return "未知方块";
        // 去掉 minecraft: 前缀
        String name = blockId.contains(":") ? blockId.split(":")[1] : blockId;
        // 将下划线替换为空格
        return name.replace('_', ' ');
    }

    // ================ 数据类型 ================

    /**
     * 失败原因枚举。
     */
    public enum FailureReason {
        /** 路径被阻挡，无法到达目标 */
        PATH_BLOCKED("路径被阻挡，无法到达目标"),
        /** 没有合适的工具 */
        NO_TOOL("没有合适的工具"),
        /** 背包已满，无法拾取物品 */
        INVENTORY_FULL("背包已满"),
        /** 目标实体消失/死亡 */
        ENTITY_GONE("目标实体消失"),
        /** 技能被手动中断 */
        INTERRUPTED("技能被中断"),
        /** 执行成功 */
        SUCCESS("执行成功");

        private final String description;

        FailureReason(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 技能执行反馈数据结构。
     */
    public static class SkillFeedback {
        /** 技能名 */
        public String skillName;
        /** 技能描述 */
        public String skillDescription;
        /** 同伴 UUID */
        public String companionId;
        /** 收集时间戳 */
        public long timestamp;

        /** 执行耗时（tick 数） */
        public int executionTicks;
        /** 失败原因 */
        public FailureReason failureReason;
        /** 额外失败描述（LLM 生成或系统检测的详细原因） */
        public String failureDetail;

        /** 同伴最终生命值 */
        public float finalHealth;
        /** 同伴最大生命值 */
        public float finalMaxHealth;
        /** 同伴最终位置 */
        public BlockPos finalPosition;
        /** 手持工具名称 */
        public String equippedTool;
        /** 当前工作模式 */
        public String workingMode;

        /** 背包已用槽位数 */
        public int inventoryUsed;
        /** 背包总槽位数 */
        public int inventoryTotal;

        /** 破坏的方块及数量（方块名 → 数量） */
        public Map<String, Integer> brokenBlocks = new LinkedHashMap<>();
        /** 获取的物品及数量（物品名 → 数量） */
        public Map<String, Integer> collectedItems = new LinkedHashMap<>();

        /** 技能执行过程中输出的日志/消息 */
        public List<String> executionLog = new ArrayList<>();

        /**
         * 记录一个破坏的方块。
         */
        public void recordBlockBroken(String blockId) {
            brokenBlocks.merge(toFriendlyName(blockId), 1, Integer::sum);
        }

        /**
         * 记录一个获取的物品。
         */
        public void recordItemCollected(String itemId) {
            collectedItems.merge(toFriendlyName(itemId), 1, Integer::sum);
        }

        /**
         * 记录一条执行日志。
         */
        public void log(String message) {
            executionLog.add(message);
        }

        /**
         * 检查是否成功。
         */
        public boolean isSuccess() {
            return failureReason == FailureReason.SUCCESS;
        }
    }
}
