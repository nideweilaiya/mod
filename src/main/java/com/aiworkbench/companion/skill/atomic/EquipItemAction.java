package com.aiworkbench.companion.skill.atomic;

import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.AtomicAction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * 从背包中查找并装备匹配的物品到主手。
 * <p>
 * 根据过滤条件匹配背包中的物品，将最优的物品装备到主手。
 * 单 tick 完成。
 */
public class EquipItemAction implements AtomicAction {

    private final Predicate<ItemStack> filter;
    private boolean done;

    public EquipItemAction(Predicate<ItemStack> filter) {
        this.filter = filter;
        this.done = false;
    }

    /**
     * 装备背包中最佳的武器（优先剑，其次工具）。
     */
    public static EquipItemAction bestWeapon() {
        return new EquipItemAction(EquipItemAction::isWeaponOrTool);
    }

    /**
     * 装备匹配指定方块类型的最佳挖掘工具。
     *
     * @param blockId 方块 ID 包含的字符串（如 "stone", "log"）
     */
    public static EquipItemAction bestToolFor(String blockId) {
        return new EquipItemAction(EquipItemAction::isDiggingTool);
    }

    private static boolean isWeaponOrTool(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getItem() instanceof SwordItem
            || stack.getItem() instanceof DiggerItem;
    }

    private static boolean isDiggingTool(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getItem() instanceof DiggerItem;
    }

    @Override
    public boolean canStart(AutomatonEntity entity) {
        return true;
    }

    @Override
    public boolean tick(AutomatonEntity entity) {
        if (done) return true;

        ItemStack currentMainHand = entity.getEquippedTool();

        // 如果手中已有匹配物品，跳过
        if (!currentMainHand.isEmpty() && filter.test(currentMainHand)) {
            done = true;
            return true;
        }

        // 搜索背包，找第一个匹配物品
        ItemStack bestItem = ItemStack.EMPTY;
        int bestSlot = -1;

        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack stack = entity.getItem(i);
            if (stack.isEmpty() || !filter.test(stack)) continue;

            // 优先选剑（更高攻击力），其次是工具
            int priority = 0;
            if (stack.getItem() instanceof SwordItem) priority = 2;
            else if (stack.getItem() instanceof DiggerItem) priority = 1;

            if (bestItem.isEmpty() || priority > getItemPriority(bestItem)) {
                bestItem = stack;
                bestSlot = i;
            }
        }

        // 找到物品，交换到主手
        if (!bestItem.isEmpty() && bestSlot >= 0) {
            entity.setItem(bestSlot, currentMainHand);
            entity.setItemSlot(EquipmentSlot.MAINHAND, bestItem);
        }

        done = true;
        return true;
    }

    private int getItemPriority(ItemStack stack) {
        if (stack.getItem() instanceof SwordItem) return 2;
        if (stack.getItem() instanceof DiggerItem) return 1;
        return 0;
    }

    @Override
    public void stop(AutomatonEntity entity) {
        done = false;
    }

    @Override
    public String getDescription() {
        return "装备物品";
    }
}
