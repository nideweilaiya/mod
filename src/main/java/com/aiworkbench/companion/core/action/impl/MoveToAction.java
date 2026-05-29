package com.aiworkbench.companion.core.action.impl;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.core.action.ActionResult;
import com.aiworkbench.companion.core.action.IAction;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.task.Navigator;
import com.aiworkbench.companion.task.ObstacleAvoidancePlanner;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Path;

import java.util.Random;

/**
 * 移动到目标坐标的动作原语。
 *
 * <p>每 tick 调用一次 {@link #execute}，内部驱动 MC 原版寻路系统。
 * 到达目标 1.5 格范围内返回 SUCCESS；卡住先尝试绕路，仍卡住返回 FAILURE；
 * 超过最大执行时间返回 FAILURE。</p>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>canExecute 是纯函数：只检查前置条件，不改变世界状态</li>
 *   <li>execute 处理三个终态：SUCCESS / FAILURE / IN_PROGRESS，没有第四种</li>
 *   <li>卡住先绕路再失败：不直接放弃</li>
 *   <li>超时保护：防止永久卡住</li>
 * </ul>
 */
public class MoveToAction implements IAction {

    private final AutomatonEntity entity;
    private final BlockPos target;
    private final double speed;

    private int elapsedTicks;
    private int maxTicks;
    private boolean navigationStarted;
    private int stuckTicks;
    private BlockPos lastPos;
    private boolean recoveryAttempted;
    private boolean shortDistanceMode; // MC-045: 近距离直接用 MoveControl，不走 pathfinder

    private static final double ARRIVAL_THRESHOLD = 1.5;
    private static final double STUCK_DIST_SQ = 0.25;
    private static final int MAX_STUCK_TICKS = 60; // 3 秒卡住 → 触发恢复
    private static final int BASE_TIMEOUT = 200;    // 基础超时 10 秒
    private static final double SHORT_DISTANCE = 4.0; // MC-045: 此距离内跳过 pathfinder
    private static final Random RNG = new Random();

    public MoveToAction(AutomatonEntity entity, BlockPos target, double speed) {
        this.entity = entity;
        this.target = target;
        this.speed = speed;
        this.lastPos = entity.blockPosition();
        // 超时 = 基础 10s + 每格距离 1s
        double dist = Math.sqrt(entity.blockPosition().distSqr(target));
        this.maxTicks = BASE_TIMEOUT + (int) Math.ceil(dist * 20);
    }

    public MoveToAction(AutomatonEntity entity, BlockPos target) {
        this(entity, target, 1.0);
    }

    // ==================== IAction 接口 ====================

