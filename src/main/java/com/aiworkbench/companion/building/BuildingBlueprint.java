package com.aiworkbench.companion.building;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.*;

/**
 * 建筑蓝图 —— 定义结构的相对方块布局和材料清单。
 * <p>
 * 每个蓝图层由 BlockPlacement 列表组成，每个 BlockPlacement 指定相对坐标+方块类型。
 * 支持多材料混合建造（如瞭望塔：木板+栅栏+火把+梯子）。
 */
public class BuildingBlueprint {

    public final String name;
    public final int width, height, depth;
    public final List<String> tags;
    private final List<BlockPlacement> placements;
    /** 材料清单：方块 → 数量 */
    private final Map<Block, Integer> materialCounts;

    public BuildingBlueprint(String name, int width, int height, int depth, List<String> tags) {
        this.name = name;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
        this.placements = new ArrayList<>();
        this.materialCounts = new LinkedHashMap<>();
    }

    /** 添加一个方块放置步骤 */
    public BuildingBlueprint add(int x, int y, int z, Block block) {
        placements.add(new BlockPlacement(new BlockPos(x, y, z), block));
        materialCounts.merge(block, 1, Integer::sum);
        return this;
    }

    /** 获取所有放置步骤（按添加顺序） */
    public List<BlockPlacement> getPlacements() { return Collections.unmodifiableList(placements); }

    /** 获取材料清单 */
    public Map<Block, Integer> getMaterialCounts() { return Collections.unmodifiableMap(materialCounts); }

    /** 总方块数 */
    public int totalBlocks() { return placements.size(); }

    @Override
    public String toString() {
        return String.format("Blueprint{%s %dx%dx%d, %d blocks, %d materials}",
            name, width, height, depth, placements.size(), materialCounts.size());
    }

    // ==================== 数据类型 ====================

    public record BlockPlacement(BlockPos relativePos, Block block) {}
}
