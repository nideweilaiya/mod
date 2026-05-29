package com.aiworkbench.companion.core.action.impl;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.core.action.ActionResult;
import com.aiworkbench.companion.core.action.IAction;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.entity.AutomatonEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 在指定位置放置方块的动作原语。
 *
 * <p>由评估层传入目标坐标和方块类型。从背包中取出方块物品，
 * 潜行状态下放置在目标位置。</p>
 *
 * <h3>组合示例</h3>
 * <pre>{@code
 *   // 垫脚：在脚下放方块然后跳上去
 *   PlaceBlockAction(feetPos) → MoveToAction(abovePos)
 *
 *   // 建筑：连续放置
 *   PlaceBlockAction(wallPos, "cobblestone") → PlaceBlockAction(nextWallPos, "cobblestone") → ...
 * }</pre>
 */
public class PlaceBlockAction implements IAction {

    private final AutomatonEntity entity;
    private final BlockPos target;
    private final String blockKeyword; // null = 任意方块

    private boolean executed;

    /** 放置范围上限 */
    private static final double PLACE_DISTANCE_SQ = 5.0 * 5.0;

    public PlaceBlockAction(AutomatonEntity entity, BlockPos target, String blockKeyword) {
        this.entity = entity;
        this.target = target;
        this.blockKeyword = blockKeyword;
    }

    /** 放置任意方块（使用背包中第一个找到的方块物品） */
    public PlaceBlockAction(AutomatonEntity entity, BlockPos target) {
        this(entity, target, null);
    }

    // ==================== IAction 接口 ====================

    @Override
    public boolean canExecute(PerceptionData perception) {
        if (target == null) return false;

        // 目标位置必须可放置
        BlockState state = entity.level().getBlockState(target);
        if (!state.isAir() && !state.canBeReplaced()) return false;

        // 背包中必须有方块物品
        return findBlockItem() >= 0;
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        if (executed) return ActionResult.SUCCESS;
        executed = true;

        // 距离检查
        double distSq = entity.distanceToSqr(
            target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5
        );
        if (distSq > PLACE_DISTANCE_SQ) {
            return ActionResult.FAILURE;
        }

        int slot = findBlockItem();
        if (slot < 0) return ActionResult.FAILURE;

        ItemStack stack = entity.getItem(slot);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return ActionResult.FAILURE;
        }

        // 潜行放置（确保方块放在目标面而非交互）
        entity.setShiftKeyDown(true);
        entity.level().setBlock(target, blockItem.getBlock().defaultBlockState(), 3);
        entity.setShiftKeyDown(false);

        // 消耗物品
        stack.shrink(1);
        if (stack.isEmpty()) {
            entity.setItem(slot, ItemStack.EMPTY);
        }

        AICompanionMod.LOGGER.info("[PlaceBlock] {} at {}",
            blockItem.getBlock().builtInRegistryHolder().key().location(), target);

        return ActionResult.SUCCESS;
    }

    @Override
    public int getCost() {
        return 10; // 放置动作约 0.5 秒
    }

    // ==================== 内部方法 ====================

    private int findBlockItem() {
        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack stack = entity.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) continue;
            if (blockKeyword != null) {
                String id = stack.getItem().builtInRegistryHolder().key().location().getPath();
                if (!id.contains(blockKeyword)) continue;
            }
            return i;
        }
        return -1;
    }
}
