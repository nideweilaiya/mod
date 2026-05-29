package com.aiworkbench.companion.task;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;

/**
 * v2.0: 导航抽象层 — 封装寻路逻辑
 *
 * 当前使用 Vanilla 寻路 (PathNavigation)。
 * 当 Baritone 作为依赖加入后, 切换为 Baritone API:
 *
 *   baritone.api.BaritoneAPI.getProvider().getBaritoneForEntity(entity)
 *       .getCustomGoalProcess().setGoalAndPath(new GoalBlock(pos));
 *
 * 接口不变, 只需修改 navigateTo() 内部实现。
 */
public class Navigator {

    private final AutomatonEntity entity;
    private BlockPos currentTarget;
    private int stuckTicks = 0;
    private BlockPos lastPos;
    private static final double STUCK_THRESHOLD = 0.5;  // 方块
    private static final int MAX_STUCK_TICKS = 60;       // 3秒

    public Navigator(AutomatonEntity entity) {
        this.entity = entity;
        this.lastPos = entity.blockPosition();
    }

    /**
     * 导航到目标位置。
     * 如果未来切换为 Baritone:
     *
     *   BaritoneAPI.getProvider().getBaritoneForEntity(entity)
     *       .getCustomGoalProcess().setGoalAndPath(new GoalBlock(pos));
     *
     * 当前使用 Vanilla PathNavigation。
     */
    public boolean navigateTo(BlockPos target, double speed) {
        this.currentTarget = target;

        PathNavigation nav = entity.getNavigation();
        return nav.moveTo(target.getX(), target.getY(), target.getZ(), speed);
    }

    /**
     * 导航到目标正下方的可站立位置（用于高温方块，避免寻路到空中）。
     * 只在目标 Y > 实体 Y+1 时使用。
     */
    public boolean navigateToGroundBelow(BlockPos target, double speed) {
        int entityY = entity.blockPosition().getY();
        BlockPos groundTarget = new BlockPos(target.getX(), entityY, target.getZ());
        this.currentTarget = groundTarget;
        return entity.getNavigation().moveTo(groundTarget.getX(), groundTarget.getY(), groundTarget.getZ(), speed);
    }

    /**
     * 每 tick 调用, 检测卡住。
     * 如果使用 Baritone, 卡住检测由 Baritone 内部处理。
     */
    public boolean checkStuck() {
        BlockPos now = entity.blockPosition();
        if (now.distSqr(lastPos) < STUCK_THRESHOLD * STUCK_THRESHOLD) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
            lastPos = now;
        }
        return stuckTicks > MAX_STUCK_TICKS;
    }

    public boolean isNavigating() {
        return currentTarget != null && !entity.getNavigation().isDone();
    }

    public void stop() {
        entity.getNavigation().stop();
        currentTarget = null;
        stuckTicks = 0;
    }

    /**
     * 精确导航到目标XZ坐标正下方的可站立地面位置。
     * 用于搭路前走到树基正下方等场景。
     *
     * @param targetXZ 目标XZ坐标（Y会被忽略，自动查找地面）
     * @param speed 移动速度
     * @return true=寻路成功，false=无法到达
     */
    public boolean navigateToExact(BlockPos targetXZ, double speed) {
        BlockPos entityPos = entity.blockPosition();
        // 从目标XZ正上方开始向下扫描找第一个实心方块，导航到它上面
        BlockPos ground = findWalkableGround(targetXZ);
        if (ground == null) {
            // fallback: 使用实体当前Y
            ground = new BlockPos(targetXZ.getX(), entityPos.getY(), targetXZ.getZ());
        }
        this.currentTarget = ground;
        return entity.getNavigation().moveTo(ground.getX(), ground.getY(), ground.getZ(), speed);
    }

    /**
     * 在目标XZ坐标上从实体Y向下扫描，找第一个上方两格都是空气的实心方块表面。
     */
    private BlockPos findWalkableGround(BlockPos targetXZ) {
        return findWalkableGroundStatic(entity.level(), targetXZ, entity.blockPosition());
    }

    /** 静态版本，供外部类在不持有Navigator实例时使用 */
    @Nullable
    public static BlockPos findWalkableGroundStatic(net.minecraft.world.level.Level level, BlockPos targetXZ, BlockPos referencePos) {
        int startY = referencePos.getY() + 3;
        BlockPos best = null;
        for (int y = startY; y >= level.getMinBuildHeight() + 1; y--) {
            BlockPos check = new BlockPos(targetXZ.getX(), y, targetXZ.getZ());
            var below = level.getBlockState(check.below());
            var at = level.getBlockState(check);
            var above = level.getBlockState(check.above());
            if (!below.isAir() && below.getBlock().defaultDestroyTime() >= 0
                && (at.isAir() || at.canBeReplaced())
                && (above.isAir() || above.canBeReplaced())) {
                best = check;
            } else if (best != null) {
                break;
            }
        }
        return best;
    }

    public BlockPos getCurrentTarget() { return currentTarget; }
}
