package com.aiworkbench.companion.economy;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * 交易评估器 —— 评估村民交易的性价比 (0~10分)。
 */
public class TradeEvaluator {

    /**
     * 评估单个交易的价值。
     * @return 0~10 分，分数越高越值得交易
     */
    public static int score(MerchantOffer offer) {
        ItemStack costA = offer.getCostA();
        ItemStack costB = offer.getCostB();
        ItemStack result = offer.getResult();

        int score = 5; // 基础分

        // 买家交易：绿宝石→有用物品
        if (isEmerald(costA) && !isEmerald(result)) {
            score += scoreItem(result) - 1;
        }
        // 卖家交易：物品→绿宝石
        else if (!isEmerald(costA) && isEmerald(result)) {
            score += scoreAsIncome(costA);
        }
        // 物物交换
        else if (isEmerald(costA) && isEmerald(result)) {
            score = 0; // 绿宝石换绿宝石，没意义
        }

        // 二次原料检查
        if (!costB.isEmpty() && isEmerald(costB)) {
            score -= 2;
        }

        // 价格调整
        int emeraldCost = isEmerald(costA) ? costA.getCount() : 0;
        if (!costB.isEmpty() && isEmerald(costB)) emeraldCost += costB.getCount();
        if (emeraldCost > 0 && !isEmerald(result)) {
            // 太贵的不买
            if (emeraldCost > 30) score -= 3;
            else if (emeraldCost > 20) score -= 1;
        }

        // 交易次数用完的不考虑
        if (offer.isOutOfStock() || offer.getMaxUses() <= 0) {
            score = 0;
        }

        return Math.max(0, Math.min(10, score));
    }

    /** 物品价值分 (0~5) */
    private static int scoreItem(ItemStack stack) {
        var item = stack.getItem();
        if (item == Items.DIAMOND_PICKAXE || item == Items.DIAMOND_SWORD || item == Items.DIAMOND_AXE) return 5;
        if (item == Items.IRON_PICKAXE || item == Items.IRON_SWORD || item == Items.IRON_AXE) return 4;
        if (item == Items.ENCHANTED_BOOK) return 5;
        if (item == Items.DIAMOND || item == Items.EMERALD) return 3;
        if (item == Items.GOLDEN_CARROT || item == Items.GOLDEN_APPLE) return 4;
        if (item == Items.ENDER_PEARL) return 4;
        if (item == Items.ARROW) return 2;
        if (item == Items.COOKED_BEEF || item == Items.COOKED_PORKCHOP) return 3;
        if (item == Items.BREAD) return 2;
        if (item == Items.SADDLE) return 2;
        if (item == Items.NAME_TAG) return 2;
        if (item == Items.EXPERIENCE_BOTTLE) return 2;
        if (item == Items.GLOWSTONE || item == Items.REDSTONE) return 2;
        if (item == Items.BELL) return 0; // 铃铛没用
        return 1;
    }

    /** 卖出物品换绿宝石的收益分 */
    private static int scoreAsIncome(ItemStack cost) {
        var item = cost.getItem();
        // 常见廉价物品→绿宝石 是好交易
        if (item == Items.WHEAT || item == Items.POTATO || item == Items.CARROT) return 3;
        if (item == Items.COAL || item == Items.IRON_INGOT) return 2;
        if (item == Items.STICK) return 1; // 木棍太廉价
        if (item == Items.STRING || item == Items.ROTTEN_FLESH) return 2;
        if (item == Items.PAPER) return 2;
        if (item == Items.LEATHER) return 1;
        if (item == Items.GOLD_INGOT || item == Items.DIAMOND) return -1; // 赔本
        return 1;
    }

    private static boolean isEmerald(ItemStack stack) {
        return stack.getItem() == Items.EMERALD;
    }

    /** 描述交易内容 */
    public static String describe(MerchantOffer offer, int score) {
        String result = offer.getResult().getCount() + "x" + offer.getResult().getDisplayName().getString();
        String cost1 = offer.getCostA().getCount() + "x" + offer.getCostA().getDisplayName().getString();
        String cost2 = offer.getCostB().isEmpty() ? "" : " + " + offer.getCostB().getCount() + "x" + offer.getCostB().getDisplayName().getString();
        return String.format("[%d分] %s ← %s%s", score, result, cost1, cost2);
    }
}
