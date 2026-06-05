package com.aiworkbench.companion.core.action.impl;

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
 * Moves the companion to an exact position.
 */
public class MoveToAction implements IAction {

    private static final double ARRIVAL_THRESHOLD = 1.5;
    private static final double STUCK_DIST_SQ = 0.25;
    private static final int MAX_STUCK_TICKS = 60;
    private static final int BASE_TIMEOUT = 200;
    private static final double SHORT_DISTANCE = 4.0;
    private static final double MIN_TRAVEL_SPEED = 1.15;
    private static final double FAR_TRAVEL_SPEED = 1.3;
    private static final Random RNG = new Random();

    private final AutomatonEntity entity;
    private final BlockPos target;
    private final double speed;

    private int elapsedTicks;
    private int maxTicks;
    private boolean navigationStarted;
    private int stuckTicks;
    private BlockPos lastPos;
    private boolean recoveryAttempted;
    private boolean shortDistanceMode;

    public MoveToAction(AutomatonEntity entity, BlockPos target, double speed) {
        this.entity = entity;
        this.target = target;
        this.speed = speed;
        this.lastPos = entity.blockPosition();
        double dist = Math.sqrt(entity.blockPosition().distSqr(target));
        this.maxTicks = BASE_TIMEOUT + (int) Math.ceil(dist * 20);
    }

    public MoveToAction(AutomatonEntity entity, BlockPos target) {
        this(entity, target, MIN_TRAVEL_SPEED);
    }

    @Override
    public boolean canExecute(PerceptionData perception) {
        if (target == null) {
            return false;
        }
        if (!entity.level().isInWorldBounds(target)) {
            return false;
        }
        if (target.closerThan(entity.blockPosition(), ARRIVAL_THRESHOLD)) {
            return false;
        }

        double dist = Math.sqrt(entity.blockPosition().distSqr(target));
        if (dist < SHORT_DISTANCE) {
            return true;
        }

        Path path = entity.getNavigation().createPath(target, 1);
        return path != null && path.canReach();
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        elapsedTicks++;

        if (target.closerThan(entity.blockPosition(), ARRIVAL_THRESHOLD)) {
            entity.getNavigation().stop();
            return ActionResult.SUCCESS;
        }

        if (elapsedTicks > maxTicks) {
            entity.getNavigation().stop();
            return ActionResult.FAILURE;
        }

        double dist = Math.sqrt(entity.blockPosition().distSqr(target));
        if (dist < SHORT_DISTANCE || shortDistanceMode) {
            shortDistanceMode = true;
            entity.getNavigation().stop();
            directWalk();
        } else {
            shortDistanceMode = false;
            if (!navigationStarted || entity.getNavigation().isDone()) {
                boolean started = tryNavigate();
                navigationStarted = true;
                if (!started) {
                    directWalk();
                }
            }
        }

        BlockPos now = entity.blockPosition();
        if (now.distSqr(lastPos) < STUCK_DIST_SQ) {
            stuckTicks++;
            if (stuckTicks > MAX_STUCK_TICKS) {
                if (!recoveryAttempted) {
                    BlockPos detour = ObstacleAvoidancePlanner.plan(
                        entity.level(), entity.blockPosition(), target, perception
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
        double dist = Math.sqrt(entity.blockPosition().distSqr(target));
        return (int) Math.ceil(dist * 20);
    }

    private void directWalk() {
        double dx = target.getX() + 0.5 - entity.getX();
        double dz = target.getZ() + 0.5 - entity.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 0.01) {
            entity.getMoveControl().setWantedPosition(
                target.getX() + 0.5, target.getY(), target.getZ() + 0.5, travelSpeed(target)
            );
        }
    }

    private boolean tryNavigate() {
        double moveSpeed = travelSpeed(target);
        boolean ok = entity.getNavigation().moveTo(
            target.getX(), target.getY(), target.getZ(), moveSpeed
        );
        if (ok) {
            return true;
        }

        BlockPos ground = Navigator.findWalkableGroundStatic(
            entity.level(), target, entity.blockPosition()
        );
        if (ground != null) {
            return entity.getNavigation().moveTo(
                ground.getX(), ground.getY(), ground.getZ(), moveSpeed
            );
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
        int dx = Integer.compare(target.getX(), entity.blockPosition().getX());
        int dz = Integer.compare(target.getZ(), entity.blockPosition().getZ());
        return entity.blockPosition().offset(dx, 0, dz);
    }
}
