package com.aiworkbench.companion.entity.goal;

import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Companion Follow Goal - continuously follows the owner.
 * Directly controls entity movement for reliable following.
 */
public class CompanionFollowGoal extends Goal {
    private final AutomatonEntity companion;
    private final float minDistance;
    private PathNavigation navigation;
    private int ticksSinceMove = 0;
    private boolean enabled = true;  // toggled via TCP follow command

    // Speed passed to PathNavigation.moveTo()
    // Note: PathNavigation multiplies this by entity's MOVEMENT_SPEED attribute (0.25)
    // So 1.0 = 0.25 blocks/tick, 4.0 = 1.0 blocks/tick (player walk)
    // Target: companion should move at ~3-4 blocks/sec when far, ~2 blocks/sec when close
    private static final double NAV_SPEED_FAR = 4.0;   // 1.0 blocks/tick = ~player walk
    private static final double NAV_SPEED_CLOSE = 2.0; // 0.5 blocks/tick = comfortable follow

    // Distance thresholds (squared)
    private static final double DISTANCE_FAR_SQ = 100.0;   // 10 blocks squared
    private static final double DISTANCE_CLOSE_SQ = 4.0;    // 2 blocks squared

    public CompanionFollowGoal(AutomatonEntity companion, float minDistance, float maxDistance) {
        this.companion = companion;
        this.minDistance = minDistance;
        this.navigation = companion.getNavigation();
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (companion.isSkillActive()) return false;
        if (!companion.isFollowModeActive()) return false;
        return enabled && companion.getOwnerUUID() != null && getOwner() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return enabled && companion.isFollowModeActive() && companion.getOwnerUUID() != null && getOwner() != null;
    }

    @Override
    public void start() {
        ticksSinceMove = 0;
    }

    @Override
    public void tick() {
        LivingEntity owner = getOwner();
        if (owner == null) return;

        // Always look at owner
        companion.getLookControl().setLookAt(owner, 10.0F, (float) companion.getMaxHeadYRot());

        // Get squared distance to owner
        double distSq = companion.distanceToSqr(owner);

        // When distance exceeds 10 blocks, use direct movement control for faster response
        // This is more reliable than pathfinding when owner teleports or moves quickly
        if (distSq > DISTANCE_FAR_SQ) {
            // Use MoveControl for direct, responsive chase
            // Speed 2.0 * MOVEMENT_SPEED attribute (~0.3) = ~0.6 blocks/tick = fast chase
            companion.getMoveControl().setWantedPosition(
                owner.getX(), owner.getY(), owner.getZ(), 2.0
            );
            ticksSinceMove++;
            return;
        }

        // Determine speed based on distance for normal follow
        double navSpeed;
        if (distSq > DISTANCE_CLOSE_SQ) {
            navSpeed = NAV_SPEED_CLOSE;
        } else {
            // Very close - stop moving but keep looking
            companion.getNavigation().stop();
            return;
        }

        // Get owner position with small vertical offset to stay on same Y level
        Vec3 ownerPos = owner.position();
        // Use floor of owner position as target
        BlockPos targetPos = new BlockPos((int) ownerPos.x, (int) ownerPos.y, (int) ownerPos.z);

        // Make sure target is loaded/valid
        if (!companion.level().hasChunkAt(targetPos)) {
            return;
        }

        // Use navigation to move
        companion.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), navSpeed);

        // Manual jump when owner is slightly above us (navigation can't path to higher ground)
        double verticalDiff = owner.getY() - companion.getY();
        if (verticalDiff > 0.5 && verticalDiff <= 2.0 && companion.onGround()) {
            // Owner is above us and close - jump!
            companion.getJumpControl().jump();
        }

        ticksSinceMove++;
    }

    private LivingEntity getOwner() {
        ServerPlayer owner = companion.getOwner(); // Uses cross-dimension search
        if (owner == null) return null;
        // Only follow in same dimension (cross-dimension handled by auto-recall system)
        if (!owner.level().dimension().equals(companion.level().dimension())) return null;
        return owner;
    }

    /**
     * Enable or disable the follow goal (used by TCP follow command)
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            companion.getNavigation().stop();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
