package com.aiworkbench.companion.core.action.impl;

import com.aiworkbench.companion.core.action.ActionResult;
import com.aiworkbench.companion.core.action.IAction;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.entity.AutomatonEntity;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;

/**
 * 将背包中的指定物品切换到主手的动作原语。
 *
 * <p>由评估层根据任务需求调用。例如砍树前需要斧头，挖矿前需要镐子。
 * 如果当前主手已经是目标类型，canExecute 返回 false（无需切换）。</p>
 *
 * <h3>组合示例</h3>
 * <pre>{@code
 *   EquipItemAction("axe") → MoveToAction(nearTree) → BreakBlockAction(treePos)
 * }</pre>
 */
public class EquipItemAction implements IAction {

    private final AutomatonEntity entity;
    private final String itemKeyword; // 匹配物品注册名的关键字，如 "axe", "pickaxe", "sword"
    private boolean executed;

    public EquipItemAction(AutomatonEntity entity, String itemKeyword) {
        this.entity = entity;
        this.itemKeyword = itemKeyword;
    }

    @Override
    public boolean canExecute(PerceptionData perception) {
        // 当前主手已经是目标类型 → 无需执行
        ItemStack held = entity.getEquippedTool();
        if (!held.isEmpty() && matches(held)) return false;

        // 检查背包中是否有匹配物品
        return findInInventory() >= 0;
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        if (executed) return ActionResult.SUCCESS;
        executed = true;

        int slot = findInInventory();
        if (slot < 0) return ActionResult.FAILURE;

        ItemStack currentMain = entity.getItemBySlot(EquipmentSlot.MAINHAND);
        ItemStack target = entity.getInventory().get(slot).copy();

        // 交换：目标物品 → 主手，主手物品 → 背包槽
        entity.setItemSlot(EquipmentSlot.MAINHAND, target);
        entity.getInventory().set(slot, currentMain);

        return ActionResult.SUCCESS;
    }

    @Override
    public int getCost() {
        return 1;
    }

    // ==================== 内部方法 ====================

    private int findInInventory() {
        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack stack = entity.getInventory().get(i);
            if (!stack.isEmpty() && matches(stack)) return i;
        }
        return -1;
    }

    private boolean matches(ItemStack stack) {
        if ("axe".equals(itemKeyword)) {
            return stack.getItem() instanceof AxeItem;
        }
        if ("pickaxe".equals(itemKeyword)) {
            return stack.getItem() instanceof PickaxeItem;
        }
        if ("sword".equals(itemKeyword)) {
            return stack.getItem() instanceof SwordItem;
        }
        String id = stack.getItem().builtInRegistryHolder().key().location().getPath();
        return id.contains(itemKeyword);
    }
}
