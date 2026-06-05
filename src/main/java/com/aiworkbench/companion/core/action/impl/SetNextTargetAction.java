package com.aiworkbench.companion.core.action.impl;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.core.action.ActionResult;
import com.aiworkbench.companion.core.action.IAction;
import com.aiworkbench.companion.core.action.TreeHarvestCursor;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;

/**
 * Picks the next block target from the capability variables.
 */
public class SetNextTargetAction implements IAction {

    private final AutomatonEntity entity;
    private final Map<String, Object> variables;

    public SetNextTargetAction(AutomatonEntity entity, Map<String, Object> variables) {
        this.entity = entity;
        this.variables = variables;
    }

    @Override
    public boolean canExecute(PerceptionData perception) {
        if (variables == null) {
            return false;
        }
        Object cursor = variables.get("$tree_harvest_cursor");
        if (cursor instanceof TreeHarvestCursor treeCursor && treeCursor.hasRemainingTargets()) {
            return true;
        }
        Object treeCutList = variables.get("$tree_cut_list");
        if (treeCutList instanceof List<?> list && !list.isEmpty()) {
            return true;
        }
        return variables.containsKey("$current_target");
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        if (variables == null) {
            return ActionResult.FAILURE;
        }

        Object cursor = variables.get("$tree_harvest_cursor");
        if (cursor instanceof TreeHarvestCursor treeCursor && treeCursor.hasRemainingTargets()) {
            BlockPos previous = (BlockPos) variables.get("$current_target");
            TreeHarvestCursor.Selection next = treeCursor.advance(entity.level());
            if (next != null && next.target() != null) {
                BlockPos blockPos = next.target();
                variables.put("$current_target", blockPos);
                variables.put("$tree_cut_list", treeCursor.remainingTargets(entity.level()));
                if (next.switchedColumn() && requiresColumnTransition(previous, blockPos) && hasTemporaryScaffold()) {
                    variables.put("$column_transition_required", true);
                    AICompanionMod.LOGGER.info(
                        "[SetNextTarget] Column transition {} -> {}, cleanup required before continuing",
                        previous,
                        blockPos
                    );
                } else {
                    variables.remove("$column_transition_required");
                }
                AICompanionMod.LOGGER.info(
                    "[SetNextTarget] Current target -> {} (column={} remaining={})",
                    blockPos,
                    next.columnIndex(),
                    treeCursor.remainingTargets(entity.level()).size()
                );
                return ActionResult.SUCCESS;
            }
        }

        Object treeCutList = variables.get("$tree_cut_list");
        if (treeCutList instanceof List<?> list && !list.isEmpty()) {
            BlockPos previous = (BlockPos) variables.get("$current_target");
            Object next = list.remove(0);
            if (next instanceof BlockPos blockPos) {
                variables.put("$current_target", blockPos);
                if (requiresColumnTransition(previous, blockPos) && hasTemporaryScaffold()) {
                    variables.put("$column_transition_required", true);
                } else {
                    variables.remove("$column_transition_required");
                }
                AICompanionMod.LOGGER.info("[SetNextTarget] Legacy target -> {} (remaining={})", blockPos, list.size());
                return ActionResult.SUCCESS;
            }
        }

        BlockPos current = (BlockPos) variables.get("$current_target");
        if (current != null) {
            BlockPos next = current.above();
            variables.put("$current_target", next);
            AICompanionMod.LOGGER.info("[SetNextTarget] Fallback target -> {}", next);
            return ActionResult.SUCCESS;
        }

        return ActionResult.FAILURE;
    }

    @Override
    public int getCost() {
        return 1;
    }

    @SuppressWarnings("unchecked")
    private boolean hasTemporaryScaffold() {
        Object temporaryBlocks = variables.get("$temporary_blocks");
        if (temporaryBlocks instanceof List<?> list && !list.isEmpty()) {
            return true;
        }
        Object pillarPath = variables.get("$pillar_path");
        return pillarPath instanceof List<?> list && !list.isEmpty();
    }

    private boolean requiresColumnTransition(BlockPos previous, BlockPos next) {
        return previous != null
            && (previous.getX() != next.getX() || previous.getZ() != next.getZ());
    }
}
