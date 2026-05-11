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
import net.minecraft.world.phys.Vec3;

/**
 * 建造结构 —— 根据预设模板在目标位置逐格建造。
 * <p>
 * 模板定义了一组相对偏移坐标，实体从原点开始依次移动到每个位置并放置方块。
 * 支持：3x3 小屋、柱子、墙壁等。
 */
public class BuildStructureAction implements AtomicAction {

    private static final double PLACE_DISTANCE_SQ = 3.0 * 3.0;
    private static final int TIMEOUT_TICKS_PER_BLOCK = 100;

    // ===== 预设结构模板（偏移坐标列表） =====

    /** 3×3 地板（9 格） */
    public static final BlockPos[] FLOOR_3x3 = {
        new BlockPos(0,0,0), new BlockPos(1,0,0), new BlockPos(2,0,0),
        new BlockPos(0,0,1), new BlockPos(1,0,1), new BlockPos(2,0,1),
        new BlockPos(0,0,2), new BlockPos(1,0,2), new BlockPos(2,0,2),
    };

    /** 简易 3×3 小屋 = 地板 + 四角柱 + 四面墙（不含屋顶） */
    public static final BlockPos[] HUT_3x3;
    static {
        // 地板 3×3
        BlockPos[] floor = FLOOR_3x3;
        // 四角柱 y=1,2
        BlockPos[] corners = {
            new BlockPos(0,1,0), new BlockPos(2,1,0),
            new BlockPos(0,1,2), new BlockPos(2,1,2),
            new BlockPos(0,2,0), new BlockPos(2,2,0),
            new BlockPos(0,2,2), new BlockPos(2,2,2),
        };
        // 四面墙 y=1（不含角柱位置）
        BlockPos[] wallsY1 = {
            new BlockPos(1,1,0), new BlockPos(0,1,1),
            new BlockPos(1,1,1), new BlockPos(2,1,1),
            new BlockPos(1,1,2),
        };
        // 四面墙 y=2
        BlockPos[] wallsY2 = {
            new BlockPos(1,2,0), new BlockPos(0,2,1),
            new BlockPos(1,2,1), new BlockPos(2,2,1),
            new BlockPos(1,2,2),
        };
        // 合并所有
        BlockPos[] all = new BlockPos[
            floor.length + corners.length + wallsY1.length + wallsY2.length];
        System.arraycopy(floor, 0, all, 0, floor.length);
        System.arraycopy(corners, 0, all, floor.length, corners.length);
        System.arraycopy(wallsY1, 0, all, floor.length + corners.length, wallsY1.length);
        System.arraycopy(wallsY2, 0, all,
            floor.length + corners.length + wallsY1.length, wallsY2.length);
        HUT_3x3 = all;
    }

    /** 垂直柱（5 格高） */
    public static final BlockPos[] PILLAR_5 = {
        new BlockPos(0,0,0), new BlockPos(0,1,0),
        new BlockPos(0,2,0), new BlockPos(0,3,0),
        new BlockPos(0,4,0),
    };

    // ===== 实例字段 =====

    private final BlockPos[] offsets;
    private final Block blockType;
    private BlockPos origin;
    private int currentIndex;
    private int timeoutCounter;
    private boolean done;

    /**
     * @param offsets  相对偏移坐标列表，定义结构形状
     * @param blockType 建造方块类型
     */
    public BuildStructureAction(BlockPos[] offsets, Block blockType) {
        this.offsets = offsets;
        this.blockType = blockType;
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
        if (currentIndex >= offsets.length) {
            done = true;
            return true;
        }

        // 首次 tick 记录原点
        if (origin == null) {
            origin = entity.blockPosition();
        }

        // 计算当前目标位置
        BlockPos targetPos = origin.offset(offsets[currentIndex]);

        // 检查目标位置是否已被占用
        Level level = entity.level();
        if (!level.getBlockState(targetPos).isAir()) {
            currentIndex++;
            timeoutCounter = 0;
            return false; // 继续下一个位置
        }

        // 检查背包中是否有对应方块
        if (!hasBlockInInventory(entity)) {
            AICompanionMod.LOGGER.info("[BuildStructure] No {} blocks left, stopping at index {}",
                blockType, currentIndex);
            done = true;
            return true;
        }

        // 看向目标
        Vec3 center = Vec3.atCenterOf(targetPos);
        entity.getLookControl().setLookAt(center.x, center.y, center.z);

        double distSq = entity.distanceToSqr(center.x, center.y, center.z);

        if (distSq > PLACE_DISTANCE_SQ) {
            // 走过去
            entity.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 0.8);
            timeoutCounter = 0;
        } else {
            // 放置方块
            entity.getNavigation().stop();

            if (tryPlace(entity, targetPos)) {
                entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                currentIndex++;
                timeoutCounter = 0;
                AICompanionMod.LOGGER.info("[BuildStructure] Placed block {}/{} at {}",
                    currentIndex, offsets.length, targetPos);
            } else {
                // 放置失败，跳过
                currentIndex++;
                timeoutCounter = 0;
            }
        }

        // 超时保护（每个位置的超时叠加）
        timeoutCounter++;
        if (timeoutCounter > TIMEOUT_TICKS_PER_BLOCK * 2) {
            AICompanionMod.LOGGER.info("[BuildStructure] Timeout at index {}, skipping", currentIndex);
            currentIndex++;
            timeoutCounter = 0;
        }

        return false;
    }

    /**
     * 尝试从背包中取方块并放置到目标位置。
     */
    private boolean tryPlace(AutomatonEntity entity, BlockPos pos) {
        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack stack = entity.getItem(i);
            if (stack.isEmpty()) continue;

            if (stack.getItem() instanceof BlockItem blockItem) {
                Block block = blockItem.getBlock();
                if (block == blockType) {
                    Level level = entity.level();
                    BlockState state = block.defaultBlockState();

                    if (state.canSurvive(level, pos)) {
                        level.setBlock(pos, state, 3);
                        stack.shrink(1);
                        if (stack.isEmpty()) {
                            entity.setItem(i, ItemStack.EMPTY);
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 检查背包中是否还有对应的方块。
     */
    private boolean hasBlockInInventory(AutomatonEntity entity) {
        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack stack = entity.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() instanceof BlockItem blockItem) {
                if (blockItem.getBlock() == blockType) {
                    return true;
                }
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
        return "建造: " + blockType + " (" + offsets.length + " 格)";
    }
}
