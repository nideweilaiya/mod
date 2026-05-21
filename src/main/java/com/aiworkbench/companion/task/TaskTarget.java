package com.aiworkbench.companion.task;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * v2.0: 任务目标对象 — 承载坐标/类型/优先级信息在整个行为树中流转
 *
 * 解决核心问题: GatherGoal找到目标后通过字符串传递,
 * BreakBlockAction 自己重新搜索导致找不到/找错。
 *
 * TaskTarget 确保目标在整个执行链路中不丢失。
 */
public class TaskTarget {
    public enum Type {
        BLOCK,       // 破坏方块
        ENTITY,      // 攻击/交互实体
        ITEM,        // 拾取物品
        POSITION,    // 移动到位置
        CRAFT,       // 合成
        BUILD,       // 建造
    }

    private final Type type;
    @Nullable private final BlockPos position;
    private final String targetId;       // 如 "minecraft:iron_ore"
    private final int priority;          // 0-100, 越高越优先
    private long claimedAt;              // 被哪个Agent占用
    @Nullable private String claimedBy;

    public TaskTarget(Type type, @Nullable BlockPos position, String targetId, int priority) {
        this.type = type;
        this.position = position;
        this.targetId = targetId;
        this.priority = priority;
        this.claimedAt = 0;
        this.claimedBy = null;
    }

    /** 快速创建一个方块破坏目标 */
    public static TaskTarget mineBlock(BlockPos pos, String blockId) {
        return new TaskTarget(Type.BLOCK, pos, blockId, 50);
    }

    /** 快速创建一个移动目标 */
    public static TaskTarget moveTo(BlockPos pos) {
        return new TaskTarget(Type.POSITION, pos, "", 10);
    }

    /** 从 BlockState 创建目标 */
    public static TaskTarget fromBlockState(BlockPos pos, BlockState state) {
        String id = state.getBlock().builtInRegistryHolder().key().location().toString();
        return new TaskTarget(Type.BLOCK, pos, id, 50);
    }

    // === Getters ===
    public Type getType() { return type; }
    @Nullable public BlockPos getPosition() { return position; }
    public String getTargetId() { return targetId; }
    public int getPriority() { return priority; }

    /** 尝试锁定目标 (多Agent防冲突) */
    public boolean claim(String agentId) {
        if (claimedBy != null && !claimedBy.equals(agentId)) return false;
        this.claimedBy = agentId;
        this.claimedAt = System.currentTimeMillis();
        return true;
    }

    /** 释放目标 */
    public void release() {
        this.claimedBy = null;
        this.claimedAt = 0;
    }

    public boolean isClaimed() { return claimedBy != null; }
    @Nullable public String getClaimedBy() { return claimedBy; }

    @Override
    public String toString() {
        return String.format("TaskTarget{%s @ %s [%s] pri=%d}",
                type, position, targetId, priority);
    }
}