    @Override
    public boolean canExecute(PerceptionData perception) {
        if (target == null) return false;
        if (!entity.level().isInWorldBounds(target)) return false;
        if (target.closerThan(entity.blockPosition(), ARRIVAL_THRESHOLD)) return false;

        // MC-045: 近距离不依赖 pathfinder 可达性（createPath 对空气/树干位置经常失败）
        double dist = Math.sqrt(entity.blockPosition().distSqr(target));
        if (dist < SHORT_DISTANCE) return true;

        // 远距离：需要验证 pathfinder 可达性
        Path path = entity.getNavigation().createPath(target, 1);
        return path != null && path.canReach();
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        elapsedTicks++;

        // 已到达
        if (target.closerThan(entity.blockPosition(), ARRIVAL_THRESHOLD)) {
            entity.getNavigation().stop();
            return ActionResult.SUCCESS;
        }

        // 超时保护
        if (elapsedTicks > maxTicks) {
            entity.getNavigation().stop();
            return ActionResult.FAILURE;
        }

        // MC-046: 悬空保护 — 提前到前 3 tick，且每 tick 持续检查
        if (!entity.onGround() && elapsedTicks > 3) {
            BlockPos ground = Navigator.findWalkableGroundStatic(
                entity.level(), entity.blockPosition(), entity.blockPosition());
            if (ground != null && ground.getY() != entity.blockPosition().getY()) {
                entity.setPos(entity.getX(), ground.getY(), entity.getZ());
            }
        }

        double dist = Math.sqrt(entity.blockPosition().distSqr(target));

        // MC-045: 目标在 4 格内 → 跳过 pathfinder，直接用 MoveControl 直走
        if (dist < SHORT_DISTANCE || shortDistanceMode) {
            shortDistanceMode = true;
            entity.getNavigation().stop(); // 确保 pathfinder 不干扰
            directWalk();
        } else {
            shortDistanceMode = false;
            // 启动或重试寻路
            if (!navigationStarted || entity.getNavigation().isDone()) {
                boolean started = tryNavigate();
                navigationStarted = true;
                if (!started) {
                    directWalk();
                }
            }
        }

        // 卡住检测 → 先绕路
        BlockPos now = entity.blockPosition();
        if (now.distSqr(lastPos) < STUCK_DIST_SQ) {
            stuckTicks++;
            if (stuckTicks > MAX_STUCK_TICKS) {
                if (!recoveryAttempted) {
                    // 调用 ObstacleAvoidancePlanner 计算最优绕路
                    BlockPos detour = ObstacleAvoidancePlanner.plan(
                        entity.level(), entity.blockPosition(), target, perception);
                    if (detour == null) {
                        detour = randomDetour(); // fallback
                    }
                    entity.getNavigation().moveTo(
                        detour.getX(), detour.getY(), detour.getZ(), speed
                    );
                    recoveryAttempted = true;
                    stuckTicks = 0;
                    lastPos = now;
                    return ActionResult.IN_PROGRESS;
                } else {
                    // 已经绕路过仍卡住 → 真正失败
                    entity.getNavigation().stop();
                    return ActionResult.FAILURE;
                }
            }
        } else {
            stuckTicks = 0;
            lastPos = now;
        }

        return ActionResult.IN_PROGRESS;
    }

    @Override
    public int getCost() {
        double dist = Math.sqrt(entity.blockPosition().distSqr(target));
        return (int) Math.ceil(dist * 20);
    }

    // ==================== 内部方法 ====================

    /**
     * 短距离直接行走：不依赖 MC 寻路器，直接朝目标移动。
     * 用于 pathfinder 无法处理短距离的情况（如目标在 2-3 格外）。
     */
    private void directWalk() {
        double dx = target.getX() + 0.5 - entity.getX();
        double dz = target.getZ() + 0.5 - entity.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 0.01) {
            entity.getMoveControl().setWantedPosition(
                target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);
        }
    }

    /**
     * 尝试寻路到目标。精确坐标失败时回退到地面级寻路。
     */
    private boolean tryNavigate() {
        boolean ok = entity.getNavigation().moveTo(
            target.getX(), target.getY(), target.getZ(), speed
        );
        if (ok) return true;

        BlockPos ground = Navigator.findWalkableGroundStatic(
            entity.level(), target, entity.blockPosition()
        );
        if (ground != null) {
            return entity.getNavigation().moveTo(
                ground.getX(), ground.getY(), ground.getZ(), speed
            );
        }
        return false;
    }

    /**
     * 在当前位置周围 2 格内随机选一个可行走的偏移，用于卡住时绕路。
     */
    private BlockPos randomDetour() {
        for (int attempt = 0; attempt < 8; attempt++) {
            int dx = RNG.nextInt(5) - 2; // -2 ~ +2
            int dz = RNG.nextInt(5) - 2;
            if (dx == 0 && dz == 0) continue;
            BlockPos candidate = entity.blockPosition().offset(dx, 0, dz);
            if (Navigator.findWalkableGroundStatic(entity.level(), candidate, candidate) != null) {
                return candidate;
            }
        }
        // 兜底：尝试直接向目标方向走一格
        int dx = Integer.compare(target.getX(), entity.blockPosition().getX());
        int dz = Integer.compare(target.getZ(), entity.blockPosition().getZ());
        return entity.blockPosition().offset(dx, 0, dz);
    }
}
