package com.aiworkbench.companion.skill.atomic;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.AtomicAction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * 合成物品 —— 从背包中查找配方所需材料，消耗后产出成品。
 * <p>
 * 使用世界的 RecipeManager 匹配配方，支持所有工作台配方（无需工作台方块）。
 * 目前版本简化处理：不要求形状精确匹配，只检查材料数量和类型是否足够。
 */
public class CraftItemAction implements AtomicAction {

    /** 允许忽略的 "空" 标签 - 配方中 Ingredients 为空说明该位置不需要物品 */
    private static final float TOOLTIP_RADIUS = 4.0f;

    private final Item targetItem;
    private final int craftCount;
    private int remainingCrafts;
    private boolean done;

    /**
     * @param targetItem 要合成的物品（如 Items.STICK）
     * @param craftCount 合成次数
     */
    public CraftItemAction(Item targetItem, int craftCount) {
        this.targetItem = targetItem;
        this.craftCount = Math.max(craftCount, 1);
        this.remainingCrafts = this.craftCount;
        this.done = false;
    }

    /**
     * 合成单个物品。
     */
    public static CraftItemAction craftOnce(Item item) {
        return new CraftItemAction(item, 1);
    }

    /**
     * 合成多个物品。
     */
    public static CraftItemAction craftMultiple(Item item, int count) {
        return new CraftItemAction(item, count);
    }

    @Override
    public boolean canStart(AutomatonEntity entity) {
        return true;
    }

    @Override
    public boolean tick(AutomatonEntity entity) {
        if (done || remainingCrafts <= 0) return true;

        Level level = entity.level();
        if (level.isClientSide) {
            done = true;
            return true;
        }

        RecipeManager recipeManager = level.getRecipeManager();
        boolean craftedThisTick = false;

        // 遍历所有合成配方，找到第一个匹配目标物品的
        for (var holder : recipeManager.getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            ItemStack result = recipe.getResultItem(level.registryAccess());
            if (result.getItem() != targetItem) continue;

            // 尝试合成
            if (tryCraft(entity, recipe)) {
                remainingCrafts--;
                craftedThisTick = true;

                if (remainingCrafts > 0) {
                    return false; // 继续合成
                }
                done = true;
                return true;
            }
        }

        // 没有找到可合成的配方
        if (!craftedThisTick) {
            AICompanionMod.LOGGER.info("[CraftItem] No craftable recipe found for {}", targetItem);
        }
        done = true;
        return true;
    }

    /**
     * 尝试合成指定配方。
     * 检查背包是否有足够材料，如果有则消耗材料并产出成品。
     */
    private boolean tryCraft(AutomatonEntity entity, CraftingRecipe recipe) {
        int inventorySize = entity.getInventorySize();

        // 建立可用材料追踪表：slot -> 剩余可用数量
        Map<Integer, Integer> available = new HashMap<>();
        for (int i = 0; i < inventorySize; i++) {
            ItemStack stack = entity.getItem(i);
            if (!stack.isEmpty()) {
                available.put(i, stack.getCount());
            }
        }

        // 需要消耗的材料记录：slot -> 消耗数量
        Map<Integer, Integer> toConsume = new HashMap<>();

        // 遍历配方所需的每个材料位置
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;

            boolean found = false;
            for (Map.Entry<Integer, Integer> entry : available.entrySet()) {
                if (entry.getValue() <= 0) continue;

                ItemStack stack = entity.getItem(entry.getKey());
                if (ingredient.test(stack)) {
                    // 消耗一个
                    entry.setValue(entry.getValue() - 1);
                    toConsume.merge(entry.getKey(), 1, Integer::sum);
                    found = true;
                    break;
                }
            }

            if (!found) {
                return false; // 材料不足
            }
        }

        // 执行材料消耗
        for (Map.Entry<Integer, Integer> entry : toConsume.entrySet()) {
            int slot = entry.getKey();
            int count = entry.getValue();
            ItemStack stack = entity.getItem(slot);
            stack.shrink(count);
            if (stack.isEmpty()) {
                entity.setItem(slot, ItemStack.EMPTY);
            }
        }

        // 产出成品
        Level level = entity.level();
        ItemStack result = recipe.getResultItem(level.registryAccess()).copy();
        if (!result.isEmpty()) {
            AICompanionMod.LOGGER.info("[CraftItem] Crafted {} x{}", result.getItem(), result.getCount());
            // 先尝试放入背包，满了就掉落在地上
            if (!entity.addItemToInventory(result)) {
                entity.spawnAtLocation(result);
            }
            entity.animateSwing();
        }

        return true;
    }

    @Override
    public void stop(AutomatonEntity entity) {
        done = true;
    }

    @Override
    public void reset() {
        remainingCrafts = craftCount;
        done = false;
    }

    @Override
    public String getDescription() {
        return "合成物品";
    }
}
