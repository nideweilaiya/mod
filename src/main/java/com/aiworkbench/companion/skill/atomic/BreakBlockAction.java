package com.aiworkbench.companion.skill.atomic;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.AtomicAction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 破坏指定类型的方块并收集掉落物。
 * <p>
 * 搜索最近的匹配方块 → 移动到旁边 → 渐进式破坏 → 收集掉落物。
 * 使用与 CompanionMineGoal 相同的渐进式挖掘算法。
 */
public class BreakBlockAction implements AtomicAction {

    private static final double BREAK_DISTANCE_SQ = 3.0 * 3.0;
    private static final float BASE_BREAK_TICKS_PER_HARDNESS = 30f;
    private static final int SEARCH_RADIUS = 12;
    private static final int TIMEOUT_TICKS = 400;

    private final BlockMatcher matcher;
    private BlockPos target;
    private int tickCounter;
    private float accumulatedProgress;
    private int mineTicks;
    private boolean isBreaking;
    private boolean searched;

    public BreakBlockAction(BlockMatcher matcher) {
        this.matcher = matcher;
        this.tickCounter = 0;
        this.accumulatedProgress = 0f;
        this.mineTicks = 0;
        this.isBreaking = false;
        this.searched = false;
    }

    @Override
    public boolean canStart(AutomatonEntity entity) {
        return true;
    }

    @Override
    public boolean tick(AutomatonEntity entity) {
        tickCounter++;

        if (!searched || (target != null && !matcher.matches(entity.level(), target))) {
            target = findNearest(entity);
            searched = true;
        }

        if (target == null) {
            AICompanionMod.LOGGER.info("[BreakBlock] No matching blocks found within radius {}", SEARCH_RADIUS);
            return true; // 没有找到方块，跳过
        }

        // 看向目标
        var blockCenter = net.minecraft.world.phys.Vec3.atCenterOf(target);
        entity.getLookControl().setLookAt(blockCenter.x, blockCenter.y, blockCenter.z);

        double distSq = entity.distanceToSqr(blockCenter.x, blockCenter.y, blockCenter.z);

        if (distSq > BREAK_DISTANCE_SQ) {
            // 走过去
            entity.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), 0.8);
            isBreaking = false;
            accumulatedProgress = 0f;
            mineTicks = 0;
        } else {
            // 到达，开始挖掘
            entity.getNavigation().stop();

            if (matcher.matches(entity.level(), target)) {
                BlockState state = entity.level().getBlockState(target);
                float hardness = state.getBlock().defaultDestroyTime();
                if (hardness < 0) hardness = 50;
                float toolSpeed = entity.getEffectiveDigSpeed(state);
                accumulatedProgress += toolSpeed / (hardness * 30f);
                isBreaking = true;

                // 挥动手臂 + 方块破裂动画
                entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                if (entity.level() instanceof net.minecraft.server.level.ServerLevel sl && mineTicks % 4 == 0) {
                    int crackStage = (int)(accumulatedProgress * 10);
                    if (crackStage > 9) crackStage = 9;
                    sl.destroyBlockProgress(entity.getId(), target, crackStage);
                }

                if (accumulatedProgress >= 1.0f) {
                    if (entity.level() instanceof net.minecraft.server.level.ServerLevel sl)
                        sl.destroyBlockProgress(entity.getId(), target, -1);
                    breakBlock(entity, target);
                    accumulatedProgress = 0f;
                    mineTicks = 0;
                    isBreaking = false;
                    // 继续找下一个同类型方块
                    target = findNearest(entity);
                    if (target == null) {
                        return true; // 全部挖完
                    }
                } else {
                    mineTicks++;
                }
            } else {
                // 方块已被破坏，找下一个
                target = findNearest(entity);
                if (target == null) return true;
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
        if (target != null && entity.level() instanceof net.minecraft.server.level.ServerLevel sl)
            sl.destroyBlockProgress(entity.getId(), target, -1);
        tickCounter = 0;
        accumulatedProgress = 0f;
        mineTicks = 0;
        isBreaking = false;
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
        searched = false;
    }

    @Override
    public String getDescription() {
        return "破坏方块";
    }
}
