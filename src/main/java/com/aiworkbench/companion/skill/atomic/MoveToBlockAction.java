package com.aiworkbench.companion.skill.atomic;

import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.AtomicAction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;

/**
 * 移动到最近的匹配方块位置。
 * <p>
 * 使用 {@link BlockMatcher} 查找最近的方块，
 * 到达目标 3 格范围内完成。
 */
public class MoveToBlockAction implements AtomicAction {

    private static final double ARRIVAL_DISTANCE_SQ = 9.0;
    private static final int CHECK_INTERVAL = 10;
    private static final int SEARCH_RADIUS = 16;
    private static final int TIMEOUT_TICKS = 300; // 15 seconds

    private final BlockMatcher matcher;
    private final double speed;
    private BlockPos target;
    private int tickCounter;
    private boolean searched;

    public MoveToBlockAction(BlockMatcher matcher, double speed) {
        this.matcher = matcher;
        this.speed = speed;
        this.tickCounter = 0;
        this.searched = false;
    }

    public MoveToBlockAction(BlockMatcher matcher) {
        this(matcher, 1.0);
    }

    @Override
    public boolean canStart(AutomatonEntity entity) {
        return true;
    }

    @Override
    public boolean tick(AutomatonEntity entity) {
        tickCounter++;

        // 首次 tick 或目标消失时搜索最近的方块
        if (!searched || (target != null && !matcher.matches(entity.level(), target))) {
            target = findNearest(entity);
            searched = true;
        }

        if (target == null) {
            // 没有找到匹配方块，跳过此步骤
            return true;
        }

        PathNavigation nav = entity.getNavigation();

        if (nav.isDone()) {
            nav.moveTo(target.getX(), target.getY(), target.getZ(), speed);
        }

        // 检查是否到达
        double distSq = entity.position().distanceToSqr(
                target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        if (distSq <= ARRIVAL_DISTANCE_SQ) {
            nav.stop();
            return true;
        }

        // 超时保护
        if (tickCounter > TIMEOUT_TICKS) {
            nav.stop();
            return true;
        }

        return false;
    }

    @Override
    public void stop(AutomatonEntity entity) {
        entity.getNavigation().stop();
        tickCounter = 0;
        searched = false;
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

    @Override
    public String getDescription() {
        return "搜索目标位置";
    }

    public BlockPos getTarget() {
        return target;
    }
}
