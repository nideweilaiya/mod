package com.aiworkbench.companion.entity.goal;

import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.entity.NavigationSafety;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.UUID;

/**
 * Companion Wander Goal - random wandering when idle.
 * Only picks new target when navigation is done or stopped.
 * Stops wandering if owner is nearby.
 */
public class CompanionWanderGoal extends Goal {
    private final AutomatonEntity companion;
    private final double speed;
    private int ticksUntilWander;
    private final float range;

    public CompanionWanderGoal(AutomatonEntity companion, double speed, float range) {
        this.companion = companion;
        this.speed = speed;
        this.range = range;
        this.ticksUntilWander = 0;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Nullable
    private Player getOwnerInDimension() {
        UUID ownerUUID = companion.getOwnerUUID();
        if (ownerUUID == null || companion.getServer() == null) return null;
        ServerLevel level = companion.getServer().getLevel(companion.level().dimension());
        if (level == null) return null;
        return level.getPlayerByUUID(ownerUUID);
    }

    @Override
    public boolean canUse() {
        // Don't wander if we have an owner nearby
        Player owner = getOwnerInDimension();
        if (owner != null && companion.distanceTo(owner) < 8.0) {
            return false;
        }

        if (--ticksUntilWander <= 0) {
            ticksUntilWander = 40 + companion.getRandom().nextInt(80);
            // Only wander if not already moving
            return companion.getNavigation().isDone()
                && companion.getRandom().nextInt(3) == 0;
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        // Stop if owner is nearby or navigation is done
        Player owner = getOwnerInDimension();
        if (owner != null && companion.distanceTo(owner) < 6.0) {
            return false;
        }
        return companion.getNavigation().isInProgress();
    }

    @Override
    public void start() {
        double x = companion.getX() + (companion.getRandom().nextDouble() * 2.0 - 1.0) * range;
        double y = companion.getY();
        double z = companion.getZ() + (companion.getRandom().nextDouble() * 2.0 - 1.0) * range;

        // Safety check: find safe position
        BlockPos safePos = NavigationSafety.findSafePosition(companion.level(), new BlockPos((int) x, (int) y, (int) z));

        companion.getNavigation().moveTo(safePos.getX(), safePos.getY(), safePos.getZ(), speed);
    }

    @Override
    public void tick() {
        // Nothing needed - navigation handles movement
        // Just check if we should stop because owner got close
        Player owner = getOwnerInDimension();
        if (owner != null && companion.distanceTo(owner) < 6.0) {
            companion.getNavigation().stop();
        }
    }
}
