package com.aiworkbench.companion.entity.goal;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Companion Mine Goal - mines nearby stone and earth blocks.
 * When mine mode is enabled, the companion will seek out and mine
 * nearby blocks like stone, dirt, gravel, sand, etc.
 */
public class CompanionMineGoal extends Goal {
    private final AutomatonEntity companion;
    private final double speed;
    private final float range;
    private BlockPos targetBlock;
    private boolean isMining = false;
    private int miningTicks = 0;
    private float accumulatedProgress = 0f;
    // Base break time multiplier: how many ticks to break a hardness-1.0 block (like a wooden tool)
    private static final float BASE_BREAK_TICKS_PER_HARDNESS = 30f;
    private static final double MINE_DISTANCE_SQ = 3.0 * 3.0;  // Must be within 3 blocks

    // Block tags that are mineable (simplified check)
    private static final List<String> MINEABLE_BLOCKS = List.of(
        "stone", "dirt", "grass", "gravel", "sand", "clay", "ice", "snow", "snow_block",
        "cobblestone", "cobblestone_slab", "cobblestone_stairs",
        "oak_log", "spruce_log", "birch_log", "jungle_log", "dark_oak_log", "acacia_log",
        "oak_planks", "spruce_planks", "birch_planks", "jungle_planks", "dark_oak_planks", "acacia_planks",
        "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves", "dark_oak_leaves", "acacia_leaves",
        "coal_ore", "iron_ore", "copper_ore", "gold_ore", "redstone_ore", "emerald_ore", "lapis_ore",
        "diamond_ore", "deepslate_coal_ore", "deepslate_iron_ore", "deepslate_copper_ore",
        "deepslate_gold_ore", "deepslate_redstone_ore", "deepslate_emerald_ore", "deepslate_lapis_ore",
        "deepslate_diamond_ore", "nether_gold_ore", "nether_quartz_ore",
        "oak_slab", "spruce_slab", "birch_slab", "jungle_slab", "dark_oak_slab", "acacia_slab",
        "stone_slab", "smooth_stone_slab", "cobblestone_slab",
        "oak_stairs", "spruce_stairs", "birch_stairs", "jungle_stairs", "dark_oak_stairs", "acacia_stairs",
        "oak_fence", "spruce_fence", "birch_fence", "jungle_fence", "dark_oak_fence", "acacia_fence",
        "oak_fence_gate", "spruce_fence_gate", "birch_fence_gate", "jungle_fence_gate",
        "dark_oak_fence_gate", "acacia_fence_gate"
    );

    public CompanionMineGoal(AutomatonEntity companion, double speed, float range) {
        this.companion = companion;
        this.speed = speed;
        this.range = range;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // Only active when mine mode is enabled
        if (!companion.isMineModeEnabled()) {
            return false;
        }

        // Don't start new mining if already mining
        if (isMining) {
            return true;
        }

        // Find a block to mine
        targetBlock = findMineableBlock();
        return targetBlock != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!companion.isMineModeEnabled()) {
            return false;
        }

