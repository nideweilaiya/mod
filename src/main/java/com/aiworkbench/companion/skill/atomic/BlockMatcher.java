package com.aiworkbench.companion.skill.atomic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 方块匹配器 —— 用于原子操作中查找目标方块。
 */
@FunctionalInterface
public interface BlockMatcher {

    /** 判断指定位置的方块是否匹配 */
    boolean matches(Level level, BlockPos pos);

    /** 方块 ID 完全匹配 */
    static BlockMatcher exact(String blockId) {
        return (level, pos) -> {
            BlockState state = level.getBlockState(pos);
            String name = state.getBlock().builtInRegistryHolder().key().location().getPath();
            return name.equals(blockId);
        };
    }

    /** 方块 ID 包含指定字符串（如 "log" 匹配 oak_log, spruce_log 等） */
    static BlockMatcher contains(String infix) {
        return (level, pos) -> {
            if (level.isOutsideBuildHeight(pos)) return false;
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) return false;
            String name = state.getBlock().builtInRegistryHolder().key().location().getPath();
            return name.contains(infix);
        };
    }

    /** 忽略空气和液体 */
    static boolean isSolid(Level level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.liquid();
    }
}
