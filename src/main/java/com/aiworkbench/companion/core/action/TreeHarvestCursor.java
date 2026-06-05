package com.aiworkbench.companion.core.action;

import com.aiworkbench.companion.core.perception.TreeColumn;
import com.aiworkbench.companion.core.perception.TreeStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mutable traversal cursor for structured tree harvesting.
 */
public class TreeHarvestCursor {

    private static final int MAX_REPOSITION_ATTEMPTS = 3;

    private final TreeStructure treeStructure;
    private final Map<BlockPos, Integer> repositionFailures = new LinkedHashMap<>();
    private final Set<BlockPos> unreachableTargets = new LinkedHashSet<>();
    private int activeColumnIndex;
    private int activeLogIndex;

    public TreeHarvestCursor(TreeStructure treeStructure) {
        this.treeStructure = treeStructure;
    }

    public TreeStructure treeStructure() {
        return treeStructure;
    }

    public boolean hasRemainingTargets() {
        if (treeStructure == null || treeStructure.isEmpty()) {
            return false;
        }
        for (int columnIndex = activeColumnIndex; columnIndex < treeStructure.trunkColumns().size(); columnIndex++) {
            TreeColumn column = treeStructure.trunkColumns().get(columnIndex);
            int startIndex = columnIndex == activeColumnIndex ? activeLogIndex : 0;
            for (int logIndex = startIndex; logIndex < column.logsBottomUp().size(); logIndex++) {
                if (column.logsBottomUp().get(logIndex) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    public Selection advance(Level level) {
        if (treeStructure == null || treeStructure.isEmpty()) {
            return null;
        }

        while (activeColumnIndex < treeStructure.trunkColumns().size()) {
            TreeColumn column = treeStructure.trunkColumns().get(activeColumnIndex);
            List<BlockPos> logs = column.logsBottomUp();
            if (activeLogIndex < logs.size()) {
                BlockPos next = logs.get(activeLogIndex++);
                if (!isEligibleTarget(level, next)) {
                    continue;
                }
                boolean switchedColumn = activeLogIndex == 1 && activeColumnIndex > 0;
                return new Selection(next, switchedColumn, activeColumnIndex, logs.size() - activeLogIndex);
            }
            activeColumnIndex++;
            activeLogIndex = 0;
        }

        return null;
    }

    public TreeColumn activeColumn() {
        if (treeStructure == null || treeStructure.isEmpty() || activeColumnIndex >= treeStructure.trunkColumns().size()) {
            return null;
        }
        return treeStructure.trunkColumns().get(activeColumnIndex);
    }

    public TreeColumn columnFor(BlockPos pos) {
        return treeStructure == null ? null : treeStructure.findColumn(pos);
    }

    public boolean isSameTreeLog(BlockPos pos) {
        return treeStructure != null && treeStructure.containsLog(pos);
    }

    public boolean isInActiveColumn(BlockPos pos) {
        TreeColumn column = activeColumn();
        return column != null && pos != null && column.x() == pos.getX() && column.z() == pos.getZ();
    }

    public int recordRepositionFailure(BlockPos pos) {
        if (pos == null) {
            return 0;
        }
        int attempts = repositionFailures.getOrDefault(pos, 0) + 1;
        repositionFailures.put(pos.immutable(), attempts);
        if (attempts >= MAX_REPOSITION_ATTEMPTS) {
            unreachableTargets.add(pos.immutable());
        }
        return attempts;
    }

    public boolean shouldAbandonTarget(BlockPos pos) {
        return pos != null && unreachableTargets.contains(pos);
    }

    public void markUnreachable(BlockPos pos) {
        if (pos == null) {
            return;
        }
        repositionFailures.put(pos.immutable(), MAX_REPOSITION_ATTEMPTS);
        unreachableTargets.add(pos.immutable());
    }

    public void clearFailureState(BlockPos pos) {
        if (pos == null) {
            return;
        }
        repositionFailures.remove(pos);
        unreachableTargets.remove(pos);
    }

    public List<BlockPos> remainingTargets() {
        return remainingTargets(null);
    }

    public List<BlockPos> remainingTargets(Level level) {
        List<BlockPos> remaining = new ArrayList<>();
        if (treeStructure == null || treeStructure.isEmpty()) {
            return remaining;
        }
        for (int columnIndex = activeColumnIndex; columnIndex < treeStructure.trunkColumns().size(); columnIndex++) {
            TreeColumn column = treeStructure.trunkColumns().get(columnIndex);
            int startIndex = columnIndex == activeColumnIndex ? activeLogIndex : 0;
            List<BlockPos> logs = column.logsBottomUp();
            for (int logIndex = startIndex; logIndex < logs.size(); logIndex++) {
                BlockPos pos = logs.get(logIndex);
                if (isEligibleTarget(level, pos)) {
                    remaining.add(pos);
                }
            }
        }
        return remaining;
    }

    private boolean isEligibleTarget(Level level, BlockPos pos) {
        if (pos == null || unreachableTargets.contains(pos)) {
            return false;
        }
        if (level == null) {
            return true;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        String path = state.getBlock().builtInRegistryHolder().key().location().getPath();
        return path.contains("log") || path.contains("stem") || path.contains("hyphae");
    }

    public record Selection(BlockPos target, boolean switchedColumn, int columnIndex, int remainingInColumn) {}
}
