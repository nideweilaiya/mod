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
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EnsureReachBlockAction implements IAction {

    private static final double PLAYER_BLOCK_REACH_SQ = 4.5 * 4.5;
    private static final double CLOSE_HORIZONTAL_SQ = 2.8 * 2.8;
    private static final int MAX_TICKS = 240;
    private static final int MAX_PILLAR_BLOCKS = 16;
    private static final int PLACE_INTERVAL_TICKS = 15;

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
        if (target == null || !entity.level().isInWorldBounds(target)) return false;
        BlockState state = entity.level().getBlockState(target);
        return !state.isAir() && state.getBlock().defaultDestroyTime() >= 0;
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        elapsedTicks++;
        if (elapsedTicks > MAX_TICKS) return ActionResult.FAILURE;
        if (entity.level().getBlockState(target).isAir()) return ActionResult.SUCCESS;
        if (canReach(target)) return ActionResult.SUCCESS;

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
            entity.getNavigation().moveTo(wantedX, entity.getY(), wantedZ, 1.0);
            entity.getMoveControl().setWantedPosition(wantedX, entity.getY(), wantedZ, 1.0);
            return ActionResult.IN_PROGRESS;
        }

        if (!entity.onGround() || placeCooldown > 0) return ActionResult.IN_PROGRESS;
        if (placedThisAction >= MAX_PILLAR_BLOCKS) return ActionResult.FAILURE;
        int slot = findBlockItem();
        if (slot < 0) return ActionResult.FAILURE;

        BlockPos placePos = entity.blockPosition();
        BlockState placeState = entity.level().getBlockState(placePos);
        if (!placeState.isAir() && !placeState.canBeReplaced()) {
            return ActionResult.FAILURE;
        }

        ItemStack stack = entity.getItem(slot);
        if (!(stack.getItem() instanceof BlockItem blockItem)) return ActionResult.FAILURE;

        entity.level().setBlock(placePos, blockItem.getBlock().defaultBlockState(), 3);
        stack.shrink(1);
        if (stack.isEmpty()) entity.setItem(slot, ItemStack.EMPTY);
        temporaryBlocks().add(placePos.immutable());
        pathBlocks().add(placePos.immutable());
        placedThisAction++;
        waitingForStepUp = placePos.immutable();
        waitingTicks = 0;
        placeCooldown = PLACE_INTERVAL_TICKS;
        entity.getJumpControl().jump();
        entity.getNavigation().stop();

        AICompanionMod.LOGGER.info("[EnsureReachBlock] Pillar block {} at {} for target {}",
            blockItem.getBlock().builtInRegistryHolder().key().location(), placePos, target);
        return ActionResult.IN_PROGRESS;
    }

    @Override
    public int getCost() {
        return 20;
    }

    private boolean canReach(BlockPos pos) {
        return entity.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= PLAYER_BLOCK_REACH_SQ;
    }

    private int findBlockItem() {
        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack stack = entity.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) continue;
            String id = stack.getItem().builtInRegistryHolder().key().location().getPath();
            if (id.contains("leaves") || id.contains("leaf")) continue;
            return i;
        }
        return -1;
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
    private List<BlockPos> pathBlocks() {
        Object existing = variables.get("$pillar_path");
        if (existing instanceof List<?> list) {
            return (List<BlockPos>) list;
        }
        List<BlockPos> created = new ArrayList<>();
        variables.put("$pillar_path", created);
        return created;
    }
}
