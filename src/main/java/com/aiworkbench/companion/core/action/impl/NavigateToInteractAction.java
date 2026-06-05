package com.aiworkbench.companion.core.action.impl;

import com.aiworkbench.companion.core.action.ActionResult;
import com.aiworkbench.companion.core.action.IAction;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.task.Navigator;
import com.aiworkbench.companion.task.ObstacleAvoidancePlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Moves the companion to a standable position adjacent to the target block.
 */
public class NavigateToInteractAction implements IAction {

    private static final double ARRIVAL_THRESHOLD = 1.5;
    private static final double STUCK_DIST_SQ = 0.25;
    private static final int MAX_STUCK_TICKS = 60;
    private static final int BASE_TIMEOUT = 200;
    private static final double SHORT_DISTANCE = 4.0;
    private static final double REACH_BYPASS_DISTANCE_SQ = 4.0 * 4.0;
    private static final double MIN_TRAVEL_SPEED = 1.15;
    private static final double FAR_TRAVEL_SPEED = 1.3;
    private static final Random RNG = new Random();

    private final AutomatonEntity entity;
    private final BlockPos targetBlock;
    private final double speed;

    private BlockPos navTarget;
    private int elapsedTicks;
    private int maxTicks;
    private boolean navigationStarted;
    private int stuckTicks;
    private BlockPos lastPos;
    private boolean recoveryAttempted;

    public NavigateToInteractAction(AutomatonEntity entity, BlockPos targetBlock, double speed) {
        this.entity = entity;
        this.targetBlock = targetBlock;
        this.speed = speed;
        this.lastPos = entity.blockPosition();
        double dist = Math.sqrt(entity.blockPosition().distSqr(targetBlock));
        this.maxTicks = BASE_TIMEOUT + (int) Math.ceil(dist * 20);
    }

    public NavigateToInteractAction(AutomatonEntity entity, BlockPos targetBlock) {
        this(entity, targetBlock, MIN_TRAVEL_SPEED);
    }

