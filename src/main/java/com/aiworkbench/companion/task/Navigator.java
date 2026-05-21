package com.aiworkbench.companion.task;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.core.BlockPos;
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
        boolean started = nav.moveTo(target.getX(), target.getY(), target.getZ(), speed);

        if (!started) {
            // 寻路失败 -> 尝试简化
            return nav.moveTo(target.getX(), target.getY(), target.getZ(), speed * 0.5);
        }
        return true;
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

    public BlockPos getCurrentTarget() { return currentTarget; }
}
