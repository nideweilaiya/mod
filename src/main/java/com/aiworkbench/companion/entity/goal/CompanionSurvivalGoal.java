package com.aiworkbench.companion.entity.goal;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

/**
 * 同伴生存Goal —— 夜间照明 + 简易庇护所。
 * <p>
 * 最低优先级运行，只在夜间且同伴暴露于天空时激活：
 * 1. 检查周围光照 → 太暗就插火把
 * 2. 没火把 → 尝试合成（煤炭+木棍）
 * 3. 没材料 → 临时躲到树/墙下
 */
public class CompanionSurvivalGoal extends Goal {
    private final AutomatonEntity companion;
    private static final int LIGHT_THRESHOLD = 8;
    private static final int TORCH_RADIUS = 6;
    private static final int CHECK_INTERVAL = 200; // 10秒检查一次
    private int checkTimer;
    private BlockPos torchTarget;

    public CompanionSurvivalGoal(AutomatonEntity companion) {
        this.companion = companion;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (companion.isSkillActive()) return false;
        if (!companion.isFollowModeActive() && !companion.isGatherModeEnabled()) return false;

        int blockLight = companion.level().getBrightness(LightLayer.BLOCK, companion.blockPosition());

        if (companion.level().canSeeSky(companion.blockPosition()) && companion.level().isDay()) return false;
        if (blockLight >= LIGHT_THRESHOLD) return false;

        checkTimer++;
        if (checkTimer < CHECK_INTERVAL) return false;
        checkTimer = 0;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return torchTarget != null
            && companion.level().getBlockState(torchTarget.above()).isAir();
    }

    @Override
    public void start() {
        torchTarget = findDarkSpot();
        if (torchTarget == null) {
            // 没有暗点但露天→造临时庇护所
            buildQuickShelter();
        }
    }

    @Override
    public void tick() {
        if (torchTarget == null) return;
        double distSq = companion.distanceToSqr(torchTarget.getX() + 0.5,
            torchTarget.getY(), torchTarget.getZ() + 0.5);

        if (distSq > 4.0) {
            companion.getNavigation().moveTo(
                torchTarget.getX(), torchTarget.getY(), torchTarget.getZ(), 0.8);
        } else {
            companion.getNavigation().stop();
            placeTorch(torchTarget);
            torchTarget = null;
            checkTimer = 0;
        }
    }

    @Override
    public void stop() {
        torchTarget = null;
    }

    // ===== 找暗点 =====

    private BlockPos findDarkSpot() {
        BlockPos origin = companion.blockPosition();
        Level level = companion.level();
        BlockPos darkest = null;
        int darkestLight = LIGHT_THRESHOLD;

        for (int dx = -TORCH_RADIUS; dx <= TORCH_RADIUS; dx += 2) {
            for (int dz = -TORCH_RADIUS; dz <= TORCH_RADIUS; dz += 2) {
                BlockPos pos = origin.offset(dx, 0, dz);
                // 找地面位置
                BlockPos ground = findGround(pos);
                if (ground == null) continue;
                BlockPos above = ground.above();

                if (!level.getBlockState(above).isAir()) continue;
                if (!level.getBlockState(ground).isSolid()) continue;

                int light = level.getBrightness(LightLayer.BLOCK, above);
                if (light <= darkestLight) {
                    darkestLight = light;
                    darkest = ground.immutable();
                }
            }
        }
        return darkest;
    }

    private BlockPos findGround(BlockPos pos) {
        Level level = companion.level();
        BlockPos p = pos;
        // 向下找地面
        for (int dy = 0; dy > -5; dy--) {
            BlockPos check = p.offset(0, dy, 0);
            if (level.getBlockState(check).isSolid()
                && level.getBlockState(check.above()).isAir()) {
                return check;
            }
        }
        return null;
    }

    // ===== 放火把 =====

    private void placeTorch(BlockPos onBlock) {
        Level level = companion.level();
        BlockPos place = onBlock.above();

        // 先确认有火把
        ItemStack torch = findTorch();
        if (torch.isEmpty()) {
            // 尝试合成火把
            torch = craftTorch();
        }
        if (torch.isEmpty()) return;

        // 放置火把
        if (level.getBlockState(place).isAir()) {
            var torchState = Blocks.TORCH.defaultBlockState();
            if (!torchState.canSurvive(level, place)) return;
            level.setBlock(place, torchState, 3);
            companion.animateBlockPlace(place);
            torch.shrink(1);
            if (torch.isEmpty()) {
                for (int i = 0; i < companion.getInventorySize(); i++) {
                    if (companion.getItem(i) == torch) {
                        companion.setItem(i, ItemStack.EMPTY);
                        break;
                    }
                }
            }
            companion.showDialogue("§e🔥", 20);
            AICompanionMod.LOGGER.info("[Survival] Placed torch at {}", place);
        }
    }

    private ItemStack findTorch() {
        // 先检查副手
        ItemStack offhand = companion.getItemBySlot(EquipmentSlot.OFFHAND);
        if (offhand.getItem() == Items.TORCH) return offhand;
        // 检查背包
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack s = companion.getItem(i);
            if (s.getItem() == Items.TORCH) return s;
        }
        return ItemStack.EMPTY;
    }

    private ItemStack craftTorch() {
        // 检查材料：煤炭/木炭 + 木棍
        int coalSlot = -1, stickSlot = -1;
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack s = companion.getItem(i);
            if (s.isEmpty()) continue;
            if ((s.getItem() == Items.COAL || s.getItem() == Items.CHARCOAL) && coalSlot < 0)
                coalSlot = i;
            if (s.getItem() == Items.STICK && stickSlot < 0)
                stickSlot = i;
        }
        if (coalSlot < 0 || stickSlot < 0) return ItemStack.EMPTY;

        // 消耗材料
        companion.getItem(coalSlot).shrink(1);
        companion.getItem(stickSlot).shrink(1);
        if (companion.getItem(coalSlot).isEmpty()) companion.setItem(coalSlot, ItemStack.EMPTY);
        if (companion.getItem(stickSlot).isEmpty()) companion.setItem(stickSlot, ItemStack.EMPTY);

        // 产出4个火把
        ItemStack torches = new ItemStack(Items.TORCH, 4);
        companion.addItemToInventory(torches);
        companion.showDialogue("§e合成火把×4", 30);
        return findTorch();
    }

    // ===== 简易庇护所 =====

    private void buildQuickShelter() {
        BlockPos pos = companion.blockPosition();
        Level level = companion.level();
        BlockPos shelter = pos;

        // 找最近的墙/树作为掩体
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                BlockPos p = pos.offset(dx, 0, dz);
                if (level.canSeeSky(p)) continue;
                if (level.getBlockState(p.above()).isAir()) {
                    shelter = p;
                    break;
                }
            }
        }

        if (!shelter.equals(pos)) {
            companion.getNavigation().moveTo(shelter.getX(), shelter.getY(), shelter.getZ(), 1.0);
            companion.showDialogue("§7找掩体...", 30);
        }
    }
}
