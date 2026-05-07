package com.aiworkbench.companion.skill.atomic;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.AtomicAction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 在目标位置放置方块 —— 从背包中查找匹配的 BlockItem → 移动到目标旁 → 放置。
 * <p>
 * 放置前会检查目标位置是否为空，背包中是否有对应的方块。
 */
public class PlaceBlockAction implements AtomicAction {

    private static final double PLACE_DISTANCE_SQ = 3.0 * 3.0;
    private static final int TIMEOUT_TICKS = 200;

    private final BlockPos targetPos;
    private final Block targetBlock;
    private int tickCounter;
    private boolean placed;
    private boolean searched;

    public PlaceBlockAction(BlockPos targetPos, Block targetBlock) {
        this.targetPos = targetPos;
        this.targetBlock = targetBlock;
        this.tickCounter = 0;
        this.placed = false;
        this.searched = false;
    }

    @Override
    public boolean canStart(AutomatonEntity entity) {
        return true;
    }

    @Override
    public boolean tick(AutomatonEntity entity) {
        tickCounter++;

        if (placed) return true;

        // 检查目标位置是否已有方块
        Level level = entity.level();
        if (!level.getBlockState(targetPos).isAir()) {
            return true; // 位置已被占用，跳过
        }

        // 看向目标位置
        var center = net.minecraft.world.phys.Vec3.atCenterOf(targetPos);
        entity.getLookControl().setLookAt(center.x, center.y, center.z);

        double distSq = entity.distanceToSqr(center.x, center.y, center.z);

        if (distSq > PLACE_DISTANCE_SQ) {
            // 走过去
            entity.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 0.8);
        } else {
            // 到达，尝试放置
            entity.getNavigation().stop();

            if (tryPlace(entity)) {
                placed = true;
                entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                return true;
            } else {
                // 背包中没有需要的方块，跳过
                return true;
            }
        }

        if (tickCounter > TIMEOUT_TICKS) {
            entity.getNavigation().stop();
            return true;
        }

        return false;
    }

    /**
     * 从背包中查找匹配的 BlockItem 并放置。
     */
    private boolean tryPlace(AutomatonEntity entity) {
        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack stack = entity.getItem(i);
            if (stack.isEmpty()) continue;

            if (stack.getItem() instanceof BlockItem blockItem) {
                Block block = blockItem.getBlock();
                if (block == targetBlock) {
                    // 放置方块
                    Level level = entity.level();
                    BlockState state = block.defaultBlockState();

                    if (state.canSurvive(level, targetPos)) {
                        level.setBlock(targetPos, state, 3);

                        // 消耗物品
                        stack.shrink(1);
                        if (stack.isEmpty()) {
                            entity.setItem(i, ItemStack.EMPTY);
                        }

                        AICompanionMod.LOGGER.info("[PlaceBlock] Placed {} at {}",
                                targetBlock.builtInRegistryHolder().key().location(), targetPos);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void stop(AutomatonEntity entity) {
        entity.getNavigation().stop();
        tickCounter = 0;
    }

    @Override
    public String getDescription() {
        return "放置方块";
    }
}
