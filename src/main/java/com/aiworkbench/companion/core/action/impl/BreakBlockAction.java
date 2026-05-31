package com.aiworkbench.companion.core.action.impl;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.core.action.ActionResult;
import com.aiworkbench.companion.core.action.IAction;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.entity.AutomatonEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 破坏指定方块并收集掉落物的动作原语。
 *
 * <p>每 tick 调用一次 {@link #execute}，累积挖掘进度。
 * 方块被破坏后自动收集掉落物。与 MoveToAction 组合使用：
 * <pre>{@code
 *   MoveToAction(nearTree) → BreakBlockAction(treePos) → PickupItemAction()
 * }</pre>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>只负责破坏传入的方块，不自行搜索目标（搜索是评估层的职责）</li>
 *   <li>canExecute 是纯函数：只检查方块是否存在、是否可破坏、是否在范围内</li>
 *   <li>距离不足时返回 FAILURE 而非内部移动（移动是 MoveToAction 的职责）</li>
 * </ul>
 */
public class BreakBlockAction implements IAction {

    private final AutomatonEntity entity;
    private final BlockPos target;

    private int elapsedTicks;
    private float accumulatedProgress;
    private int mineTicks;

    // 射线排障
    @Nullable
    private BlockPos obstacleTarget;
    private float obstacleProgress;
    private int obstacleMineTicks;
    private int obstacleAttempts;
    private static final int MAX_OBSTACLE_ATTEMPTS = 20;

    /** 匹配玩家生存挖掘距离 */
    private static final double BREAK_DISTANCE_SQ = 4.5 * 4.5;
    /** 硬度→tick 转换系数 */
    private static final float TICKS_PER_HARDNESS = 30f;
    /** 最大执行时间（30 秒超时） */
    private static final int MAX_TICKS = 600;

    public BreakBlockAction(AutomatonEntity entity, BlockPos target) {
        this.entity = entity;
        this.target = target;
    }

    // ==================== IAction 接口 ====================

    @Override
    public boolean canExecute(PerceptionData perception) {
        if (target == null) return false;
        // 距离检查：必须在挖掘范围内
        if (!isWithinBreakReach(target)) return false;
        BlockState state = entity.level().getBlockState(target);
        if (state.isAir()) return false;
        // 不可破坏方块（基岩等）
        if (state.getBlock().defaultDestroyTime() < 0) return false;
        return true;
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        elapsedTicks++;

        // 确定本 tick 实际挖掘目标（可能被障碍物替换）
        BlockPos effectiveTarget = this.obstacleTarget != null ? this.obstacleTarget : this.target;

        // 射线排障检测（每 10 tick 检查一次，有障碍物时不重复检测）
        if (this.obstacleTarget == null && elapsedTicks % 10 == 0) {
            BlockHitResult hit = raycastToBlock(this.target);
            if (hit != null && !hit.getBlockPos().equals(this.target)) {
                BlockPos barrier = hit.getBlockPos();
                BlockState barrierState = entity.level().getBlockState(barrier);
                if (!barrierState.isAir()
                    && barrierState.getBlock().defaultDestroyTime() >= 0
                    && isBarrier(barrierState)) {
                    if (obstacleAttempts >= MAX_OBSTACLE_ATTEMPTS) {
                        AICompanionMod.LOGGER.info("[BreakBlock] Barrier attempts exhausted for {}", this.target);
                        return ActionResult.FAILURE;
                    }
                    this.obstacleTarget = barrier;
                    this.obstacleProgress = 0;
                    this.obstacleMineTicks = 0;
                    this.obstacleAttempts++;
                    this.accumulatedProgress = 0;
                    this.mineTicks = 0;
                    effectiveTarget = barrier;
                    AICompanionMod.LOGGER.info("[BreakBlock] Clearing barrier {} for target {} (attempt {})",
                        barrier, this.target, obstacleAttempts);
                }
            }
        }

        BlockState state = entity.level().getBlockState(effectiveTarget);

        // 目标已消失
        if (state.isAir()) {
            if (this.obstacleTarget != null) {
                // 障碍物已清除 → 切回原始目标
                this.obstacleTarget = null;
                this.accumulatedProgress = 0;
                this.mineTicks = 0;
                BlockState originalState = entity.level().getBlockState(this.target);
                if (originalState.isAir()) return ActionResult.SUCCESS; // 原目标也不存在了
                AICompanionMod.LOGGER.debug("[BreakBlock] Barrier cleared, returning to {}", this.target);
                return ActionResult.IN_PROGRESS;
            }
            return ActionResult.SUCCESS;
        }

        // 不可破坏
        if (state.getBlock().defaultDestroyTime() < 0) {
            if (this.obstacleTarget != null) {
                // 障碍物变得不可破坏 → 放弃这个障碍物，尝试下一个方向
                this.obstacleTarget = null;
                this.obstacleAttempts++;
                return ActionResult.IN_PROGRESS;
            }
            return ActionResult.FAILURE;
        }

        // 超时（障碍物模式下用更短超时）
        int maxTicks = this.obstacleTarget != null ? 100 : MAX_TICKS;
        if (elapsedTicks > maxTicks) {
            if (this.obstacleTarget != null) {
                this.obstacleTarget = null;
                this.obstacleAttempts++;
                return ActionResult.IN_PROGRESS; // 放弃障碍物，重新 raycast
            }
            return ActionResult.FAILURE;
        }

        // 距离检查
        double distSq = entity.distanceToSqr(
            effectiveTarget.getX() + 0.5, effectiveTarget.getY() + 0.5, effectiveTarget.getZ() + 0.5
        );
        if (!isWithinBreakReach(effectiveTarget)) {
            if (this.obstacleTarget != null) {
                this.obstacleTarget = null;
                return ActionResult.IN_PROGRESS; // 障碍物太远 → 可能是障碍物在奇怪的位置，放弃
            }
            return ActionResult.FAILURE;
        }

        equipBestToolFor(state);

        // 注视目标
        entity.getLookControl().setLookAt(
            effectiveTarget.getX() + 0.5, effectiveTarget.getY() + 0.5, effectiveTarget.getZ() + 0.5
        );

        // 累积挖掘进度（障碍物用独立进度计数器）
        float hardness = state.getBlock().defaultDestroyTime();
        if (hardness < 0) hardness = 50;
        float toolSpeed = entity.getEffectiveDigSpeed(state);
        float progress = this.obstacleTarget != null
            ? (obstacleProgress += toolSpeed / (hardness * TICKS_PER_HARDNESS))
            : (accumulatedProgress += toolSpeed / (hardness * TICKS_PER_HARDNESS));

        // 挥臂动画
        entity.swing(InteractionHand.MAIN_HAND);

        // 裂缝动画（障碍物用独立计数器）
        int currentMineTicks = this.obstacleTarget != null ? ++obstacleMineTicks : ++mineTicks;
        if (entity.level() instanceof ServerLevel sl && currentMineTicks % 4 == 0) {
            int crackStage = Math.min((int) (
                (this.obstacleTarget != null ? obstacleProgress : accumulatedProgress) * 10), 9);
            sl.destroyBlockProgress(entity.getId(), effectiveTarget, crackStage);
        }

        // 挖掘完成
        float targetProgress = this.obstacleTarget != null ? obstacleProgress : accumulatedProgress;
        if (targetProgress >= 1.0f) {
            if (entity.level() instanceof ServerLevel sl) {
                sl.destroyBlockProgress(entity.getId(), effectiveTarget, -1);
            }
            breakAndCollect(entity, effectiveTarget);
            if (this.obstacleTarget != null) {
                // 障碍物挖完 → 切回原始目标
                this.obstacleTarget = null;
                this.obstacleProgress = 0;
                this.obstacleMineTicks = 0;
                this.accumulatedProgress = 0;
                this.mineTicks = 0;
                AICompanionMod.LOGGER.debug("[BreakBlock] Barrier removed, returning to original target {}", this.target);
                return ActionResult.IN_PROGRESS;
            }
            return ActionResult.SUCCESS;
        }

        return ActionResult.IN_PROGRESS;
    }

    @Override
    public int getCost() {
        BlockState state = entity.level().getBlockState(target);
        float hardness = state.getBlock().defaultDestroyTime();
        if (hardness < 0) return Integer.MAX_VALUE;
        float toolSpeed = entity.getEffectiveDigSpeed(state);
        if (toolSpeed <= 0) toolSpeed = 1.0f;
        return (int) Math.ceil(hardness * TICKS_PER_HARDNESS / toolSpeed);
    }

    // ==================== 内部方法 ====================

    /** 从实体眼睛向目标方块中心做射线检测，命中非目标方块表示有遮挡 */
    @Nullable
    private BlockHitResult raycastToBlock(BlockPos target) {
        Vec3 eye = entity.getEyePosition();
        Vec3 center = Vec3.atCenterOf(target);
        Vec3 dir = center.subtract(eye).normalize();
        double dist = eye.distanceTo(center) + 1.0;
        Vec3 end = eye.add(dir.scale(dist));
        ClipContext ctx = new ClipContext(eye, end,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity);
        BlockHitResult hit = entity.level().clip(ctx);
        return hit.getBlockPos().equals(target) ? null : hit;
    }

    /** 判断是否为可清理的软障碍（树叶、藤蔓、苔藓等植物） */
    private static boolean isBarrier(BlockState state) {
        String name = state.getBlock().builtInRegistryHolder().key().location().getPath();
        return name.contains("leaves") || name.contains("leaf") || name.contains("vine")
            || name.contains("moss") || name.contains("snow")
            || name.contains("_plant") || name.contains("fern") || name.contains("bamboo")
            || name.contains("cobweb") || name.contains("scaffold")
            || name.contains("wool") || name.contains("carpet");
    }

    private boolean isWithinBreakReach(BlockPos pos) {
        return entity.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= BREAK_DISTANCE_SQ;
    }

    private void equipBestToolFor(BlockState state) {
        String blockName = state.getBlock().builtInRegistryHolder().key().location().getPath();
        boolean needAxe = blockName.contains("_log") || blockName.contains("_stem")
            || blockName.endsWith("_wood") || blockName.endsWith("_hyphae");
        boolean needPickaxe = blockName.contains("_ore") || blockName.contains("stone")
            || blockName.contains("deepslate") || blockName.contains("cobblestone");

        ItemStack held = entity.getEquippedTool();
        if (needAxe && held.getItem() instanceof AxeItem) return;
        if (needPickaxe && held.getItem() instanceof PickaxeItem) return;
        if (!needAxe && !needPickaxe) return;

        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack stack = entity.getItem(i);
            boolean ok = needAxe ? stack.getItem() instanceof AxeItem : stack.getItem() instanceof PickaxeItem;
            if (!stack.isEmpty() && ok) {
                ItemStack mainHand = entity.getItemBySlot(EquipmentSlot.MAINHAND);
                entity.setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
                entity.setItem(i, mainHand);
                return;
            }
        }
    }

    /**
     * 破坏方块并收集掉落物到背包。
     */
    private void breakAndCollect(AutomatonEntity entity, BlockPos pos) {
        if (entity.level().isClientSide) return;

        Level level = entity.level();
        BlockState state = level.getBlockState(pos);

        if (!(level instanceof ServerLevel serverLevel)) return;

        ItemStack heldTool = entity.getEquippedTool();
        List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(
            state, serverLevel, pos, null, entity, heldTool
        );

        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);

        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) {
                net.minecraft.world.level.block.Block.popResource(level, pos, drop);
            }
        }

        AICompanionMod.LOGGER.info("[BreakBlock] {} at {}",
            state.getBlock().builtInRegistryHolder().key().location(), pos);
    }
}
