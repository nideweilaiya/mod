package com.aiworkbench.companion.entity.goal;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;

import java.util.*;

/**
 * 自动工具升级器 — 检查背包材料，合成更好的工具并装备。
 * <p>
 * 在采集流程中定期调用 {@link #tryUpgrade(AutomatonEntity)}。
 */
public final class AutoUpgrader {
    private AutoUpgrader() {}

    /** 升级冷却（避免每tick检查） */
    private static final int CHECK_INTERVAL = 200; // 10秒
    private static long lastCheckTick;

    // 升级目标：镐子 → 斧头 → 剑 → 护甲
    private static final List<Item> PICKAXE_TIERS = List.of(
        Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE,
        Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);
    private static final List<Item> AXE_TIERS = List.of(
        Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE,
        Items.DIAMOND_AXE, Items.NETHERITE_AXE);
    private static final List<Item> SWORD_TIERS = List.of(
        Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD,
        Items.DIAMOND_SWORD, Items.NETHERITE_SWORD);

    /**
     * 尝试升级工具。在采集流程中定期调用。
     * @return 升级的物品名，未升级返回 null
     */
    public static String tryUpgrade(AutomatonEntity entity) {
        if (entity.level().isClientSide) return null;
        long now = entity.level().getGameTime();
        if (now - lastCheckTick < CHECK_INTERVAL) return null;
        lastCheckTick = now;

        // 检查当前装备
        ItemStack current = entity.getItemBySlot(EquipmentSlot.MAINHAND);
        int currentPickTier = getTier(current.getItem(), PICKAXE_TIERS);
        int currentAxeTier = getTier(current.getItem(), AXE_TIERS);

        // 尝试升级镐子
        String result = tryUpgradeTool(entity, PICKAXE_TIERS, currentPickTier, "镐");
        if (result != null) return result;

        // 尝试升级斧头
        result = tryUpgradeTool(entity, AXE_TIERS, currentAxeTier, "斧");
        if (result != null) return result;

        // 尝试升级剑
        int currentSwordTier = getTier(current.getItem(), SWORD_TIERS);
        result = tryUpgradeTool(entity, SWORD_TIERS, currentSwordTier, "剑");
        if (result != null) return result;

        return null;
    }

    private static String tryUpgradeTool(AutomatonEntity entity, List<Item> tiers,
                                          int currentTier, String typeName) {
        // 从最高Tier往下尝试——总能拿到最好的
        for (int t = tiers.size() - 1; t > currentTier; t--) {
            Item target = tiers.get(t);
            String result = craftIfPossible(entity, target);
            if (result != null) {
                equipBestInInventory(entity, target.getClass());
                AICompanionMod.LOGGER.info("[AutoUpgrade] Upgraded {} to {} ({})",
                    typeName, target, result);
                entity.showDialogue("§d🔧 升级为" + target.getDescription().getString(), 40);
                return result;
            }
        }
        return null;
    }

    /**
     * 尝试合成指定物品。
     * @return 合成来源描述（如"3铁锭+2木棍"），失败返回null
     */
    private static String craftIfPossible(AutomatonEntity entity, Item target) {
        // 获取合成配方
        List<Ingredient> recipe = getShapedRecipe(target);
        if (recipe == null || recipe.isEmpty()) return null;

        // 检查是否有足够材料
        Map<Integer, Integer> needed = new HashMap<>(); // slot → count needed per ingredient
        int[] consumed = new int[entity.getInventorySize()];

        for (Ingredient ing : recipe) {
            if (ing.isEmpty()) continue;
            boolean found = false;
            for (int i = 0; i < entity.getInventorySize(); i++) {
                ItemStack stack = entity.getItem(i);
                if (stack.isEmpty()) continue;
                int alreadyUsed = consumed[i];
                if (alreadyUsed < stack.getCount() && ing.test(stack)) {
                    consumed[i]++;
                    found = true;
                    break;
                }
            }
            if (!found) return null; // 材料不足
        }

        // 收集材料描述
        StringBuilder desc = new StringBuilder();
        for (Ingredient ing : recipe) {
            if (ing.isEmpty()) continue;
            for (int i = 0; i < entity.getInventorySize(); i++) {
                if (consumed[i] > 0 && ing.test(entity.getItem(i))) {
                    if (desc.length() > 0) desc.append("+");
                    desc.append(entity.getItem(i).getDisplayName().getString());
                    consumed[i]--;
                    break;
                }
            }
        }

        // 消耗材料
        int[] toConsume = new int[entity.getInventorySize()];
        for (Ingredient ing : recipe) {
            if (ing.isEmpty()) continue;
            for (int i = 0; i < entity.getInventorySize(); i++) {
                ItemStack stack = entity.getItem(i);
                if (stack.isEmpty() || !ing.test(stack)) continue;
                stack.shrink(1);
                if (stack.isEmpty()) entity.setItem(i, ItemStack.EMPTY);
                break;
            }
        }

        // 产出成品
        ItemStack result = new ItemStack(target);
        if (!entity.addItemToInventory(result)) {
            entity.spawnAtLocation(result);
        }

        return desc.toString();
    }

