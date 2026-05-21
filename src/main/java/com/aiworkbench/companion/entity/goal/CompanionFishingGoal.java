package com.aiworkbench.companion.entity.goal;

import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.InteractionHand;

import java.util.EnumSet;
import java.util.List;

/**
 * 自动钓鱼 —— 找水域、抛竿、等咬钩、收竿、收集。
 */
public class CompanionFishingGoal extends Goal {

    private final AutomatonEntity companion;
    private BlockPos waterPos;
    private FishingHook hook;
    private int waitTimer = 0;
    private int castCooldown = 0;

    private enum State { FIND_WATER, NAVIGATE, CAST, WAIT_BITE, REEL, COLLECT }
    private State state = State.FIND_WATER;

    private static final double SCAN_RADIUS = 12.0;
    private static final int MIN_WAIT_TICKS = 100;  // 5s
    private static final int MAX_WAIT_TICKS = 600;  // 30s

    public CompanionFishingGoal(AutomatonEntity companion) {
        this.companion = companion;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (companion.isSkillActive()) return false;
        if (companion.isGuardModeEnabled()) return false;
        if (!hasFishingRod()) return false;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return hasFishingRod() && !companion.isGuardModeEnabled() && !companion.isSkillActive();
    }

    @Override
    public void start() {
        state = State.FIND_WATER;
        waterPos = null;
        waitTimer = 0;
    }

    @Override
    public void stop() {
        companion.getNavigation().stop();
        waterPos = null;
        state = State.FIND_WATER;
    }

    @Override
    public void tick() {
        if (castCooldown > 0) { castCooldown--; return; }

        switch (state) {
            case FIND_WATER -> {
                waterPos = findWater();
                if (waterPos != null) state = State.NAVIGATE;
            }
            case NAVIGATE -> {
                companion.getNavigation().moveTo(waterPos.getX(), waterPos.getY(), waterPos.getZ(), 0.5);
                double dist = companion.distanceToSqr(waterPos.getX(), waterPos.getY(), waterPos.getZ());
                if (dist < 9.0) { // 3 blocks
                    companion.getNavigation().stop();
                    if (!isSafePosition(companion.blockPosition())) {
                        state = State.FIND_WATER;
                        break;
                    }
                    ensureRodEquipped();
                    state = State.CAST;
                    waitTimer = MIN_WAIT_TICKS + companion.getRandom().nextInt(MAX_WAIT_TICKS - MIN_WAIT_TICKS);
                }
            }
            case CAST -> {
                // 看向水面
                companion.getLookControl().setLookAt(waterPos.getX(), waterPos.getY(), waterPos.getZ(), 30f, 30f);
                // 使用钓鱼竿
                companion.swing(InteractionHand.MAIN_HAND);
                state = State.WAIT_BITE;
            }
            case WAIT_BITE -> {
                waitTimer--;
                // 检查是否有鱼咬钩
                FishingHook myHook = findMyHook();
                if (myHook != null && (myHook.getHookedIn() != null || waitTimer <= 0)) {
                    // 有东西上钩或超时 → 收竿
                    companion.swing(InteractionHand.MAIN_HAND);
                    state = State.REEL;
                }
                if (waitTimer <= 0) {
                    state = State.REEL;
                }
            }
                        case REEL -> {
                companion.grantXp(10);
                companion.showDialogue("\u00a7b+10 XP", 40);
                discardJunkIfFull();
                castCooldown = 40;
                state = State.FIND_WATER;
            }
        }
    }

    private boolean isSafePosition(BlockPos pos) {
        if (pos == null) return false;
        var state = companion.level().getBlockState(pos);
        var below = companion.level().getBlockState(pos.below());
        if (below.isAir() || below.liquid()) return false;
        if (state.liquid()) return false;
        if (!companion.level().getBlockState(pos.above(2)).isAir()) return false;
        return true;
    }

    private static final java.util.Set<net.minecraft.world.item.Item> JUNK_ITEMS = java.util.Set.of(
        Items.BONE, Items.STRING, Items.ROTTEN_FLESH, Items.SPIDER_EYE,
        Items.POISONOUS_POTATO, Items.STICK, Items.VINE
    );

    private void discardJunkIfFull() {
        int empty = 0;
        for (int i = 0; i < companion.getInventorySize(); i++) {
            if (companion.getItem(i).isEmpty()) empty++;
        }
        if (empty > 2) return;
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack s = companion.getItem(i);
            if (!s.isEmpty() && JUNK_ITEMS.contains(s.getItem())) {
                companion.setItem(i, ItemStack.EMPTY);
                empty++;
                if (empty > 2) return;
            }
        }
    }
    private BlockPos findWater() {
        BlockPos center = companion.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                for (int y = 4; y >= -4; y--) {
                    BlockPos p = center.offset(x, y, z);
                    var state = companion.level().getBlockState(p);
                    if (state.is(Blocks.WATER) && state.getFluidState().isSource()) {
                        // 上方必须是空气
                        if (companion.level().getBlockState(p.above()).isAir()) {
                            double d = companion.distanceToSqr(p.getX(), p.getY(), p.getZ());
                            if (d < bestDist) {
                                bestDist = d;
                                best = p;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private boolean hasFishingRod() {
        for (int i = 0; i < companion.getInventorySize(); i++) {
            if (companion.getItem(i).getItem() == Items.FISHING_ROD) return true;
        }
        return companion.getMainHandItem().getItem() == Items.FISHING_ROD;
    }

    private void ensureRodEquipped() {
        if (companion.getMainHandItem().getItem() == Items.FISHING_ROD) return;
        for (int i = 0; i < companion.getInventorySize(); i++) {
            if (companion.getItem(i).getItem() == Items.FISHING_ROD) {
                ItemStack rod = companion.getItem(i).copy();
                companion.setItem(i, companion.getMainHandItem().copy());
                companion.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, rod);
                return;
            }
        }
    }

    private FishingHook findMyHook() {
        AABB area = companion.getBoundingBox().inflate(20);
        List<FishingHook> hooks = companion.level().getEntitiesOfClass(FishingHook.class, area);
        for (FishingHook h : hooks) {
            if (h.getOwner() == companion) return h;
        }
        return null;
    }
}
