package com.aiworkbench.companion.skill.atomic;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.building.BuildingBlueprint;
import com.aiworkbench.companion.building.BuildingBlueprint.BlockPlacement;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.AtomicAction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 建造结构 —— 根据蓝图在目标位置逐格建造，支持多材料混合。
 */
public class BuildStructureAction implements AtomicAction {

    private static final double PLACE_DISTANCE_SQ = 3.0 * 3.0;
    private static final int TIMEOUT_TICKS_PER_BLOCK = 100;

    private final BuildingBlueprint blueprint;
    private BlockPos origin;
    private int currentIndex;
    private int timeoutCounter;
    private boolean done;

    /** 使用蓝图构造 */
    public BuildStructureAction(BuildingBlueprint blueprint) {
        this.blueprint = blueprint;
        this.currentIndex = 0;
        this.timeoutCounter = 0;
        this.done = false;
    }

    /** @deprecated 使用 {@link #BuildStructureAction(BuildingBlueprint)} 替代 */
    @Deprecated
    public BuildStructureAction(BlockPos[] offsets, Block blockType) {
        // 从旧模板创建临时蓝图
        BuildingBlueprint bp = new BuildingBlueprint("legacy", 3, 3, 3, null);
        for (BlockPos offset : offsets) {
            bp.add(offset.getX(), offset.getY(), offset.getZ(), blockType);
        }
        this.blueprint = bp;
        this.currentIndex = 0;
        this.timeoutCounter = 0;
        this.done = false;
    }

    @Override
    public boolean canStart(AutomatonEntity entity) {
        return true;
    }

    @Override
    public boolean tick(AutomatonEntity entity) {
        if (done) return true;

        var placements = blueprint.getPlacements();
        if (currentIndex >= placements.size()) {
            done = true;
            return true;
        }

        if (origin == null) {
            origin = entity.blockPosition();
        }

        BlockPlacement placement = placements.get(currentIndex);
        BlockPos relative = placement.relativePos();
        BlockPos targetPos = origin.offset(relative);
        Block blockType = placement.block();

        Level level = entity.level();

        // 跳过已有方块的位置
        if (!level.getBlockState(targetPos).isAir()) {
            currentIndex++;
            timeoutCounter = 0;
            return false;
        }

        // 检查是否有对应方块
        if (!hasBlock(entity, blockType)) {
            AICompanionMod.LOGGER.info("[BuildStructure] No {} left, stopping at {}/{}",
                blockType.getName().getString(), currentIndex, placements.size());
            done = true;
            return true;
        }

        // 看向目标
        Vec3 center = Vec3.atCenterOf(targetPos);
        entity.getLookControl().setLookAt(center.x, center.y, center.z);

        double distSq = entity.distanceToSqr(center.x, center.y, center.z);
        if (distSq > PLACE_DISTANCE_SQ) {
            entity.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 0.8);
            timeoutCounter = 0;
        } else {
            entity.getNavigation().stop();
            if (tryPlace(entity, targetPos, blockType)) {
                entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                currentIndex++;
                timeoutCounter = 0;
            } else {
                currentIndex++;
                timeoutCounter = 0;
            }
        }

        timeoutCounter++;
        if (timeoutCounter > TIMEOUT_TICKS_PER_BLOCK * 2) {
            currentIndex++;
            timeoutCounter = 0;
        }
        return false;
    }

    private boolean tryPlace(AutomatonEntity entity, BlockPos pos, Block blockType) {
        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack stack = entity.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() instanceof BlockItem bi) {
                if (bi.getBlock() == blockType) {
                    Level level = entity.level();
                    BlockState state = blockType.defaultBlockState();
                    if (state.canSurvive(level, pos)) {
                        level.setBlock(pos, state, 3);
                        stack.shrink(1);
                        if (stack.isEmpty()) entity.setItem(i, ItemStack.EMPTY);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasBlock(AutomatonEntity entity, Block blockType) {
        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack stack = entity.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() instanceof BlockItem bi && bi.getBlock() == blockType) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void stop(AutomatonEntity entity) {
        entity.getNavigation().stop();
        done = true;
    }

    @Override
    public void reset() {
        origin = null;
        currentIndex = 0;
        timeoutCounter = 0;
        done = false;
    }

    @Override
    public String getDescription() {
        return "建造: " + blueprint.name + " (" + blueprint.totalBlocks() + " 格)";
    }
}