    // 任意木板
    private static final Ingredient PLANKS = Ingredient.of(
        Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS,
        Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS,
        Items.CHERRY_PLANKS, Items.BAMBOO_PLANKS, Items.CRIMSON_PLANKS, Items.WARPED_PLANKS);

    /** 获取物品在工作台中合成的材料列表（简化版，只处理镐/斧/剑） */
    private static List<Ingredient> getShapedRecipe(Item target) {
        if (target == Items.WOODEN_PICKAXE || target == Items.WOODEN_AXE || target == Items.WOODEN_SWORD)
            return recipe(PLANKS, PLANKS, PLANKS, Ingredient.of(Items.STICK), Ingredient.of(Items.STICK));
        if (target == Items.STONE_PICKAXE || target == Items.STONE_AXE || target == Items.STONE_SWORD)
            return recipe(Ingredient.of(Items.COBBLESTONE), Ingredient.of(Items.COBBLESTONE),
                         Ingredient.of(Items.COBBLESTONE), Ingredient.of(Items.STICK), Ingredient.of(Items.STICK));
        if (target == Items.IRON_PICKAXE || target == Items.IRON_AXE || target == Items.IRON_SWORD)
            return recipe(Ingredient.of(Items.IRON_INGOT), Ingredient.of(Items.IRON_INGOT),
                         Ingredient.of(Items.IRON_INGOT), Ingredient.of(Items.STICK), Ingredient.of(Items.STICK));
        if (target == Items.DIAMOND_PICKAXE || target == Items.DIAMOND_AXE || target == Items.DIAMOND_SWORD)
            return recipe(Ingredient.of(Items.DIAMOND), Ingredient.of(Items.DIAMOND),
                         Ingredient.of(Items.DIAMOND), Ingredient.of(Items.STICK), Ingredient.of(Items.STICK));
        return null;
    }

    private static List<Ingredient> recipe(Ingredient... ings) {
        return Arrays.asList(ings);
    }

    private static int getTier(Item item, List<Item> tiers) {
        for (int i = 0; i < tiers.size(); i++)
            if (tiers.get(i) == item) return i;
        return -1;
    }

    /**
     * 检查背包中是否有可冶炼的材料，如果冶炼后能解锁工具升级则返回true。
     * 在采集过程中定期调用，发现有机可乘时会自动放置熔炉开始冶炼。
     */
    public static boolean trySmeltIfNeeded(AutomatonEntity entity) {
        if (entity.level().isClientSide) return false;

        // 有矿可烧吗
        boolean hasIronOre = countItem(entity, Items.IRON_ORE) + countItem(entity, Items.RAW_IRON) >= 1;
        boolean hasGoldOre = countItem(entity, Items.GOLD_ORE) + countItem(entity, Items.RAW_GOLD) >= 1;
        if (!hasIronOre && !hasGoldOre) return false;

        // 有燃料吗
        if (countItem(entity, Items.COAL) + countItem(entity, Items.CHARCOAL) == 0)
            return false;

        // 有熔炉吗（背包里或附近）
        BlockPos furnace = findOrPlaceFurnace(entity);
        if (furnace == null) {
            AICompanionMod.LOGGER.info("[AutoUpgrade] Smelt skipped: no furnace available");
            return false;
        }

        // 找到熔炉→放入矿石+燃料
        boolean result = startSmelting(entity, furnace);
        AICompanionMod.LOGGER.info("[AutoUpgrade] Smelt attempt: ore={} coal={} result={}",
            hasIronOre ? "iron" : "gold", countItem(entity, Items.COAL) + countItem(entity, Items.CHARCOAL), result);
        return result;
    }

