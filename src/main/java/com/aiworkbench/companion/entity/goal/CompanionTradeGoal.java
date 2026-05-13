package com.aiworkbench.companion.entity.goal;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.economy.TradeEvaluator;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.List;

/**
 * 自动交易 —— 寻找村民，评估交易，执行最优交易。
 */
public class CompanionTradeGoal extends Goal {

    private final AutomatonEntity companion;
    private Villager targetVillager;
    private int cooldown = 0;
    private int stuckTimer = 0;

    private static final double SCAN_RADIUS = 16.0;
    private static final int TRADE_COOLDOWN = 200; // 10秒冷却

    public CompanionTradeGoal(AutomatonEntity companion) {
        this.companion = companion;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (companion.isSkillActive()) return false;
        if (cooldown > 0) return false;
        if (!companion.isAutonomousMode() && !companion.isGatherModeEnabled()) return false;
        // 战斗状态不交易
        if (companion.isGuardModeEnabled()) return false;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return targetVillager != null && targetVillager.isAlive()
            && !companion.isSkillActive() && stuckTimer < 200;
    }

    @Override
    public void start() {
        targetVillager = findBestVillager();
        if (targetVillager != null) {
            companion.getNavigation().moveTo(targetVillager, 0.6);
        }
    }

    @Override
    public void stop() {
        targetVillager = null;
        stuckTimer = 0;
        cooldown = TRADE_COOLDOWN;
        companion.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (targetVillager == null || !targetVillager.isAlive()) {
            return;
        }
        cooldown--;

        // 看向村民
        companion.getLookControl().setLookAt(targetVillager, 30f, 30f);

        double dist = companion.distanceToSqr(targetVillager);
        if (dist > 3.0 * 3.0) {
            // 走过去
            companion.getNavigation().moveTo(targetVillager, 0.6);
            if (companion.getNavigation().isDone()) stuckTimer++;
            return;
        }

        // 到达村民旁边 → 执行交易
        companion.getNavigation().stop();
        executeTrades();
        cooldown = TRADE_COOLDOWN * 3; // 交易后长冷却
    }

    private Villager findBestVillager() {
        AABB area = companion.getBoundingBox().inflate(SCAN_RADIUS);
        List<Villager> villagers = companion.level().getEntitiesOfClass(Villager.class, area,
            v -> v.isAlive() && !v.isBaby());

        Villager best = null;
        int bestScore = 0;
        for (Villager v : villagers) {
            MerchantOffers offers = v.getOffers();
            for (MerchantOffer offer : offers) {
                int s = TradeEvaluator.score(offer);
                if (s > bestScore) {
                    bestScore = s;
                    best = v;
                }
            }
        }
        return best;
    }

    private void executeTrades() {
        if (targetVillager == null) return;
        MerchantOffers offers = targetVillager.getOffers();

        int bestScore = 0;
        MerchantOffer bestOffer = null;
        for (MerchantOffer offer : offers) {
            if (offer.isOutOfStock()) continue;
            int s = TradeEvaluator.score(offer);
            if (s > bestScore) {
                bestScore = s;
                bestOffer = offer;
            }
        }

        if (bestOffer == null || bestScore < 5) {
            companion.showDialogue("§7没有好的交易", 40);
            return;
        }

        // 检查并消耗成本
        if (!consumeCost(bestOffer.getCostA())) return;
        if (!bestOffer.getCostB().isEmpty() && !consumeCost(bestOffer.getCostB())) {
            // 退还 costA
            giveItem(bestOffer.getCostA().copy());
            return;
        }

        // 给予结果
        ItemStack reward = bestOffer.getResult().copy();
        giveItem(reward);
        bestOffer.increaseUses();
        companion.showDialogue("§a💱 " + reward.getCount() + "x" + reward.getDisplayName().getString(), 60);
        AICompanionMod.LOGGER.info("[Trade] {} traded: {}", companion.getName().getString(), TradeEvaluator.describe(bestOffer, bestScore));
    }

    private boolean consumeCost(ItemStack cost) {
        int needed = cost.getCount();
        var item = cost.getItem();
        for (int i = 0; i < companion.getInventorySize() && needed > 0; i++) {
            ItemStack stack = companion.getItem(i);
            if (stack.getItem() == item) {
                int take = Math.min(needed, stack.getCount());
                stack.shrink(take);
                needed -= take;
                if (stack.isEmpty()) companion.setItem(i, ItemStack.EMPTY);
            }
        }
        return needed == 0;
    }

    private void giveItem(ItemStack stack) {
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack slot = companion.getItem(i);
            if (slot.isEmpty()) {
                companion.setItem(i, stack);
                return;
            }
            if (ItemStack.isSameItemSameTags(slot, stack) && slot.getCount() + stack.getCount() <= slot.getMaxStackSize()) {
                slot.grow(stack.getCount());
                return;
            }
        }
        // 背包满 → 丢在地上
        companion.spawnAtLocation(stack);
    }
}
