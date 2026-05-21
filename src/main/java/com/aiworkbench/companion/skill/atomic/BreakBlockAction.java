package com.aiworkbench.companion.skill.atomic;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.AtomicAction;
import com.aiworkbench.companion.task.TaskTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 破坏指定类型的方块并收集掉落物 — v2.1 目标锁定 + 降频搜索
 *
 * 核心改动：
 * 1. 支持外部传入固定目标（通过 setTaskTarget），解决目标丢失问题
 * 2. 搜索降频：每 10 tick 才重新搜索，不每 tick 扫 7500 个方块
 * 3. 目标锁定：外部传入目标后不再自搜索，只锁定那个方块
 */
public class BreakBlockAction implements AtomicAction {

    private static final double BREAK_DISTANCE_SQ = 3.0 * 3.0;
    private static final float BASE_BREAK_TICKS_PER_HARDNESS = 30f;
    private static final int SEARCH_RADIUS = 12;
    private static final int TIMEOUT_TICKS = 600;
    private static final int SEARCH_INTERVAL = 10; // 每10 tick搜索一次

    private final BlockMatcher matcher;
    private BlockPos target;
    private int tickCounter;
    private float accumulatedProgress;
    private int mineTicks;
    private boolean isBreaking;
    private int searchTimer;

    // ── v2.1: 外部传入的目标锁定 ──
    private TaskTarget lockedTarget;
    private boolean hasExternalTarget;

    public BreakBlockAction(BlockMatcher matcher) {
        this.matcher = matcher;
        this.tickCounter = 0;
        this.accumulatedProgress = 0f;
        this.mineTicks = 0;
        this.isBreaking = false;
        this.searchTimer = 0;
        this.hasExternalTarget = false;
        this.lockedTarget = null;
    }

    /**
     * v2.1: 从外部传入固定目标（由 BTreeGatherGoal 或 TaskTarget 设置）。
     * 设置后不再自搜索，只锁定此方块。
     */
    public void setTaskTarget(TaskTarget taskTarget) {
        if (taskTarget != null && taskTarget.getPosition() != null) {
            this.lockedTarget = taskTarget;
            this.hasExternalTarget = true;
            this.target = taskTarget.getPosition();
        }
    }

    @Override
    public boolean canStart(AutomatonEntity entity) {
        return true;
    }

    @Override
    public boolean tick(AutomatonEntity entity) {
        tickCounter++;

        // ── v2.1: 目标锁定模式 — 不重新搜索 ──
        if (hasExternalTarget) {
            if (target == null || entity.level().getBlockState(target).isAir()) {
                // 目标已被破坏，完成
                return true;
            }
            // 直接进入移动/挖掘逻辑
            return performBreak(entity);
        }

        // ── 自搜索模式（降频） ──
        searchTimer++;
        if (searchTimer >= SEARCH_INTERVAL) {
            searchTimer = 0;
            if (target == null || entity.level().getBlockState(target).isAir()) {
                target = findNearest(entity);
            }
        }

        if (target == null) {
            return true; // 没有找到方块
        }

        return performBreak(entity);
    }

    /**
     * 执行实际的移动→挖掘逻辑（两种模式共用）
     */
    private boolean performBreak(AutomatonEntity entity) {
        var blockCenter = net.minecraft.world.phys.Vec3.atCenterOf(target);
        entity.getLookControl().setLookAt(blockCenter.x, blockCenter.y, blockCenter.z);

        double distSq = entity.distanceToSqr(blockCenter.x, blockCenter.y, blockCenter.z);

        if (distSq > BREAK_DISTANCE_SQ) {
            entity.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), 0.8);
            isBreaking = false;
            accumulatedProgress = 0f;
            mineTicks = 0;
        } else {
            entity.getNavigation().stop();

            if (!entity.level().getBlockState(target).isAir()) {
                BlockState state = entity.level().getBlockState(target);
                float hardness = state.getBlock().defaultDestroyTime();
                if (hardness < 0) hardness = 50;
                float toolSpeed = entity.getEffectiveDigSpeed(state);
                accumulatedProgress += toolSpeed / (hardness * 30f);
                isBreaking = true;

                entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                if (entity.level() instanceof ServerLevel sl && mineTicks % 4 == 0) {
                    int crackStage = (int)(accumulatedProgress * 10);
                    if (crackStage > 9) crackStage = 9;
                    sl.destroyBlockProgress(entity.getId(), target, crackStage);
                }

                if (accumulatedProgress >= 1.0f) {
                    if (entity.level() instanceof ServerLevel sl)
                        sl.destroyBlockProgress(entity.getId(), target, -1);
                    breakBlock(entity, target);
                    accumulatedProgress = 0f;
                    mineTicks = 0;
                    isBreaking = false;

                    // v2.1: 目标锁定模式完成一次后退出
                    if (hasExternalTarget) {
                        return true;
                    }
                    // 自搜索模式继续找下一个
                    target = null;
                } else {
                    mineTicks++;
                }
            } else {
                // 方块已被破坏
                if (hasExternalTarget) return true;
                target = null;
            }
        }

        if (tickCounter > TIMEOUT_TICKS) {
            entity.getNavigation().stop();
            return true;
        }

        return false;
    }

    @Override
    public void stop(AutomatonEntity entity) {
        entity.getNavigation().stop();
        if (target != null && entity.level() instanceof ServerLevel sl)
            sl.destroyBlockProgress(entity.getId(), target, -1);
        tickCounter = 0;
        accumulatedProgress = 0f;
        mineTicks = 0;
        isBreaking = false;
        searchTimer = 0;
        hasExternalTarget = false;
        lockedTarget = null;
    }

    private BlockPos findNearest(AutomatonEntity entity) {
        BlockPos origin = entity.blockPosition();
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;

        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS / 2; dy <= SEARCH_RADIUS / 2; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (matcher.matches(entity.level(), pos)) {
                        double dist = origin.distSqr(pos);
                        if (dist < closestDist) {
                            closestDist = dist;
                            closest = pos.immutable();
                        }
                    }
                }
            }
        }
        return closest;
    }

    private void breakBlock(AutomatonEntity entity, BlockPos pos) {
        if (entity.level().isClientSide) return;

        Level level = entity.level();
        BlockState state = level.getBlockState(pos);

        ItemStack heldTool = entity.getEquippedTool();
        if (!(level instanceof ServerLevel serverLevel)) return;
        List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(state,
                serverLevel, pos, null, entity, heldTool);

        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);

        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) {
                if (!entity.addItemToInventory(drop)) {
                    entity.spawnAtLocation(drop);
                }
            }
        }

        AICompanionMod.LOGGER.info("[BreakBlock] Broke {} at {}",
                state.getBlock().builtInRegistryHolder().key().location(), pos);
    }

    @Override
    public void reset() {
        target = null;
        tickCounter = 0;
        accumulatedProgress = 0f;
        mineTicks = 0;
        isBreaking = false;
        searchTimer = 0;
        hasExternalTarget = false;
        lockedTarget = null;
    }

    @Override
    public String getDescription() {
        return "破坏方块";
    }
}
