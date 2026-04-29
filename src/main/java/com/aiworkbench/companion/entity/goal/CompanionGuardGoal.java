package com.aiworkbench.companion.entity.goal;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Companion Guard Goal - defends owner from hostile mobs.
 * When guard mode is enabled, the companion will seek out and attack
 * nearby hostile mobs, prioritizing enemies closest to the owner.
 */
public class CompanionGuardGoal extends Goal {
    private final AutomatonEntity companion;
    private final double speed;
    private final float range;
    private LivingEntity target;
    private boolean hasTarget = false;

    // Attack range (how close to get before attacking)
    private static final double ATTACK_RANGE_SQ = 2.5 * 2.5;  // 2.5 blocks squared

    public CompanionGuardGoal(AutomatonEntity companion, double speed, float range) {
        this.companion = companion;
        this.speed = speed;
        this.range = range;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        // Only active when guard mode is enabled and we have an owner
        if (!companion.isGuardModeEnabled()) {
            return false;
        }

        if (companion.getOwnerUUID() == null) {
            return false;
        }

        // Find a target
        target = findTarget();
        hasTarget = (target != null);
        return hasTarget;
    }

    @Override
    public boolean canContinueToUse() {
        // Continue if we still have a valid target and guard mode is on
        if (!companion.isGuardModeEnabled()) {
            return false;
        }

        if (target == null || !target.isAlive()) {
            return false;
        }

        // Check if target is still within range
        double distSq = companion.distanceToSqr(target);
        if (distSq > range * range * 2) {  // Allow some margin
            return false;
        }

        return true;
    }

    @Override
    public void start() {
        if (target != null) {
            companion.setGuardTarget(target);
            AICompanionMod.LOGGER.info("[GuardGoal] Started guarding against: " + target.getName().getString());
        }
    }

    @Override
    public void tick() {
        if (target == null || !target.isAlive()) {
            hasTarget = false;
            return;
        }

        // Always look at target
        companion.getLookControl().setLookAt(target, 30.0F, (float) companion.getMaxHeadYRot());

        double distSq = companion.distanceToSqr(target);

        if (distSq > ATTACK_RANGE_SQ) {
            // Move toward target
            companion.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), speed);
            companion.setSpeed((float) speed);

            // Manual jump when target is slightly above us (navigation can't path to higher ground)
            double verticalDiff = target.getY() - companion.getY();
            double horizontalDistSq = companion.distanceToSqr(target.getX(), companion.getY(), target.getZ());
            if (verticalDiff > 0.5 && verticalDiff <= 2.0 && horizontalDistSq <= 4.0 && companion.onGround()) {
                // Target is above us and close enough - jump!
                companion.getJumpControl().jump();
            }
        } else {
            // In attack range - stop and attack
            companion.getNavigation().stop();
            companion.setSpeed(0f);

            // Attack if we can
            if (companion.isWithinAttackRange(target)) {
                companion.swingAttackHand();
                companion.doHurtTarget(target);
            }
        }
    }

    @Override
    public void stop() {
        target = null;
        hasTarget = false;
        companion.setGuardTarget(null);
        AICompanionMod.LOGGER.info("[GuardGoal] Stopped guarding");
    }

    /**
     * Find the nearest hostile mob threatening the owner
     */
    private LivingEntity findTarget() {
        Player owner = getOwner();
        if (owner == null) {
            return null;
        }

        Vec3 ownerPos = owner.position();
        double ownerX = ownerPos.x;
        double ownerY = ownerPos.y;
        double ownerZ = ownerPos.z;

        // Find the closest hostile mob within range of the owner
        LivingEntity closest = null;
        double closestDist = Double.MAX_VALUE;

        var level = companion.level();
        var entities = level.getEntities(companion, companion.getBoundingBox().inflate(range, range / 2, range));

        for (var entity : entities) {
            if (!(entity instanceof Monster)) {
                continue;
            }
            if (!entity.isAlive()) {
                continue;
            }
            // Check if within guard range of owner
            double dist = entity.distanceToSqr(ownerX, ownerY, ownerZ);
            if (dist <= range * range && dist < closestDist) {
                closestDist = dist;
                closest = (LivingEntity) entity;
            }
        }

        return closest;
    }

    /**
     * Get the owner player
     */
    private Player getOwner() {
        if (companion.getOwnerUUID() == null || companion.getServer() == null) {
            return null;
        }
        // Use companion's current dimension instead of always OVERWORLD
        return companion.getServer().getLevel(companion.level().dimension())
            .getPlayerByUUID(companion.getOwnerUUID());
    }

    /**
     * Check if we have a current target
     */
    public boolean hasTarget() {
        return hasTarget && target != null && target.isAlive();
    }

    /**
     * Get current target
     */
    public LivingEntity getTarget() {
        return target;
    }
}