    @Override
    public boolean canExecute(PerceptionData perception) {
        if (targetBlock == null || !entity.level().isInWorldBounds(targetBlock)) {
            return false;
        }
        if (entity.level().getBlockState(targetBlock).isAir()) {
            return false;
        }
        if (canInteractFromCurrentPosition()) {
            return false;
        }

        List<BlockPos> candidates = computeCandidates(entity.level(), targetBlock, entity.blockPosition());
        if (candidates.isEmpty()) {
            return false;
        }

        for (BlockPos candidate : candidates) {
            Path path = entity.getNavigation().createPath(candidate, 1);
            if (path != null && path.canReach()) {
                return true;
            }
        }
        return Math.sqrt(entity.blockPosition().distSqr(targetBlock)) < SHORT_DISTANCE;
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        elapsedTicks++;

        if (navTarget == null) {
            navTarget = selectBestCandidate();
            if (navTarget == null) {
                return ActionResult.FAILURE;
            }
        }

        if (navTarget.closerThan(entity.blockPosition(), ARRIVAL_THRESHOLD)) {
            entity.getNavigation().stop();
            return ActionResult.SUCCESS;
        }

        if (elapsedTicks > maxTicks) {
            entity.getNavigation().stop();
            return ActionResult.FAILURE;
        }

        double dist = Math.sqrt(entity.blockPosition().distSqr(navTarget));
        if (dist < SHORT_DISTANCE) {
            entity.getNavigation().stop();
            directWalk();
        } else if (!navigationStarted || entity.getNavigation().isDone()) {
            boolean started = tryNavigate();
            navigationStarted = true;
            if (!started) {
                directWalk();
            }
        }

        BlockPos now = entity.blockPosition();
        if (now.distSqr(lastPos) < STUCK_DIST_SQ) {
            stuckTicks++;
            if (stuckTicks > MAX_STUCK_TICKS) {
                if (!recoveryAttempted) {
                    BlockPos detour = ObstacleAvoidancePlanner.plan(
                        entity.level(), entity.blockPosition(), targetBlock, perception
                    );
                    if (detour == null) {
                        detour = randomDetour();
                    }
                    entity.getNavigation().moveTo(
                        detour.getX(), detour.getY(), detour.getZ(), travelSpeed(detour)
                    );
                    recoveryAttempted = true;
                    stuckTicks = 0;
                    lastPos = now;
                    return ActionResult.IN_PROGRESS;
                }
                entity.getNavigation().stop();
                return ActionResult.FAILURE;
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

    private BlockPos selectBestCandidate() {
        List<BlockPos> candidates = computeCandidates(entity.level(), targetBlock, entity.blockPosition());
        if (candidates.isEmpty()) {
            return null;
        }
        BlockPos entityPos = entity.blockPosition();
        return candidates.stream()
            .sorted(Comparator.comparingDouble(c -> c.distSqr(entityPos)))
            .filter(c -> {
                Path path = entity.getNavigation().createPath(c, 1);
                return path != null && path.canReach();
            })
            .findFirst()
            .orElse(candidates.get(0));
    }

    private void directWalk() {
        double dx = navTarget.getX() + 0.5 - entity.getX();
        double dz = navTarget.getZ() + 0.5 - entity.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 0.01) {
            entity.getMoveControl().setWantedPosition(
                navTarget.getX() + 0.5, navTarget.getY(), navTarget.getZ() + 0.5, travelSpeed(navTarget)
            );
        }
    }

    private boolean tryNavigate() {
        double moveSpeed = travelSpeed(navTarget);
        boolean ok = entity.getNavigation().moveTo(navTarget.getX(), navTarget.getY(), navTarget.getZ(), moveSpeed);
        if (ok) {
            return true;
        }
        BlockPos ground = Navigator.findWalkableGroundStatic(entity.level(), navTarget, entity.blockPosition());
        if (ground != null) {
            return entity.getNavigation().moveTo(ground.getX(), ground.getY(), ground.getZ(), moveSpeed);
        }
        return false;
    }

    private double travelSpeed(BlockPos destination) {
        double distSq = entity.blockPosition().distSqr(destination);
        double minSpeed = distSq > 64.0 ? FAR_TRAVEL_SPEED : MIN_TRAVEL_SPEED;
        return Math.max(speed, minSpeed);
    }

    private BlockPos randomDetour() {
        for (int attempt = 0; attempt < 8; attempt++) {
            int dx = RNG.nextInt(5) - 2;
            int dz = RNG.nextInt(5) - 2;
            if (dx == 0 && dz == 0) {
                continue;
            }
            BlockPos candidate = entity.blockPosition().offset(dx, 0, dz);
            if (Navigator.findWalkableGroundStatic(entity.level(), candidate, candidate) != null) {
                return candidate;
            }
        }
        int dx = Integer.compare(navTarget.getX(), entity.blockPosition().getX());
        int dz = Integer.compare(navTarget.getZ(), entity.blockPosition().getZ());
        return entity.blockPosition().offset(dx, 0, dz);
    }

    private static boolean isStandable(Level level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }
        return level.getBlockState(pos.below()).isSolid();
    }

    private boolean canInteractFromCurrentPosition() {
        Vec3 eye = entity.getEyePosition();
        Vec3 center = Vec3.atCenterOf(targetBlock);
        if (eye.distanceToSqr(center) > REACH_BYPASS_DISTANCE_SQ) {
            return false;
        }
        ClipContext context = new ClipContext(
            eye,
            center,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            entity
        );
        BlockHitResult hit = entity.level().clip(context);
        return hit.getBlockPos().equals(targetBlock);
    }

    static List<BlockPos> computeCandidates(Level level, BlockPos target, BlockPos entityPos) {
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos[] offsets = {
            target.north(), target.south(), target.east(), target.west()
        };
        for (BlockPos candidate : offsets) {
            if (Math.abs(candidate.getY() - entityPos.getY()) > 1) {
                continue;
            }
            if (isStandable(level, candidate)) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }
}
