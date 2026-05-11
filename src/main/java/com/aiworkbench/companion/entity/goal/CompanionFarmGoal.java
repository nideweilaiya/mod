package com.aiworkbench.companion.entity.goal;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * 作物种植Goal —— 收割成熟作物 + 自动补种。
 *
 * 支持作物：小麦/胡萝卜/土豆/甜菜/地狱疣
 * 行为：扫描→走路→收割→收集→补种→下一个
 * 可选：用锄头耕未开垦的湿润土地
 */
public class CompanionFarmGoal extends Goal {
    private final AutomatonEntity companion;
    private final double speed;

    private BlockPos target;
    private boolean isBreaking;
    private float progress;
    private int mineTicks;
    private int broken, collected;

    private static final double REACH_SQ = 2.5 * 2.5;
    private static final int SCAN = 10;
    private static final float BASE_BREAK = 30f;

    // 卡住
    private BlockPos lastPos;
    private int stuckTicks;
    private static final int STUCK_MAX = 30;

    private final Set<BlockPos> blacklist = new java.util.HashSet<>();
    private int blacklistTicks;
    private static final int BLACKLIST_DURATION = 200;

    public CompanionFarmGoal(AutomatonEntity companion, double speed) {
        this.companion = companion;
        this.speed = speed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (companion.isSkillActive()) return false;
        if (!companion.isFarmModeEnabled()) return false;
        if (companion.isGuardModeEnabled()) return false;
        if (isBreaking) return true;
        if (hostilesNearby()) { companion.setGuardModeEnabled(true); return false; }
        target = scan();
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!companion.isFarmModeEnabled() || companion.isSkillActive()) return false;
        if (companion.isGuardModeEnabled()) return false;
        if (hostilesNearby()) {
            companion.setGuardModeEnabled(true);
            companion.setFarmModeEnabled(false);
            companion.showDialogue("§c敌人！切换战斗", 40);
            return false;
        }
        return target != null || (isBreaking && scan() != null);
    }

    private boolean hostilesNearby() {
        return !companion.level().getEntities(companion,
            companion.getBoundingBox().inflate(10),
            e -> e instanceof net.minecraft.world.entity.monster.Monster && e.isAlive()
        ).isEmpty();
    }

    @Override
    public void start() {
        isBreaking = false; progress = 0f; mineTicks = 0;
        broken = 0; collected = 0;
        lastPos = companion.blockPosition(); stuckTicks = 0;
        blacklist.clear(); blacklistTicks = 0;
    }

    @Override
    public void stop() {
        companion.getNavigation().stop();
        if (target != null && companion.level() instanceof ServerLevel sl)
            sl.destroyBlockProgress(companion.getId(), target, -1);
        isBreaking = false; target = null;
    }

    @Override
    public void tick() {
        if (blacklistTicks > 0) blacklistTicks--;
        else if (!blacklist.isEmpty()) { blacklist.clear(); }

        // 卡住检测
        BlockPos now = companion.blockPosition();
        if (lastPos != null && now.distSqr(lastPos) < 2.25) stuckTicks++;
        else { stuckTicks = 0; lastPos = now; }
        if (stuckTicks > STUCK_MAX && target != null) {
            blacklist.add(target.immutable()); blacklistTicks = BLACKLIST_DURATION;
            clearTarget(); stuckTicks = 0;
        }

        // 目标管理
        if (target == null || !valid(target)) {
            target = scan();
            clearTarget();
            if (target == null) return;
        }

        Vec3 center = Vec3.atCenterOf(target);
        double distSq = companion.distanceToSqr(center.x, center.y, center.z);
        companion.getLookControl().setLookAt(center.x, center.y, center.z);

        if (distSq > REACH_SQ) {
            companion.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), speed);
            isBreaking = false; progress = 0f; mineTicks = 0;
        } else {
            companion.getNavigation().stop();

            BlockState state = companion.level().getBlockState(target);
            if (!isMature(state)) { target = null; return; }

            // 切换工具（作物用手更快，但锄头也行）
            float hardness = state.getBlock().defaultDestroyTime();
            float ts = companion.getEffectiveDigSpeed(state);
            progress += ts / (hardness * 30f + 1f);
            isBreaking = true;

            if (mineTicks % 4 == 0) {
                companion.animateSwing();
            }
            if (companion.level() instanceof ServerLevel sl && mineTicks % 4 == 0) {
                int crackStage = (int)(progress * 10);
                if (crackStage > 9) crackStage = 9;
                sl.destroyBlockProgress(companion.getId(), target, crackStage);
            }

            if (progress >= 1.0f) {
                if (companion.level() instanceof ServerLevel sl)
                    sl.destroyBlockProgress(companion.getId(), target, -1);
                harvestBlock(target);
                progress = 0f; mineTicks = 0; isBreaking = false;
                broken++;
                // 找下一个
                target = scan();
                stuckTicks = 0;
            } else { mineTicks++; }
        }
    }

    private void clearTarget() {
        isBreaking = false; progress = 0f; mineTicks = 0;
    }

    // ===== 扫描 =====

    private BlockPos scan() {
        BlockPos origin = companion.blockPosition();
        Level level = companion.level();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -SCAN; dx <= SCAN; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -SCAN; dz <= SCAN; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (blacklist.contains(p)) continue;
                    BlockState s = level.getBlockState(p);
                    if (isMature(s)) {
                        double dist = origin.distSqr(p);
                        if (dist < bestDist) { bestDist = dist; best = p.immutable(); }
                    }
                }
            }
        }
        return best;
    }

    // ===== 收割 =====

    private void harvestBlock(BlockPos pos) {
        if (companion.level().isClientSide) return;
        Level level = companion.level();
        BlockState state = level.getBlockState(pos);
        if (!(level instanceof ServerLevel sl)) return;

        Block block = state.getBlock();
        String name = block.builtInRegistryHolder().key().location().getPath();

        // 收集掉落物
        List<ItemStack> drops = Block.getDrops(state, sl, pos, null, companion, ItemStack.EMPTY);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        level.levelEvent(2001, pos, net.minecraft.world.level.block.Block.getId(state));

        for (ItemStack d : drops) {
            if (!d.isEmpty()) { collected++; companion.addItemToInventory(d); }
        }

        // 补种
        BlockPos soil = pos.below();
        BlockState soilState = level.getBlockState(soil);
        if (soilState.getBlock() instanceof FarmBlock || soilState.getBlock() instanceof SoulSandBlock) {
            Item seedItem = getSeedFor(name);
            if (seedItem != null && consumeItem(seedItem) >= 0) {
                BlockState newCrop = getCropBlock(name);
                if (newCrop != null) {
                    level.setBlock(pos, newCrop, 3);
                    companion.animateSwing();
                }
            }
        }

        AICompanionMod.LOGGER.info("[FarmGoal] Harvested {} ({}crops/{}items)", name, broken, collected);
    }

    // ===== 种子/作物映射 =====

    private static boolean isMature(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) return crop.isMaxAge(state);
        if (block instanceof NetherWartBlock) return state.getValue(NetherWartBlock.AGE) >= 3;
        return false;
    }

    private static Item getSeedFor(String cropName) {
        return switch (cropName) {
            case "wheat" -> Items.WHEAT_SEEDS;
            case "carrots" -> Items.CARROT;
            case "potatoes" -> Items.POTATO;
            case "beetroots" -> Items.BEETROOT_SEEDS;
            case "nether_wart" -> Items.NETHER_WART;
            default -> null;
        };
    }

    private static BlockState getCropBlock(String cropName) {
        return switch (cropName) {
            case "wheat" -> Blocks.WHEAT.defaultBlockState();
            case "carrots" -> Blocks.CARROTS.defaultBlockState();
            case "potatoes" -> Blocks.POTATOES.defaultBlockState();
            case "beetroots" -> Blocks.BEETROOTS.defaultBlockState();
            case "nether_wart" -> Blocks.NETHER_WART.defaultBlockState();
            default -> null;
        };
    }

    private int consumeItem(Item item) {
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack s = companion.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) {
                s.shrink(1);
                if (s.isEmpty()) companion.setItem(i, ItemStack.EMPTY);
                return i;
            }
        }
        // 没找到种子但作物本身就是种子（胡萝卜/土豆/地狱疣的掉落物刚被收入背包）
        // 再搜一次（因为上面可能已经addItemToInventory了）
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack s = companion.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) {
                s.shrink(1);
                if (s.isEmpty()) companion.setItem(i, ItemStack.EMPTY);
                return i;
            }
        }
        return -1;
    }

    private boolean valid(BlockPos p) {
        return p != null && !blacklist.contains(p)
            && isMature(companion.level().getBlockState(p));
    }

    public int getHarvested() { return broken; }
    public int getCollected() { return collected; }
}
