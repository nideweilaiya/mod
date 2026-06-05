package com.aiworkbench.companion.building;

import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.*;

/**
 * 材料采集器 —— 计算蓝图缺料，将采集任务入队。
 */
public class MaterialGatherer {

    /**
     * 检查背包中是否有足够材料建造蓝图。
     * @return 缺料清单（方块 → 缺少数量），空 map 表示材料充足
     */
    public static Map<Block, Integer> checkMissing(AutomatonEntity entity, BuildingBlueprint blueprint) {
        Map<Block, Integer> needed = new LinkedHashMap<>(blueprint.getMaterialCounts());
        Map<Block, Integer> available = countInventory(entity);

        Map<Block, Integer> missing = new LinkedHashMap<>();
        for (Map.Entry<Block, Integer> e : needed.entrySet()) {
            int have = available.getOrDefault(e.getKey(), 0);
            int need = e.getValue();
            if (have < need) {
                missing.put(e.getKey(), need - have);
            }
        }
        return missing;
    }

    /** 统计背包中的方块数量（按类型） */
    private static Map<Block, Integer> countInventory(AutomatonEntity entity) {
        Map<Block, Integer> counts = new HashMap<>();
        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack stack = entity.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() instanceof BlockItem bi) {
                counts.merge(bi.getBlock(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /**
     * 为缺料生成可读描述。
     */
    public static String describeMissing(Map<Block, Integer> missing) {
        if (missing.isEmpty()) return "§a材料充足";
        StringBuilder sb = new StringBuilder("§e缺料:\n");
        for (Map.Entry<Block, Integer> e : missing.entrySet()) {
            String name = e.getKey().getName().getString();
            sb.append("§7  ").append(name).append(" x").append(e.getValue()).append("\n");
        }
        return sb.toString();
    }
}
