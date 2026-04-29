package com.aiworkbench.companion.entity.goal;

import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Jump Goal - Simple terrain detection for 1-block steps.
 * Activates when the block directly in front is solid at feet level.
 */
public class JumpGoal extends Goal {
    private final AutomatonEntity companion;

    public JumpGoal(AutomatonEntity companion) {
        this.companion = companion;
        setFlags(EnumSet.of(Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        // Only jump when on ground
        if (!companion.onGround()) return false;
        if (companion.getOwnerUUID() == null) return false;

        // Check if there's a solid block in front that we need to jump over
        return isSolidBlockAhead();
    }

    @Override
    public boolean canContinueToUse() {
        // Keep jumping until we land
        return !companion.onGround();
    }

    @Override
    public void start() {
        companion.getJumpControl().jump();
    }

    /**
     * Check if there's a solid block at feet level ahead that blocks movement
     */
    private boolean isSolidBlockAhead() {
        Level level = companion.level();
        Vec3 lookAngle = companion.getLookAngle();

        // Get forward direction
        int lookX = (int) Math.signum(Math.round(lookAngle.x));
        int lookZ = (int) Math.signum(Math.round(lookAngle.z));

        if (lookX == 0 && lookZ == 0) {
            // Not looking in any direction
            return false;
        }

        // Current position
        BlockPos current = companion.blockPosition();

        // Block directly ahead at current Y level (what we're walking into)
        BlockPos ahead = new BlockPos(current.getX() + lookX, current.getY(), current.getZ() + lookZ);

        // Check if ahead block at feet level is solid
        if (level.getBlockState(ahead).isSolid()) {
            return true;
        }

        // Also check if ahead block at head level is blocking (for 1-block walls)
        BlockPos aheadAbove = new BlockPos(ahead.getX(), ahead.getY() + 1, ahead.getZ());
        if (level.getBlockState(aheadAbove).isSolid()) {
            return true;
        }

        // Check for 1-block step up
        BlockPos stepUp = new BlockPos(ahead.getX(), current.getY() + 1, ahead.getZ());
        if (level.getBlockState(stepUp).isSolid() && !level.getBlockState(stepUp.above()).isSolid()) {
            return true;
        }

        return false;
    }
}
