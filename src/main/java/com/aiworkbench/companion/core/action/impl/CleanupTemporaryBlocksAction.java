package com.aiworkbench.companion.core.action.impl;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.core.action.ActionResult;
import com.aiworkbench.companion.core.action.IAction;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class CleanupTemporaryBlocksAction implements IAction {

    private static final double CLEANUP_REACH_SQ = 5.0 * 5.0;
    private static final double COLUMN_EPSILON = 0.45;
    private static final double RECOVERY_MOVE_SPEED = 1.2;
    private static final int BREAK_INTERVAL_TICKS = 6;
    private static final float TICKS_PER_HARDNESS = 30f;

    private final AutomatonEntity entity;
    private final Map<String, Object> variables;

    private int breakCooldownTicks;
    private BlockPos breakingPos;
    private float breakingProgress;
    private int breakingTicks;

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
        if (breakCooldownTicks > 0) {
            breakCooldownTicks--;
            return ActionResult.IN_PROGRESS;
        }

        List<BlockPos> blocks = temporaryBlocks();
        if (blocks.isEmpty()) {
            clearBreakProgress();
            return ActionResult.SUCCESS;
        }

        BlockPos pos = selectRecoveryBlock(blocks, pillarPath());
        if (pos == null) {
            clearBreakProgress();
            blocks.clear();
            pillarPath().clear();
            return ActionResult.SUCCESS;
        }

        BlockState state = entity.level().getBlockState(pos);
        if (state.isAir()) {
            clearBreakProgress();
            removeRecovered(pos);
            return ActionResult.IN_PROGRESS;
        }

        if (!isReadyToRecover(pos)) {
            clearBreakProgress();
            moveTowardRecoveryAnchor(pos);
            return ActionResult.IN_PROGRESS;
        }

        if (!entity.onGround()) {
            return ActionResult.IN_PROGRESS;
        }

        if (breakingPos == null || !breakingPos.equals(pos)) {
            clearBreakProgress();
            breakingPos = pos.immutable();
        }

        if (!mineOneStep(pos, state)) {
            return ActionResult.IN_PROGRESS;
        }

        removeRecovered(pos);
        breakCooldownTicks = BREAK_INTERVAL_TICKS;
        AICompanionMod.LOGGER.info("[CleanupTemporaryBlocks] Recovered scaffold at {}", pos);
        return ActionResult.IN_PROGRESS;
    }

    @Override
    public int getCost() {
        return 5;
    }

    private boolean mineOneStep(BlockPos pos, BlockState state) {
        entity.getNavigation().stop();
        entity.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        entity.swing(InteractionHand.MAIN_HAND);

        float hardness = state.getBlock().defaultDestroyTime();
        if (hardness < 0) {
            hardness = 50f;
        }
        float toolSpeed = entity.getEffectiveDigSpeed(state);
        breakingProgress += toolSpeed / (hardness * TICKS_PER_HARDNESS);
        breakingTicks++;

        Level level = entity.level();
        if (level instanceof ServerLevel serverLevel && breakingTicks % 4 == 0) {
            int crackStage = Math.min((int) (breakingProgress * 10), 9);
            serverLevel.destroyBlockProgress(entity.getId(), pos, crackStage);
        }

        if (breakingProgress < 1.0f) {
            return false;
        }

        if (level instanceof ServerLevel serverLevel) {
            serverLevel.destroyBlockProgress(entity.getId(), pos, -1);
            serverLevel.destroyBlock(pos, true, entity);
        } else {
            level.destroyBlock(pos, true, entity);
        }
        clearBreakProgress();
        return true;
    }

    private boolean isReadyToRecover(BlockPos pos) {
        return isAlignedAbove(pos)
            && entity.getEyePosition().distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= CLEANUP_REACH_SQ;
    }

    private boolean isAlignedAbove(BlockPos pos) {
        return Math.abs(entity.getX() - (pos.getX() + 0.5)) <= COLUMN_EPSILON
            && Math.abs(entity.getZ() - (pos.getZ() + 0.5)) <= COLUMN_EPSILON
            && entity.getY() >= pos.getY();
    }

    private void moveTowardRecoveryAnchor(BlockPos pos) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 1.0;
        double z = pos.getZ() + 0.5;
        entity.getNavigation().moveTo(x, y, z, RECOVERY_MOVE_SPEED);
        entity.getMoveControl().setWantedPosition(x, y, z, RECOVERY_MOVE_SPEED);
    }

    private BlockPos selectRecoveryBlock(List<BlockPos> blocks, List<BlockPos> path) {
        for (int i = path.size() - 1; i >= 0; i--) {
            BlockPos pos = path.get(i);
            if (blocks.contains(pos)) {
                return pos;
            }
        }
        return blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
    }

    private void removeRecovered(BlockPos pos) {
        temporaryBlocks().remove(pos);
        pillarPath().remove(pos);
    }

    private void clearBreakProgress() {
        if (breakingPos != null && entity.level() instanceof ServerLevel serverLevel) {
            serverLevel.destroyBlockProgress(entity.getId(), breakingPos, -1);
        }
        breakingPos = null;
        breakingProgress = 0.0f;
        breakingTicks = 0;
    }

    @SuppressWarnings("unchecked")
    private List<BlockPos> temporaryBlocks() {
        Object existing = variables.get("$temporary_blocks");
        if (existing instanceof List<?> list) {
            return (List<BlockPos>) list;
        }
        return Collections.emptyList();
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