    private static int countItem(AutomatonEntity entity, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack s = entity.getItem(i);
            if (s.getItem() == item) count += s.getCount();
        }
        return count;
    }

    private static boolean hasEnoughForUpgrade(AutomatonEntity entity, Item material, int needed) {
        return countItem(entity, material) >= needed
            && countItem(entity, Items.STICK) >= 2;
    }

    private static BlockPos findOrPlaceFurnace(AutomatonEntity entity) {
        // 先找背包里的熔炉
        int furnaceSlot = -1;
        for (int i = 0; i < entity.getInventorySize(); i++) {
            if (entity.getItem(i).getItem() == Items.FURNACE) { furnaceSlot = i; break; }
        }

        BlockPos pos = entity.blockPosition();
        // 在脚下放熔炉
        BlockPos place = pos;
        net.minecraft.world.level.Level level = entity.level();
        while (level.getBlockState(place).isAir() && place.getY() > level.getMinBuildHeight() + 1) {
            place = place.below();
        }
        BlockPos furnacePos = place.above();
        if (!level.getBlockState(furnacePos).isAir()) return null;

        if (furnaceSlot >= 0) {
            level.setBlock(furnacePos, Blocks.FURNACE.defaultBlockState(), 3);
            entity.animateBlockPlace(furnacePos);
            entity.getItem(furnaceSlot).shrink(1);
            if (entity.getItem(furnaceSlot).isEmpty())
                entity.setItem(furnaceSlot, ItemStack.EMPTY);
        } else {
            // 合成熔炉（8圆石）
            if (countItem(entity, Items.COBBLESTONE) >= 8) {
                consumeItems(entity, Items.COBBLESTONE, 8);
                level.setBlock(furnacePos, Blocks.FURNACE.defaultBlockState(), 3);
                entity.animateBlockPlace(furnacePos);
                entity.addItemToInventory(new ItemStack(Items.FURNACE));
            } else return null;
        }
        AICompanionMod.LOGGER.info("[AutoUpgrade] Placed furnace at {}", furnacePos);
        entity.showDialogue("§8🔥 放置熔炉", 30);
        return furnacePos;
    }

    private static void consumeItems(AutomatonEntity entity, Item item, int count) {
        int remaining = count;
        for (int i = 0; i < entity.getInventorySize() && remaining > 0; i++) {
            ItemStack s = entity.getItem(i);
            if (s.getItem() == item) {
                int take = Math.min(remaining, s.getCount());
                s.shrink(take);
                remaining -= take;
                if (s.isEmpty()) entity.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    private static boolean startSmelting(AutomatonEntity entity, BlockPos furnacePos) {
        net.minecraft.world.level.Level level = entity.level();
        if (!(level.getBlockState(furnacePos).getBlock() instanceof net.minecraft.world.level.block.FurnaceBlock))
            return false;

        net.minecraft.world.level.block.entity.FurnaceBlockEntity furnace =
            (net.minecraft.world.level.block.entity.FurnaceBlockEntity) level.getBlockEntity(furnacePos);
        if (furnace == null) return false;

        // 放入铁矿石
        int oreSlot = findItemSlot(entity, Items.IRON_ORE, Items.RAW_IRON);
        if (oreSlot < 0) return false;
        ItemStack ore = entity.getItem(oreSlot);
        ItemStack smeltInput = furnace.getItem(0);
        if (!smeltInput.isEmpty()) return false; // 熔炉忙

        furnace.setItem(0, ore.copyWithCount(1));
        ore.shrink(1);
        if (ore.isEmpty()) entity.setItem(oreSlot, ItemStack.EMPTY);

        // 放入燃料
        int fuelSlot = findItemSlot(entity, Items.COAL, Items.CHARCOAL);
        if (fuelSlot < 0) return false;
        ItemStack fuel = entity.getItem(fuelSlot);
        ItemStack smeltFuel = furnace.getItem(1);
        if (!smeltFuel.isEmpty()) {
            // 燃料槽有东西→不覆盖
            entity.showDialogue("§8🔥 冶炼中...", 30);
            return true;
        }

        furnace.setItem(1, fuel.copyWithCount(1));
        fuel.shrink(1);
        if (fuel.isEmpty()) entity.setItem(fuelSlot, ItemStack.EMPTY);

        AICompanionMod.LOGGER.info("[AutoUpgrade] Started smelting iron at {}", furnacePos);
        entity.showDialogue("§8🔥 冶炼铁矿石...", 40);
        return true;
    }

    private static int findItemSlot(AutomatonEntity entity, Item... items) {
        for (Item item : items)
            for (int i = 0; i < entity.getInventorySize(); i++)
                if (entity.getItem(i).getItem() == item) return i;
        return -1;
    }

    /** 从背包中装备最佳匹配类型的工具 */
    private static void equipBestInInventory(AutomatonEntity entity, Class<?> toolClass) {
        ItemStack current = entity.getItemBySlot(EquipmentSlot.MAINHAND);
        int bestSlot = -1, bestTier = -1;

        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack s = entity.getItem(i);
            if (s.isEmpty()) continue;
            if (!toolClass.isInstance(s.getItem())) continue;
            int t = s.getItem() instanceof TieredItem ti ? ti.getTier().getLevel() : 0;
            if (t > bestTier) { bestTier = t; bestSlot = i; }
        }
        if (bestSlot >= 0) {
            ItemStack nt = entity.getItem(bestSlot);
            entity.setItem(bestSlot, current);
            entity.setItemSlot(EquipmentSlot.MAINHAND, nt);
        }
    }
}
