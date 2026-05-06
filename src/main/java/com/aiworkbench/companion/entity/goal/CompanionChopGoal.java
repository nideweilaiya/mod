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
 * Companion Chop Goal - chops nearby trees (wood/logs).
 * When chop mode is enabled, the companion will seek out and chop
 * tree trunks (logs) and collect the drops.
 */
public class CompanionChopGoal extends Goal {
    private final AutomatonEntity companion;
    private final double speed;
    private final float range;
    private BlockPos targetBlock;
    private boolean isChopping = false;
    private int chopTicks = 0;
    private float accumulatedProgress = 0f;
    private static final float BASE_BREAK_TICKS_PER_HARDNESS = 30f;
    private static final double CHOP_DISTANCE_SQ = 3.0 * 3.0;  // Must be within 3 blocks

    // Block tags that are wood/logs (tree trunks)
    private static final List<String> LOG_BLOCKS = List.of(
        "oak_log", "spruce_log", "birch_log", "jungle_log", "dark_oak_log", "acacia_log",
        "stripped_oak_log", "stripped_spruce_log", "stripped_birch_log",
        "stripped_jungle_log", "stripped_dark_oak_log", "stripped_acacia_log",
        "oak_wood", "spruce_wood", "birch_wood", "jungle_wood", "dark_oak_wood", "acacia_wood",
        "stripped_oak_wood", "stripped_spruce_wood", "stripped_birch_wood",
        "stripped_jungle_wood", "stripped_dark_oak_wood", "stripped_acacia_wood",
        "crimson_stem", "warped_stem", "stripped_crimson_stem", "stripped_warped_stem",
        "crimson_hyphae", "warped_hyphae", "stripped_crimson_hyphae", "stripped_warped_hyphae"
    );

    // Block tags that are leaves (can be ignored or collected)
    private static final List<String> LEAF_BLOCKS = List.of(
        "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves",
        "dark_oak_leaves", "acacia_leaves", "azalea_leaves", "flowering_azalea_leaves",
        "mangrove_leaves", "cherry_leaves",
        "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves"
    );

    public CompanionChopGoal(AutomatonEntity companion, double speed, float range) {
        this.companion = companion;
        this.speed = speed;
        this.range = range;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // Only active when chop mode is enabled
        if (!companion.isChopModeEnabled()) {
            return false;
        }

        // Don't start new chopping if already chopping
        if (isChopping) {
            return true;
        }

        // Find a block to chop
        targetBlock = findLogBlock();
        return targetBlock != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!companion.isChopModeEnabled()) {
            return false;
        }

        // If we have a target block and it's still valid, continue
        if (targetBlock != null && targetBlock.closerToCenterThan(companion.position(), range)) {
            // Check if block still exists and is a log
            if (isLogBlock(targetBlock)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public void start() {
        if (targetBlock != null) {
            AICompanionMod.LOGGER.info("[ChopGoal] Starting to chop block at: " + targetBlock);
            isChopping = false;
            chopTicks = 0;
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

        if (distSq > CHOP_DISTANCE_SQ) {
            // Move toward block
            companion.getNavigation().moveTo(targetBlock.getX(), targetBlock.getY(), targetBlock.getZ(), speed);
            companion.setSpeed((float) speed);
            isChopping = false;
            chopTicks = 0;
            accumulatedProgress = 0f;
        } else {
            // In range - start/stop chopping
            companion.getNavigation().stop();
            companion.setSpeed(0f);

            if (isLogBlock(targetBlock)) {
                // Progressive chopping based on block hardness
                BlockState state = companion.level().getBlockState(targetBlock);
                float hardness = state.getBlock().defaultDestroyTime();

                float toolSpeed = companion.getToolDigSpeed(state);
                accumulatedProgress += toolSpeed / (hardness * BASE_BREAK_TICKS_PER_HARDNESS);
                isChopping = true;

                // Swing animation every 6 ticks
                if (chopTicks % 6 == 0) {
                    companion.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                }

                if (accumulatedProgress >= 1.0f) {
                    // Break the block
                    breakBlock(targetBlock);
                    chopTicks = 0;
                    accumulatedProgress = 0f;
                    isChopping = false;
                    // Find next block
                    targetBlock = findLogBlock();
                    if (targetBlock == null) {
                        companion.setChopModeEnabled(false);
                        AICompanionMod.LOGGER.info("[ChopGoal] No more trees to chop");
                    }
                } else {
                    chopTicks++;
                }
            } else {
                // Block no longer a log, find new block
                targetBlock = findLogBlock();
                chopTicks = 0;
                accumulatedProgress = 0f;
                isChopping = false;
            }
        }
    }

    @Override
    public void stop() {
        targetBlock = null;
        isChopping = false;
        chopTicks = 0;
        accumulatedProgress = 0f;
        AICompanionMod.LOGGER.info("[ChopGoal] Stopped chopping");
    }

    /**
     * Find the nearest log block
     */
    private BlockPos findLogBlock() {
        Level level = companion.level();
        BlockPos companionPos = companion.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();

        // Scan a cubic area around the companion
        int rangeInt = (int) range;
        for (int dx = -rangeInt; dx <= rangeInt; dx++) {
            for (int dy = -1; dy <= rangeInt; dy++) {  // Trees grow upward mostly
                for (int dz = -rangeInt; dz <= rangeInt; dz++) {
                    BlockPos pos = companionPos.offset(dx, dy, dz);
                    if (isLogBlock(pos)) {
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
     * Check if a block position is a log (tree trunk)
     */
    private boolean isLogBlock(BlockPos pos) {
        Level level = companion.level();
        if (!level.isInWorldBounds(pos)) {
            return false;
        }

        BlockState state = level.getBlockState(pos);

        // Don't process air or void
        if (state.isAir() || state.is(Blocks.VOID_AIR) || state.is(Blocks.CAVE_AIR)) {
            return false;
        }

        // Don't process liquids
        if (state.liquid()) {
            return false;
        }

        // Get the block's resource location name
        String blockName = state.getBlock().builtInRegistryHolder().key().location().getPath();

        // Check if it's in our list of log blocks
        for (String log : LOG_BLOCKS) {
            if (blockName.equals(log)) {
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

        AICompanionMod.LOGGER.info("[ChopGoal] Chopping block: " +
            state.getBlock().builtInRegistryHolder().key().location() + " at " + pos);

        // Play break sound and particles
        level.levelEvent(2001, pos, net.minecraft.world.level.block.Block.getId(state));

        // Get drops before clearing
        ItemStack heldTool = companion.getEquippedTool();
        List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(state,
            (net.minecraft.server.level.ServerLevel) level, pos, null, companion, heldTool);

        // Clear the block
        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);

        // Collect drops - add to companion inventory
        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) {
                AICompanionMod.LOGGER.info("[ChopGoal] Collected: " + drop.getDisplayName().getString());
                if (!companion.addItemToInventory(drop)) {
                    // Inventory full, spawn in world
                    AICompanionMod.LOGGER.info("[ChopGoal] Inventory full, spawning in world");
                    companion.spawnAtLocation(drop);
                }
            }
        }
    }
}
