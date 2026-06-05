package com.aiworkbench.companion.core.perception;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One trunk column inside a scanned tree structure.
 */
public class TreeColumn {

    private final int x;
    private final int z;
    private final List<BlockPos> logsBottomUp;

    public TreeColumn(int x, int z, List<BlockPos> logsBottomUp) {
        this.x = x;
        this.z = z;
        this.logsBottomUp = new ArrayList<>(logsBottomUp);
        this.logsBottomUp.sort(Comparator.comparingInt(BlockPos::getY));
    }

    public int x() {
        return x;
    }

    public int z() {
        return z;
    }

    public BlockPos base() {
        return logsBottomUp.isEmpty() ? new BlockPos(x, 0, z) : logsBottomUp.get(0);
    }

    public List<BlockPos> logsBottomUp() {
        return new ArrayList<>(logsBottomUp);
    }

    public boolean contains(BlockPos pos) {
        return pos != null && pos.getX() == x && pos.getZ() == z && logsBottomUp.contains(pos);
    }
}
