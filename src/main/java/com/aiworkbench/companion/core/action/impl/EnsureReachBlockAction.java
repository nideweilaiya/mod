package com.aiworkbench.companion.core.action.impl;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.core.action.ActionResult;
import com.aiworkbench.companion.core.action.IAction;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EnsureReachBlockAction implements IAction {

    private static final double PLAYER_BLOCK_REACH_SQ = 4.5 * 4.5;
    private static final double CLOSE_HORIZONTAL_SQ = 2.8 * 2.8;
    private static final int MAX_TICKS = 240;
    private static final int MAX_PILLAR_BLOCKS = 16;
    private static final int PLACE_INTERVAL_TICKS = 15;
    private static final int MAX_DESCENT_GAP = 2;
    private static final double MOVE_SPEED = 1.2;

    private final AutomatonEntity entity;
    private final BlockPos target;
    private final Map<String, Object> variables;

    private int elapsedTicks;
    private int placedThisAction;
    private int placeCooldown;
    private BlockPos waitingForStepUp;
    private int waitingTicks;

    public EnsureReachBlockAction(AutomatonEntity entity, BlockPos target, Map<String, Object> variables) {
        this.entity = entity;
        this.target = target;
        this.variables = variables;
    }

    @Override
    public boolean canExecute(PerceptionData perception) {
        if (target == null || !entity.level().isInWorldBounds(target)) {
            return false;
        }
        BlockState state = entity.level().getBlockState(target);
        return !state.isAir() && state.getBlock().defaultDestroyTime() >= 0;
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        elapsedTicks++;
        if (elapsedTicks > MAX_TICKS) {
            return ActionResult.FAILURE;
        }
        if (entity.level().getBlockState(target).isAir()) {
            return ActionResult.SUCCESS;
        }
        int descentGap = entity.blockPosition().getY() - target.getY();
        if (descentGap > MAX_DESCENT_GAP) {
            AICompanionMod.LOGGER.warn(
                "[EnsureReachBlock] Target {} is {} blocks below current height {}, aborting pillar-up",
                target,
                descentGap,
                entity.blockPosition()
            );
            return ActionResult.FAILURE;
        }
        if (canReach(target)) {
            return ActionResult.SUCCESS;
        }

        if (placeCooldown > 0) {
            placeCooldown--;
        }

        if (waitingForStepUp != null) {
            if (entity.blockPosition().getY() > waitingForStepUp.getY()) {
                waitingForStepUp = null;
                waitingTicks = 0;
            } else {
                waitingTicks++;
                entity.getNavigation().stop();
                if (entity.onGround() && waitingTicks % 10 == 0) {
                    entity.getJumpControl().jump();
                }
                if (waitingTicks > 20 && entity.blockPosition().getY() <= waitingForStepUp.getY()) {
                    entity.setPos(entity.getX(), waitingForStepUp.getY() + 1.0, entity.getZ());
                    waitingForStepUp = null;
                    waitingTicks = 0;
                }
                return ActionResult.IN_PROGRESS;
            }
        }

        double dx = target.getX() + 0.5 - entity.getX();
        double dz = target.getZ() + 0.5 - entity.getZ();
        double horizontalSq = dx * dx + dz * dz;
        if (horizontalSq > CLOSE_HORIZONTAL_SQ) {
            double horizontal = Math.sqrt(horizontalSq);
            double wantedX = target.getX() + 0.5 - dx / horizontal * 1.8;
            double wantedZ = target.getZ() + 0.5 - dz / horizontal * 1.8;
            entity.getNavigation().moveTo(wantedX, entity.getY(), wantedZ, MOVE_SPEED);
            entity.getMoveControl().setWantedPosition(wantedX, entity.getY(), wantedZ, MOVE_SPEED);
            return ActionResult.IN_PROGRESS;
        }

        if (!entity.onGround() || placeCooldown > 0) {
            return ActionResult.IN_PROGRESS;
        }
        if (placedThisAction >= MAX_PILLAR_BLOCKS) {
            AICompanionMod.LOGGER.warn("[EnsureReachBlock] Exhausted pillar budget for {}", target);
            return ActionResult.FAILURE;
        }

        int slot = findBlockItem();
        if (slot < 0) {
            AICompanionMod.LOGGER.warn("[EnsureReachBlock] No scaffold block available for {}", target);
            return ActionResult.FAILURE;
        }

        BlockPos placePos = choosePillarSpot();
        if (placePos == null) {
            AICompanionMod.LOGGER.warn("[EnsureReachBlock] No valid pillar spot for {}", target);
            return ActionResult.FAILURE;
        }

        if (!isAtPillarSpot(placePos)) {
            entity.getNavigation().moveTo(placePos.getX() + 0.5, entity.getY(), placePos.getZ() + 0.5, MOVE_SPEED);
            entity.getMoveControl().setWantedPosition(placePos.getX() + 0.5, entity.getY(), placePos.getZ() + 0.5, MOVE_SPEED);
            return ActionResult.IN_PROGRESS;
        }

        BlockState placeState = entity.level().getBlockState(placePos);
        if (!placeState.isAir() && !placeState.canBeReplaced()) {
            AICompanionMod.LOGGER.warn(
                "[EnsureReachBlock] Pillar spot {} blocked by {} for {}",
                placePos,
                placeState.getBlock().builtInRegistryHolder().key().location(),
                target
            );
            return ActionResult.FAILURE;
        }

        ItemStack stack = entity.getItem(slot);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return ActionResult.FAILURE;
        }

        int yBefore = entity.blockPosition().getY();
        entity.level().setBlock(placePos, blockItem.getBlock().defaultBlockState(), 3);
        stack.shrink(1);
        if (stack.isEmpty()) {
            entity.setItem(slot, ItemStack.EMPTY);
        }

        temporaryBlocks().add(placePos.immutable());
        pillarPath().add(placePos.immutable());
        placedThisAction++;
        waitingForStepUp = placePos.immutable();
        waitingTicks = 0;
        placeCooldown = PLACE_INTERVAL_TICKS;
        entity.getJumpControl().jump();
        entity.getNavigation().stop();
        if (entity.blockPosition().getY() == yBefore) {
            entity.setPos(entity.getX(), yBefore + 1.0, entity.getZ());
        }

        AICompanionMod.LOGGER.info(
            "[EnsureReachBlock] Pillar block {} at {} for target {}",
            blockItem.getBlock().builtInRegistryHolder().key().location(),
            placePos,
            target
        );
        return ActionResult.IN_PROGRESS;
    }

    @Override
    public int getCost() {
        return 20;
    }

    private boolean canReach(BlockPos pos) {
        return entity.getEyePosition().distanceToSqr(
            pos.getX() + 0.5,
            pos.getY() + 0.5,
            pos.getZ() + 0.5
        ) <= PLAYER_BLOCK_REACH_SQ;
    }

    private int findBlockItem() {
        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack stack = entity.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            String id = stack.getItem().builtInRegistryHolder().key().location().getPath();
            if (id.contains("_ore") || id.contains("ancient_debris") || id.contains("gilded_blackstone")) {
                continue;
            }
            if (id.contains("dirt") || id.contains("cobblestone")
                || id.contains("netherrack") || id.contains("sandstone")
                || id.contains("planks") || id.contains("gravel")
                || id.contains("sand")) {
                return i;
            }
        }

        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack stack = entity.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            String id = stack.getItem().builtInRegistryHolder().key().location().getPath();
            if (id.contains("leaves") || id.contains("leaf")
                || id.contains("_log") || id.contains("_stem")
                || id.endsWith("_wood") || id.endsWith("_hyphae")
                || id.contains("_ore") || id.contains("ancient_debris")
                || id.contains("gilded_blackstone")) {
                continue;
            }
            return i;
        }

        // Last resort for tree chopping: allow harvested logs/planks instead of stalling forever.
        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack stack = entity.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            String id = stack.getItem().builtInRegistryHolder().key().location().getPath();
            if (id.contains("leaves") || id.contains("leaf")
                || id.contains("_ore") || id.contains("ancient_debris")
                || id.contains("gilded_blackstone")) {
                continue;
            }
            return i;
        }
        return -1;
    }

    @Nullable
    private BlockPos choosePillarSpot() {
        BlockPos current = entity.blockPosition();
        BlockState atCurrent = entity.level().getBlockState(current);
        if (isPillarSpace(current, atCurrent)) {
            return current;
        }

        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        int baseY = current.getY();
        for (int[] offset : offsets) {
            BlockPos candidate = new BlockPos(target.getX() + offset[0], baseY, target.getZ() + offset[1]);
            if (isPillarSpace(candidate, entity.level().getBlockState(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isAtPillarSpot(BlockPos spot) {
        double dx = entity.getX() - (spot.getX() + 0.5);
        double dz = entity.getZ() - (spot.getZ() + 0.5);
        return dx * dx + dz * dz <= 0.8 * 0.8;
    }

    private boolean isPillarSpace(BlockPos pos, BlockState stateAtPos) {
        BlockState below = entity.level().getBlockState(pos.below());
        BlockState above = entity.level().getBlockState(pos.above());
        return !below.isAir()
            && below.getBlock().defaultDestroyTime() >= 0
            && (stateAtPos.isAir() || stateAtPos.canBeReplaced())
            && (above.isAir() || above.canBeReplaced());
    }

    @SuppressWarnings("unchecked")
    private List<BlockPos> temporaryBlocks() {
        Object existing = variables.get("$temporary_blocks");
        if (existing instanceof List<?> list) {
            return (List<BlockPos>) list;
        }
        List<BlockPos> created = new ArrayList<>();
        variables.put("$temporary_blocks", created);
        return created;
    }

    @SuppressWarnings("unchecked")
    private List<BlockPos> pillarPath() {
        Object existing = variables.get("$pillar_path");
        if (existing instanceof List<?> list) {
            return (List<BlockPos>) list;
        }
        List<BlockPos> created = new ArrayList<>();
        variables.put("$pillar_path", created);
        return created;
    }
}
