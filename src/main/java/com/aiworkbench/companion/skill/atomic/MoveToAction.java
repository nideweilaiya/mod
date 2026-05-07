package com.aiworkbench.companion.skill.atomic;

import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.AtomicAction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;

/**
 * 移动到指定位置。
 * <p>
 * 目标位置在构造时设定。同伴使用导航系统移动到目标，
 * 到达目标 2 格范围内视为完成。
 */
public class MoveToAction implements AtomicAction {

    private static final double ARRIVAL_DISTANCE_SQ = 4.0; // 2^2 blocks
    private static final int CHECK_INTERVAL = 10;

    private final BlockPos target;
    private final double speed;
    private int tickCounter;

    public MoveToAction(BlockPos target, double speed) {
        this.target = target;
        this.speed = speed;
        this.tickCounter = 0;
    }

    public MoveToAction(BlockPos target) {
        this(target, 1.0);
    }

    @Override
    public boolean canStart(AutomatonEntity entity) {
        return target != null;
    }

    @Override
    public boolean tick(AutomatonEntity entity) {
        tickCounter++;
        PathNavigation nav = entity.getNavigation();

        if (nav.isDone()) {
            nav.moveTo(target.getX(), target.getY(), target.getZ(), speed);
        }

        if (tickCounter % CHECK_INTERVAL == 0) {
            var pos = entity.position();
            double distSq = pos.distanceToSqr(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            if (distSq <= ARRIVAL_DISTANCE_SQ) {
                nav.stop();
                return true;
            }
        }

        return false;
    }

    @Override
    public void stop(AutomatonEntity entity) {
        entity.getNavigation().stop();
        tickCounter = 0;
    }

    @Override
    public String getDescription() {
        return "移动到 (" + target.getX() + ", " + target.getY() + ", " + target.getZ() + ")";
    }
}
