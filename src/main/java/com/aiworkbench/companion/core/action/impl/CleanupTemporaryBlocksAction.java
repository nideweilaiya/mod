package com.aiworkbench.companion.core.action.impl;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.core.action.ActionResult;
import com.aiworkbench.companion.core.action.IAction;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.entity.AutomatonEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

public class CleanupTemporaryBlocksAction implements IAction {

    private static final double CLEANUP_REACH_SQ = 5.0 * 5.0;
    private static final double SAME_COLUMN_CENTER_EPSILON = 0.45;

    private final AutomatonEntity entity;
    private final Map<String, Object> variables;

    public CleanupTemporaryBlocksAction(AutomatonEntity entity, Map<String, Object> variables) {
        this.entity = entity;
        this.variables = variables;
    }

    @Override
    public boolean canExecute(PerceptionData perception) {
        return !temporaryBlocks().isEmpty();
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        List<BlockPos> blocks = temporaryBlocks();
        while (!blocks.isEmpty()) {
            BlockPos pos = blocks.get(blocks.size() - 1);
            BlockState state = entity.level().getBlockState(pos);
            if (state.isAir()) {
                blocks.remove(blocks.size() - 1);
                removeFromPath(pos);
                continue;
            }

            if (entity.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > CLEANUP_REACH_SQ) {
                moveTowardTopOf(pos);
                return ActionResult.IN_PROGRESS;
            }

            if (!sameColumn(pos)) {
                moveTowardTopOf(pos);
                return ActionResult.IN_PROGRESS;
            }
            breakBlockAsDrop(pos);
            blocks.remove(blocks.size() - 1);
            removeFromPath(pos);
            AICompanionMod.LOGGER.info("[CleanupTemporaryBlocks] Recovered scaffold at {}", pos);
            return ActionResult.IN_PROGRESS;
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public int getCost() {
        return 5;
    }

    private void breakBlockAsDrop(BlockPos pos) {
        Level level = entity.level();
        level.destroyBlock(pos, true, entity);
    }

    private boolean sameColumn(BlockPos pos) {
        return Math.abs(entity.getX() - (pos.getX() + 0.5)) <= SAME_COLUMN_CENTER_EPSILON
            && Math.abs(entity.getZ() - (pos.getZ() + 0.5)) <= SAME_COLUMN_CENTER_EPSILON
            && entity.getY() >= pos.getY();
    }

    private void moveTowardTopOf(BlockPos pos) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 1.0;
        double z = pos.getZ() + 0.5;
        entity.getNavigation().moveTo(x, y, z, 1.0);
        entity.getMoveControl().setWantedPosition(x, y, z, 1.0);
    }

    @SuppressWarnings("unchecked")
    private List<BlockPos> temporaryBlocks() {
        Object existing = variables.get("$temporary_blocks");
        if (existing instanceof List<?> list) {
            return (List<BlockPos>) list;
        }
        return java.util.Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private void removeFromPath(BlockPos pos) {
        Object existing = variables.get("$pillar_path");
        if (existing instanceof List<?> list) {
            ((List<BlockPos>) list).remove(pos);
        }
    }
}
