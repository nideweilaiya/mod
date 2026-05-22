package com.aiworkbench.companion.entity.goal;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.btree.BehaviorNode;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.atomic.BlockMatcher;
import com.aiworkbench.companion.skill.atomic.BreakBlockAction;
import com.aiworkbench.companion.task.Navigator;
import com.aiworkbench.companion.task.TaskTarget;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * v2.1: 行为树驱动的采集 Goal — 修复不停循环 + 目标锁定 + 流畅度
 *
 * 核心改动:
 * 1. 技能只创建一次（缓存 skill 引用），不每 tick 重建
 * 2. 完成后强制冷却 60tick，防止立即重新扫描
 * 3. 扫描降频：目标完成后才扫描，不每 tick 扫
 * 4. BreakBlockAction 接收 TaskTarget 确保目标不丢失
 *
 * 行为树:
 *   Cooldown(60tick) ── Sequence("采集")
 *     ├─ Condition("有目标?")
 *     ├─ Action("走到目标")
 *     ├─ Action("挖掘目标")
 *     └─ Action("收集掉落")
 */
public class BTreeGatherGoal extends Goal {

    private final AutomatonEntity companion;
    private final Navigator navigator;
    private final BehaviorNode behaviorTree;
    private TaskTarget currentTarget;

    // ── 直接挖掘：不依赖 LLM/skill 匹配 ──
    private com.aiworkbench.companion.skill.atomic.BreakBlockAction blockBreaker;

    // ── 完成冷却 ──
    private int completionCooldown = 0;
    private static final int COMPLETION_COOLDOWN_TICKS = 60; // 3秒冷却

    // ── 扫描参数 ──
    private static final int SCAN_RADIUS = 9;
    private static final Map<String, Integer> BLOCK_PRIORITY = new LinkedHashMap<>();
    static {
        BLOCK_PRIORITY.put("iron_ore", 40);
        BLOCK_PRIORITY.put("coal_ore", 30);
        BLOCK_PRIORITY.put("copper_ore", 25);
        BLOCK_PRIORITY.put("diamond_ore", 60);
        BLOCK_PRIORITY.put("emerald_ore", 55);
        BLOCK_PRIORITY.put("gold_ore", 45);
        BLOCK_PRIORITY.put("lapis_ore", 35);
        BLOCK_PRIORITY.put("redstone_ore", 30);
        BLOCK_PRIORITY.put("ancient_debris", 80);
        BLOCK_PRIORITY.put("oak_log", 8);
        BLOCK_PRIORITY.put("birch_log", 8);
        BLOCK_PRIORITY.put("spruce_log", 8);
        BLOCK_PRIORITY.put("stone", 1);
        BLOCK_PRIORITY.put("dirt", 1);
    }