        // If we have a target block and it's still valid, continue
        if (targetBlock != null && targetBlock.closerToCenterThan(companion.position(), range)) {
            // Check if block still exists
            if (isBlockMineable(targetBlock)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public void start() {
        if (targetBlock != null) {
            AICompanionMod.LOGGER.info("[MineGoal] Starting to mine block at: " + targetBlock);
            isMining = false;
            miningTicks = 0;
            accumulatedProgress = 0f;
        }
    }

    @Override
    public void tick() {
        if (targetBlock == null) {
            return;
        }

        // Look at the block
        Vec3 blockCenter = Vec3.atCenterOf(targetBlock);
        companion.getLookControl().setLookAt(blockCenter.x, blockCenter.y, blockCenter.z);

        double distSq = companion.distanceToSqr(blockCenter.x, blockCenter.y, blockCenter.z);

        if (distSq > MINE_DISTANCE_SQ) {
            // Move toward block
            companion.getNavigation().moveTo(targetBlock.getX(), targetBlock.getY(), targetBlock.getZ(), speed);
            companion.setSpeed((float) speed);
            isMining = false;
            miningTicks = 0;
            accumulatedProgress = 0f;
        } else {
            // In range - start/stop mining
            companion.getNavigation().stop();
            companion.setSpeed(0f);

            if (isBlockMineable(targetBlock)) {
                // Progressive mining based on block hardness (like player)
                BlockState state = companion.level().getBlockState(targetBlock);
                float hardness = state.getBlock().defaultDestroyTime();

                // Accumulate progress proportional to hardness and tool speed
                // hardness=1.0, tool speed=1.0 -> BASE_BREAK_TICKS_PER_HARDNESS ticks
                // hardness=1.0, diamond pick (speed=8) -> ticks / 8
                float toolSpeed = companion.getToolDigSpeed(state);
                accumulatedProgress += toolSpeed / (hardness * BASE_BREAK_TICKS_PER_HARDNESS);
                isMining = true;

                // Swing animation every 6 ticks
                if (miningTicks % 6 == 0) {
                    companion.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                }

                if (accumulatedProgress >= 1.0f) {
                    // Break the block
                    breakBlock(targetBlock);
                    miningTicks = 0;
                    accumulatedProgress = 0f;
                    isMining = false;
                    // Find next block
                    targetBlock = findMineableBlock();
                    if (targetBlock == null) {
                        companion.setMineModeEnabled(false);
                        AICompanionMod.LOGGER.info("[MineGoal] No more blocks to mine");
                    }
                } else {
                    miningTicks++;
                }
            } else {
                // Block no longer mineable, find new block
                targetBlock = findMineableBlock();
                miningTicks = 0;
                accumulatedProgress = 0f;
                isMining = false;
            }
        }
    }

    @Override
    public void stop() {
        targetBlock = null;
        isMining = false;
        miningTicks = 0;
        accumulatedProgress = 0f;
        AICompanionMod.LOGGER.info("[MineGoal] Stopped mining");
    }

    /**
     * Find the nearest mineable block
     */
    private BlockPos findMineableBlock() {
        Level level = companion.level();
        BlockPos companionPos = companion.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();

        // Scan a cubic area around the companion
        int rangeInt = (int) range;
        for (int dx = -rangeInt; dx <= rangeInt; dx++) {
            for (int dy = -rangeInt / 2; dy <= rangeInt / 2; dy++) {
                for (int dz = -rangeInt; dz <= rangeInt; dz++) {
                    BlockPos pos = companionPos.offset(dx, dy, dz);
                    if (isBlockMineable(pos)) {
                        candidates.add(pos);
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        // Return the closest block
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;

        for (BlockPos pos : candidates) {
            double dist = companion.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (dist < closestDist) {
                closestDist = dist;
                closest = pos;
            }
        }

        return closest;
    }

    /**
     * Check if a block position contains a mineable block
     */
    private boolean isBlockMineable(BlockPos pos) {
        Level level = companion.level();
        if (!level.isInWorldBounds(pos)) {
            return false;
        }

        BlockState state = level.getBlockState(pos);

        // Don't mine air or void
        if (state.isAir() || state.is(Blocks.VOID_AIR) || state.is(Blocks.CAVE_AIR)) {
            return false;
        }

        // Don't mine liquids or special blocks
        if (state.liquid()) {
            return false;
        }

        // Get the block's resource location name
        String blockName = state.getBlock().builtInRegistryHolder().key().location().getPath();

        // Check if it's in our list of mineable blocks
        for (String mineable : MINEABLE_BLOCKS) {
            if (blockName.contains(mineable)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Break the block and collect drops
     */
    private void breakBlock(BlockPos pos) {
        Level level = companion.level();
        BlockState state = level.getBlockState(pos);

        AICompanionMod.LOGGER.info("[MineGoal] Breaking block: " +
            state.getBlock().builtInRegistryHolder().key().location() + " at " + pos);

        // Remove the block
        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);

        // Get drops - use equipped tool for fortune/silk touch
        ItemStack heldTool = companion.getEquippedTool();
        List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(state,
            (net.minecraft.server.level.ServerLevel) level, pos, null, companion, heldTool);

        int collected = 0;
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            AICompanionMod.LOGGER.info("[MineGoal] Got drop: " + drop.getDisplayName().getString() + " x" + drop.getCount());
            if (companion.addItemToInventory(drop)) {
                collected++;
            } else {
                // Inventory full - spawn at companion's feet
                companion.spawnAtLocation(drop);
                AICompanionMod.LOGGER.info("[MineGoal] Inventory full, spawned at companion");
            }
        }
        if (collected > 0) {
            AICompanionMod.LOGGER.info("[MineGoal] Collected " + collected + " drops into inventory");
        }
    }
}
