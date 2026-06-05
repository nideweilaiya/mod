package com.aiworkbench.companion.core.action.impl;

import com.aiworkbench.companion.core.action.ActionResult;
import com.aiworkbench.companion.core.action.IAction;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.entity.AutomatonEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 使用手中物品的动作原语（右键）。
 *
 * <p>覆盖多种使用场景：
 * <ul>
 *   <li>空手或无目标 → 尝试对面前方块使用（如打开熔炉、工作台）</li>
 *   <li>手持方块 → 不在此处理（由 PlaceBlockAction 负责）</li>
 *   <li>手持工具/物品 → 在目标方块上使用（如锄头耕地、桶装水）</li>
 * </ul>
 *
 * <h3>组合示例</h3>
 * <pre>{@code
 *   // 使用熔炉
 *   MoveToAction(nearFurnace) → UseItemAction(furnacePos)
 *
 *   // 钓鱼
 *   EquipItemAction("fishing_rod") → UseItemAction(waterPos)
 *
 *   // 剪羊毛
 *   EquipItemAction("shears") → InteractEntityAction(sheep)
 * }</pre>
 */
public class UseItemAction implements IAction {

    private final AutomatonEntity entity;
    private final BlockPos target; // null = 对空气使用

    private boolean used;

    public UseItemAction(AutomatonEntity entity, BlockPos target) {
        this.entity = entity;
        this.target = target;
    }

    /** 对空气使用（如抛竿） */
    public static UseItemAction onAir(AutomatonEntity entity) {
        return new UseItemAction(entity, null);
    }

    @Override
    public boolean canExecute(PerceptionData perception) {
        ItemStack held = entity.getEquippedTool();
        if (held.isEmpty()) return false;
        // 方块物品的放置由 PlaceBlockAction 处理，UseItem 不处理
        if (held.getItem() instanceof BlockItem) return false;
        return true;
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        if (used) return ActionResult.SUCCESS;
        used = true;

        ItemStack held = entity.getEquippedTool();
        if (held.isEmpty()) return ActionResult.FAILURE;

        entity.swing(InteractionHand.MAIN_HAND);

        if (target != null) {
            BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(target),
                net.minecraft.core.Direction.UP,
                target,
                false
            );
            InteractionResult result = held.useOn(
                new net.minecraft.world.item.context.UseOnContext(
                    entity.level(), null,
                    InteractionHand.MAIN_HAND,
                    held, hit
                )
            );
            if (result.consumesAction()) return ActionResult.SUCCESS;
        }

        // 对空气使用（如吃东西、拉弓、抛竿）
        entity.startUsingItem(InteractionHand.MAIN_HAND);
        return ActionResult.SUCCESS;
    }

    @Override
    public int getCost() {
        return 1;
    }
}
