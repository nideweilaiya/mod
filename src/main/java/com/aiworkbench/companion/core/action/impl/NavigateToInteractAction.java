package com.aiworkbench.companion.core.action.impl;

import com.aiworkbench.companion.core.action.ActionResult;
import com.aiworkbench.companion.core.action.IAction;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.task.Navigator;
import com.aiworkbench.companion.task.ObstacleAvoidancePlanner;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 走到目标方块相邻位置的动作原语。
 *
 * <p>与 {@link MoveToAction} 的区别：MoveTo 走到精确坐标上，
 * 本原语走到目标方块旁边的可站立位置（差 1 格），用于砍树、挖矿、使用方块等场景。</p>
 *
 * <h3>候选位置计算</h3>
 * 对目标方块检查 5 个相邻位置（4 水平方向 + 顶部），
 * 过滤出可站立位置（下方有固体方块、自身是空气），选最近可达的一个。
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>canExecute 只检查前置条件，不改变世界状态</li>
 *   <li>execute 三个终态：SUCCESS / FAILURE / IN_PROGRESS</li>
 *   <li>卡住先绕路再失败</li>
 *   <li>超时保护</li>
 * </ul>
 */
public class NavigateToInteractAction implements IAction {

    private final AutomatonEntity entity;
    private final BlockPos targetBlock;
    private final double speed;

    private BlockPos navTarget; // 实际导航目标（目标方块旁边的可站立位置）
    private int elapsedTicks;
    private int maxTicks;
    private boolean navigationStarted;
    private int stuckTicks;
    private BlockPos lastPos;
    private boolean recoveryAttempted;

    private static final double ARRIVAL_THRESHOLD = 1.5;
    private static final double STUCK_DIST_SQ = 0.25;
    private static final int MAX_STUCK_TICKS = 60;
    private static final int BASE_TIMEOUT = 200;
    private static final Random RNG = new Random();

