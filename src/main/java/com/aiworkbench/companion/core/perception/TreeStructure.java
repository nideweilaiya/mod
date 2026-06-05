package com.aiworkbench.companion.core.perception;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structured tree scan result used by harvesting logic.
 */
public class TreeStructure {

    private final BlockPos treeAnchor;
    private final List<TreeColumn> trunkColumns;
    private final Set<BlockPos> allLogBlocks;
    private final Set<BlockPos> allLeafBlocks;

    public TreeStructure(
        BlockPos treeAnchor,
        List<TreeColumn> trunkColumns,
        Set<BlockPos> allLogBlocks,
        Set<BlockPos> allLeafBlocks
    ) {
        this.treeAnchor = treeAnchor != null ? treeAnchor.immutable() : null;
        this.trunkColumns = new ArrayList<>(trunkColumns);
        this.allLogBlocks = new LinkedHashSet<>(allLogBlocks);
        this.allLeafBlocks = new LinkedHashSet<>(allLeafBlocks);
    }

    public BlockPos treeAnchor() {
        return treeAnchor;
    }

    public List<TreeColumn> trunkColumns() {
        return new ArrayList<>(trunkColumns);
    }

    public Set<BlockPos> allLogBlocks() {
        return new LinkedHashSet<>(allLogBlocks);
    }

    public Set<BlockPos> allLeafBlocks() {
        return new LinkedHashSet<>(allLeafBlocks);
    }

    public boolean isEmpty() {
        return trunkColumns.isEmpty() || allLogBlocks.isEmpty();
    }

    public boolean containsLog(BlockPos pos) {
        return pos != null && allLogBlocks.contains(pos);
    }

    public TreeColumn findColumn(BlockPos pos) {
        if (pos == null) {
            return null;
        }
        for (TreeColumn column : trunkColumns) {
            if (column.x() == pos.getX() && column.z() == pos.getZ()) {
                return column;
            }
        }
        return null;
    }

    public List<BlockPos> flattenColumnsBottomUp() {
        List<BlockPos> flattened = new ArrayList<>();
        for (TreeColumn column : trunkColumns) {
            flattened.addAll(column.logsBottomUp());
        }
        return flattened;
    }

    public static TreeStructure fromLogs(
        BlockPos anchor,
        Collection<BlockPos> logs,
        Collection<BlockPos> leaves,
        BlockPos origin
    ) {
        if (logs == null || logs.isEmpty()) {
            return new TreeStructure(anchor, List.of(), Set.of(), Set.of());
        }

        Map<String, List<BlockPos>> grouped = new LinkedHashMap<>();
        for (BlockPos log : logs) {
            String key = log.getX() + ":" + log.getZ();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(log.immutable());
        }

        List<TreeColumn> columns = new ArrayList<>();
        for (List<BlockPos> group : grouped.values()) {
            group.sort(Comparator.comparingInt(BlockPos::getY));
            BlockPos base = group.get(0);
            columns.add(new TreeColumn(base.getX(), base.getZ(), group));
        }

        columns.sort((a, b) -> {
            boolean aAnchorColumn = anchor != null && a.x() == anchor.getX() && a.z() == anchor.getZ();
            boolean bAnchorColumn = anchor != null && b.x() == anchor.getX() && b.z() == anchor.getZ();
            if (aAnchorColumn != bAnchorColumn) {
                return aAnchorColumn ? -1 : 1;
            }

            double aDist = horizontalDistanceSq(origin, a.base());
            double bDist = horizontalDistanceSq(origin, b.base());
            int distCmp = Double.compare(aDist, bDist);
            if (distCmp != 0) {
                return distCmp;
            }

            int xCmp = Integer.compare(a.x(), b.x());
            if (xCmp != 0) {
                return xCmp;
            }
            return Integer.compare(a.z(), b.z());
        });

        Set<BlockPos> normalizedLogs = new LinkedHashSet<>();
        for (BlockPos log : logs) {
            normalizedLogs.add(log.immutable());
        }
        Set<BlockPos> normalizedLeaves = new LinkedHashSet<>();
        if (leaves != null) {
            for (BlockPos leaf : leaves) {
                normalizedLeaves.add(leaf.immutable());
            }
        }

        return new TreeStructure(
            anchor,
            columns,
            normalizedLogs,
            normalizedLeaves
        );
    }

    private static double horizontalDistanceSq(BlockPos origin, BlockPos pos) {
        if (origin == null || pos == null) {
            return 0.0;
        }
        long dx = (long) pos.getX() - origin.getX();
        long dz = (long) pos.getZ() - origin.getZ();
        return (double) dx * dx + (double) dz * dz;
    }
}
