package com.aiworkbench.companion.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Utility for checking if a position is safe for a companion entity.
 * Checks for hazards like lava, fire, cactus, etc.
 */
public class NavigationSafety {

    /**
     * Check if a block position is safe (no dangerous blocks at feet/head level).
     */
    public static boolean isSafe(Level level, BlockPos pos) {
        // Check the block at feet level
        BlockState feetState = level.getBlockState(pos);
        if (isDangerous(feetState)) return false;

        // Check the block at head level
        BlockState headState = level.getBlockState(pos.above());
        if (isDangerous(headState)) return false;

        return true;
    }

    /**
     * Check if a block is dangerous to touch or stand on.
     */
    public static boolean isDangerous(BlockState state) {
        if (state.isAir()) return false;

        // Lava and fire
        if (state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK)) return true;
        if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) return true;

        // Cactus and sweet berries
        if (state.is(Blocks.CACTUS)) return true;
        if (state.is(Blocks.SWEET_BERRY_BUSH)) return true;

        // Wither rose
        if (state.is(Blocks.WITHER_ROSE)) return true;

        // Water (companion can't swim, avoid)
        if (state.is(Blocks.WATER)) return true;

        return false;
    }

    /**
     * Find a safe position near the given target, searching outward in a spiral.
     */
    public static BlockPos findSafePosition(Level level, BlockPos target) {
        if (isSafe(level, target)) return target;

        // Spiral outward searching for safe ground (max radius 5)
        for (int radius = 1; radius <= 5; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    BlockPos candidate = target.offset(dx, 0, dz);
                    if (isSafe(level, candidate)) {
                        // Make sure entity can actually fit here (2 blocks high)
                        if (!level.getBlockState(candidate.above()).isAir()) continue;
                        return candidate;
                    }
                }
            }
        }

        // Fallback: return original position if nothing found
        return target;
    }
}