    /** 检查位置是否可站立（位置是空气 + 下方是固体方块） */
    private static boolean isStandable(net.minecraft.world.level.Level level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir()) return false;
        return level.getBlockState(pos.below()).isSolid();
    }

    /** 计算目标方块周围的可站立候选位置 */
    static List<BlockPos> computeCandidates(net.minecraft.world.level.Level level, BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
        // 4 水平方向
        BlockPos[] offsets = {
            target.north(), target.south(), target.east(), target.west(),
            target.above() // 顶部
        };
        for (BlockPos candidate : offsets) {
            if (isStandable(level, candidate)) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    public NavigateToInteractAction(AutomatonEntity entity, BlockPos targetBlock, double speed) {
        this.entity = entity;
        this.targetBlock = targetBlock;
        this.speed = speed;
        this.lastPos = entity.blockPosition();
        double dist = Math.sqrt(entity.blockPosition().distSqr(targetBlock));
        this.maxTicks = BASE_TIMEOUT + (int) Math.ceil(dist * 20);
    }

    public NavigateToInteractAction(AutomatonEntity entity, BlockPos targetBlock) {
        this(entity, targetBlock, 1.0);
    }

    // ==================== IAction 接口 ====================

    @Override
    public boolean canExecute(PerceptionData perception) {
        if (targetBlock == null) return false;
        if (!entity.level().isInWorldBounds(targetBlock)) return false;
        // 目标必须是实心方块（不是空气），否则没有"交互"的意义
        if (entity.level().getBlockState(targetBlock).isAir()) return false;

        // 计算候选位置
        List<BlockPos> candidates = computeCandidates(entity.level(), targetBlock);
        if (candidates.isEmpty()) return false;

        // 至少有一个候选位置可达
        for (BlockPos c : candidates) {
            Path path = entity.getNavigation().createPath(c, 1);
            if (path != null && path.canReach()) return true;
        }
        // 近距离兜底：实体距目标 < 4 格时不强求 pathfinder
        return Math.sqrt(entity.blockPosition().distSqr(targetBlock)) < 4.0;
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        elapsedTicks++;

        // 首次执行：选最佳候选位置
        if (navTarget == null) {
            navTarget = selectBestCandidate();
            if (navTarget == null) return ActionResult.FAILURE;
        }

        // 已到达
        if (navTarget.closerThan(entity.blockPosition(), ARRIVAL_THRESHOLD)) {
            entity.getNavigation().stop();
            return ActionResult.SUCCESS;
        }

        // 超时保护
        if (elapsedTicks > maxTicks) {
            entity.getNavigation().stop();
            return ActionResult.FAILURE;
        }

        // 悬空保护
        if (!entity.onGround() && elapsedTicks > 3) {
            BlockPos ground = Navigator.findWalkableGroundStatic(
                entity.level(), entity.blockPosition(), entity.blockPosition());
            if (ground != null && ground.getY() != entity.blockPosition().getY()) {
                entity.setPos(entity.getX(), ground.getY(), entity.getZ());
            }
        }

        double dist = Math.sqrt(entity.blockPosition().distSqr(navTarget));

        // 4 格内直走
        if (dist < 4.0) {
            entity.getNavigation().stop();
            directWalk();
        } else {
            if (!navigationStarted || entity.getNavigation().isDone()) {
                boolean started = tryNavigate();
                navigationStarted = true;
                if (!started) directWalk();
            }
        }

        // 卡住检测 → 绕路
        BlockPos now = entity.blockPosition();
        if (now.distSqr(lastPos) < STUCK_DIST_SQ) {
            stuckTicks++;
            if (stuckTicks > MAX_STUCK_TICKS) {
                if (!recoveryAttempted) {
                    BlockPos detour = ObstacleAvoidancePlanner.plan(
                        entity.level(), entity.blockPosition(), targetBlock, perception);
                    if (detour == null) {
                        detour = randomDetour(); // fallback
                    }
                    entity.getNavigation().moveTo(detour.getX(), detour.getY(), detour.getZ(), speed);
                    recoveryAttempted = true;
                    stuckTicks = 0;
                    lastPos = now;
                    return ActionResult.IN_PROGRESS;
                } else {
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
        double dist = Math.sqrt(entity.blockPosition().distSqr(targetBlock));
        return (int) Math.ceil(dist * 20);
    }

    // ==================== 内部方法 ====================

    /** 从候选位置中选最近可达的一个 */
    private BlockPos selectBestCandidate() {
        List<BlockPos> candidates = computeCandidates(entity.level(), targetBlock);
        if (candidates.isEmpty()) return null;

        BlockPos entityPos = entity.blockPosition();
        // 按距离排序，尝试创建路径
        return candidates.stream()
            .sorted(Comparator.comparingDouble(c -> c.distSqr(entityPos)))
            .filter(c -> {
                Path path = entity.getNavigation().createPath(c, 1);
                return path != null && path.canReach();
            })
            .findFirst()
            .orElse(candidates.get(0)); // 兜底：取最近的一个
    }

    private void directWalk() {
        double dx = navTarget.getX() + 0.5 - entity.getX();
        double dz = navTarget.getZ() + 0.5 - entity.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 0.01) {
            entity.getMoveControl().setWantedPosition(
                navTarget.getX() + 0.5, navTarget.getY(), navTarget.getZ() + 0.5, speed);
        }
    }

    private boolean tryNavigate() {
        boolean ok = entity.getNavigation().moveTo(
            navTarget.getX(), navTarget.getY(), navTarget.getZ(), speed);
        if (ok) return true;

        BlockPos ground = Navigator.findWalkableGroundStatic(
            entity.level(), navTarget, entity.blockPosition());
        if (ground != null) {
            return entity.getNavigation().moveTo(
                ground.getX(), ground.getY(), ground.getZ(), speed);
        }
        return false;
    }

    private BlockPos randomDetour() {
        for (int attempt = 0; attempt < 8; attempt++) {
            int dx = RNG.nextInt(5) - 2;
            int dz = RNG.nextInt(5) - 2;
            if (dx == 0 && dz == 0) continue;
            BlockPos candidate = entity.blockPosition().offset(dx, 0, dz);
            if (Navigator.findWalkableGroundStatic(entity.level(), candidate, candidate) != null) {
                return candidate;
            }
        }
        int dx = Integer.compare(navTarget.getX(), entity.blockPosition().getX());
        int dz = Integer.compare(navTarget.getZ(), entity.blockPosition().getZ());
        return entity.blockPosition().offset(dx, 0, dz);
    }
}
