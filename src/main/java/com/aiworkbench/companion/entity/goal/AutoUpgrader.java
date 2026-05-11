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
        net.minecraft.world.level.Level level = entity.level();
        BlockPos origin = entity.blockPosition();

        // 1. 扫描附近16格内已有熔炉（包括自己之前放的）
        for (int dx = -16; dx <= 16; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -16; dz <= 16; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (level.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.FurnaceBlock) {
                        AICompanionMod.LOGGER.info("[AutoUpgrade] Using existing furnace at {}", p);
                        return p.immutable();
                    }
                }
            }
        }

        // 2. 背包里有熔炉→放到合适位置
        if (countItem(entity, Items.FURNACE) >= 1) {
            BlockPos placePos = findSolidGround(entity, origin);
            if (placePos != null) {
                level.setBlock(placePos, Blocks.FURNACE.defaultBlockState(), 3);
                entity.animateBlockPlace(placePos);
                consumeOneItem(entity, Items.FURNACE);
                AICompanionMod.LOGGER.info("[AutoUpgrade] Placed furnace at {}", placePos);
                entity.showDialogue("§8🔥 放置熔炉", 30);
                return placePos;
            }
        }

        // 3. 没有熔炉→检查是否有工作台+圆石来合成（遵循原版规则）
        if (hasCraftingTable(entity) && countItem(entity, Items.COBBLESTONE) >= 8) {
            consumeItems(entity, Items.COBBLESTONE, 8);
            entity.addItemToInventory(new ItemStack(Items.FURNACE));
            entity.animateSwing();
            AICompanionMod.LOGGER.info("[AutoUpgrade] Crafted furnace using crafting table (8 cobblestone)");
            entity.showDialogue("§8🔧 合成熔炉", 30);
            // 递归调用自己→现在背包有熔炉了
            return findOrPlaceFurnace(entity);
        }

        AICompanionMod.LOGGER.info("[AutoUpgrade] No furnace: need crafting table + 8 cobblestone");
        return null;
    }

    /** 检查背包或附近是否有工作台 */
    private static boolean hasCraftingTable(AutomatonEntity entity) {
        // 检查背包
        if (countItem(entity, Items.CRAFTING_TABLE) >= 1) return true;
        // 检查附近方块
        net.minecraft.world.level.Level level = entity.level();
        BlockPos origin = entity.blockPosition();
        for (int dx = -8; dx <= 8; dx++)
            for (int dy = -2; dy <= 2; dy++)
                for (int dz = -8; dz <= 8; dz++)
                    if (level.getBlockState(origin.offset(dx, dy, dz)).getBlock() == Blocks.CRAFTING_TABLE)
                        return true;
        return false;
    }

    /** 找合适的放置位置（实体地面，非农田/作物） */
    private static BlockPos findSolidGround(AutomatonEntity entity, BlockPos origin) {
        net.minecraft.world.level.Level level = entity.level();
        BlockPos place = origin;
        while (level.getBlockState(place).isAir() && place.getY() > level.getMinBuildHeight() + 1)
            place = place.below();
        BlockPos above = place.above();
        if (!level.getBlockState(above).isAir()) {
            above = origin.east().above();
            if (!level.getBlockState(above).isAir()) return null;
        }
        if (level.getBlockState(place).getBlock() instanceof net.minecraft.world.level.block.FarmBlock) return null;
        return above;
    }

    /** 消耗单个物品 */
    private static void consumeOneItem(AutomatonEntity entity, Item item) {
        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack s = entity.getItem(i);
            if (s.getItem() == item) {
                s.shrink(1);
                if (s.isEmpty()) entity.setItem(i, ItemStack.EMPTY);
                return;
            }
        }
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

        // 放入矿石（一次放满：最多64个）
        ItemStack smeltInput = furnace.getItem(0);
        if (!smeltInput.isEmpty()) return false; // 熔炉忙

        int oreSlot = findItemSlot(entity, Items.IRON_ORE, Items.RAW_IRON);
        if (oreSlot < 0) return false;
        ItemStack ore = entity.getItem(oreSlot);
        int putCount = Math.min(ore.getCount(), 64);
        furnace.setItem(0, ore.copyWithCount(putCount));
        ore.shrink(putCount);
        if (ore.isEmpty()) entity.setItem(oreSlot, ItemStack.EMPTY);

        // 放入燃料（1煤=8矿，按比例放）
        int fuelSlot = findItemSlot(entity, Items.COAL, Items.CHARCOAL);
        if (fuelSlot < 0) return false;
        ItemStack fuel = entity.getItem(fuelSlot);
        ItemStack smeltFuel = furnace.getItem(1);
        if (!smeltFuel.isEmpty()) {
            // 燃料槽有东西→不覆盖
            entity.showDialogue("§8🔥 冶炼中...", 30);
            return true;
        }

        int fuelNeeded = Math.max(1, (putCount + 7) / 8);
        int fuelPut = Math.min(fuel.getCount(), fuelNeeded);
        furnace.setItem(1, fuel.copyWithCount(fuelPut));
        fuel.shrink(fuelPut);
        if (fuel.isEmpty()) entity.setItem(fuelSlot, ItemStack.EMPTY);

        AICompanionMod.LOGGER.info("[AutoUpgrade] Started smelting: {} ore + {} fuel at {}", putCount, fuelPut, furnacePos);
        entity.showDialogue("§8🔥 冶炼" + putCount + "个矿石...", 40);
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