    public BTreeGatherGoal(AutomatonEntity companion) {
        this.companion = companion;
        this.navigator = new Navigator(companion);
        this.behaviorTree = buildTree();
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private BehaviorNode buildTree() {
        return BehaviorNode.cooldown("采集冷却", COMPLETION_COOLDOWN_TICKS,
            BehaviorNode.sequence("采集",
                new BehaviorNode.Condition("有目标?", (e, t) -> hasTarget(e, t)),
                new BehaviorNode.Action("走到目标", this::moveToTarget),
                new BehaviorNode.Action("挖掘目标", this::mineTarget),
                new BehaviorNode.Action("收集掉落", this::collectItems)
            )
        );
    }

    // ==================== Goal 生命周期 ====================

    @Override public boolean canUse() {
        if (companion.isSkillActive() || !companion.isGatherModeEnabled()) return false;
        // 冷却中不启动
        if (completionCooldown > 0) return false;
        currentTarget = scan();
        return currentTarget != null;
    }

    @Override public boolean canContinueToUse() {
        return currentTarget != null && companion.isGatherModeEnabled();
    }

    @Override public void start() {
        AICompanionMod.LOGGER.info("[BTreeGather] Started: {}", currentTarget);
        blockBreaker = null;
        behaviorTree.onStart(companion, currentTarget);
    }

    @Override public void tick() {
        if (completionCooldown > 0) {
            completionCooldown--;
            return;
        }

        if (currentTarget == null) {
            currentTarget = scan();
            if (currentTarget == null) return;
        }

        BehaviorNode.Status status = behaviorTree.tick(companion, currentTarget);
        if (status == BehaviorNode.Status.SUCCESS) {
            completionCooldown = COMPLETION_COOLDOWN_TICKS;
            blockBreaker = null;
            currentTarget = null;
            companion.setActionText("⛏ 采集完成");
        } else if (status == BehaviorNode.Status.FAILURE) {
            // 失败 → 放弃当前目标，短冷却后重新扫描
            completionCooldown = COMPLETION_COOLDOWN_TICKS / 2;
            blockBreaker = null;
            currentTarget = null;
            AICompanionMod.LOGGER.info("[BTreeGather] Target failed, will rescan");
        }
    }

    @Override public void stop() {
        navigator.stop();
        if (blockBreaker != null) {
            blockBreaker.stop(companion);
            blockBreaker = null;
        }
        currentTarget = null;
        completionCooldown = 0;
    }

    // ==================== 行为树原子动作 ====================

    private boolean hasTarget(AutomatonEntity entity, TaskTarget target) {
        return target != null && target.getPosition() != null;
    }

    private BehaviorNode.Status moveToTarget(AutomatonEntity entity, TaskTarget target) {
        BlockPos pos = target.getPosition();
        if (pos == null) return BehaviorNode.Status.FAILURE;

        double distSq = entity.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (distSq < 9.0) { // 3格
            navigator.stop();
            return BehaviorNode.Status.SUCCESS;
        }

        navigator.navigateTo(pos, 1.0);
        if (navigator.checkStuck()) {
            // 卡住 -> 放弃此目标
            AICompanionMod.LOGGER.info("[BTreeGather] Stuck, abandoning {}", pos);
            return BehaviorNode.Status.FAILURE;
        }
        return BehaviorNode.Status.RUNNING;
    }

    private BehaviorNode.Status mineTarget(AutomatonEntity entity, TaskTarget target) {
        BlockPos pos = target.getPosition();
        if (pos == null) return BehaviorNode.Status.FAILURE;

        // 确保够近
        double distSq = entity.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (distSq > 9.0) {
            navigator.navigateTo(pos, 1.0);
            return BehaviorNode.Status.RUNNING;
        }
        navigator.stop();

        // 目标已消失
        if (entity.level().getBlockState(pos).isAir()) {
            return BehaviorNode.Status.SUCCESS;
        }

        // 直接使用 BreakBlockAction，不走 LLM 技能匹配
        if (blockBreaker == null) {
            String blockName = target.getTargetId();
            String shortName = blockName.contains(":") ? blockName.split(":")[1] : blockName;
            blockBreaker = new BreakBlockAction(BlockMatcher.contains(shortName));
            blockBreaker.setTaskTarget(target);
        }

        if (blockBreaker.tick(entity)) {
            blockBreaker = null;
            return BehaviorNode.Status.SUCCESS;
        }
        return BehaviorNode.Status.RUNNING;
    }

    private BehaviorNode.Status collectItems(AutomatonEntity entity, TaskTarget target) {
        // 简单实现: 自动拾取由实体 tick() 处理
        return BehaviorNode.Status.SUCCESS;
    }

    // ==================== 扫描 ====================

    @Nullable
    private TaskTarget scan() {
        Level level = companion.level();
        BlockPos origin = companion.blockPosition();
        TaskTarget best = null;
        int bestScore = 0;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dy = -3; dy <= 5; dy++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(p);
                    if (state.isAir()) continue;
                    if (state.getBlock().defaultDestroyTime() < 0) continue;

                    String name = state.getBlock().builtInRegistryHolder().key().location().getPath();
                    int score = BLOCK_PRIORITY.getOrDefault(name, 0);
                    if (score <= 0) continue;

                    double dist = origin.distSqr(p);
                    if (score > bestScore || (score == bestScore && dist < bestDist)) {
                        bestScore = score;
                        bestDist = dist;
                        best = TaskTarget.fromBlockState(p, state);
                    }
                }
            }
        }
        return best;
    }

    // ==================== Condition 辅助类 ====================

    private interface ConditionCheck {
        boolean check(AutomatonEntity entity, TaskTarget target);
    }

    private interface ActionRunner {
        BehaviorNode.Status run(AutomatonEntity entity, TaskTarget target);
    }
}
