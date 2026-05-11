package com.aiworkbench.companion.skill.atomic;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.AtomicAction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.function.Predicate;

/**
 * 收集附近的掉落物 —— 搜索半径内的 ItemEntity → 逐个走过去拾取。
 * <p>
 * 支持自定义过滤条件（如"只捡矿物"、"只捡特定物品"）。
 * 当范围内没有符合条件的物品时完成。
 */
public class CollectItemsAction implements AtomicAction {

    private static final double PICKUP_DISTANCE_SQ = 2.5 * 2.5;
    private static final int SEARCH_RADIUS = 8;
    private static final int TIMEOUT_TICKS = 600;

    private final Predicate<ItemEntity> filter;
    private ItemEntity target;
    private int tickCounter;
    private int emptySearchTicks;

    public CollectItemsAction(Predicate<ItemEntity> filter) {
        this.filter = filter;
        this.tickCounter = 0;
        this.emptySearchTicks = 0;
    }

    /**
     * 收集所有掉落物（无过滤）。
     */
    public static CollectItemsAction all() {
        return new CollectItemsAction(e -> true);
    }

    /**
     * 收集指定类型的掉落物。
     *
     * @param itemId 物品 ID 包含的字符串（如 "diamond", "iron_ingot"）
     */
    public static CollectItemsAction byId(String itemId) {
        return new CollectItemsAction(e -> {
            String id = e.getItem().getItem().builtInRegistryHolder().key().location().getPath();
            return id.contains(itemId);
        });
    }

    @Override
    public boolean canStart(AutomatonEntity entity) {
        return true;
    }

    @Override
    public boolean tick(AutomatonEntity entity) {
        tickCounter++;

        // 如果当前目标被捡走了，重新找
        if (target == null || !target.isAlive() || target.isRemoved()) {
            target = findNearest(entity);
            if (target == null) {
                emptySearchTicks++;
                // 连续几次没找到就结束
                if (emptySearchTicks > 5) return true;
                return false;
            }
            emptySearchTicks = 0;
        }

        // 看向掉落物
        var pos = target.position();
        entity.getLookControl().setLookAt(pos.x, pos.y, pos.z);

        double distSq = entity.distanceToSqr(target);

        if (distSq > PICKUP_DISTANCE_SQ) {
            // 走过去
            entity.getNavigation().moveTo(target, 0.9);
        } else {
            // 到达，等待自动拾取（玩家附近物品会自动进入背包）
            entity.getNavigation().stop();
            // 标记目标已拾取，下次 tick 会寻找下一个
            target = null;
        }

        // 超时保护
        if (tickCounter > TIMEOUT_TICKS) {
            entity.getNavigation().stop();
            return true;
        }

        return false;
    }

    @Override
    public void stop(AutomatonEntity entity) {
        entity.getNavigation().stop();
        target = null;
        tickCounter = 0;
        emptySearchTicks = 0;
    }

    private ItemEntity findNearest(AutomatonEntity entity) {
        AABB searchBox = entity.getBoundingBox().inflate(SEARCH_RADIUS);
        List<ItemEntity> items = entity.level().getEntitiesOfClass(
                ItemEntity.class, searchBox, e -> {
                    if (!e.isAlive()) return false;
                    return filter.test(e);
                });

        ItemEntity closest = null;
        double closestDist = Double.MAX_VALUE;

        for (ItemEntity item : items) {
            double dist = entity.distanceToSqr(item);
            if (dist < closestDist) {
                closestDist = dist;
                closest = item;
            }
        }

        return closest;
    }

    @Override
    public void reset() {
        target = null;
        tickCounter = 0;
        emptySearchTicks = 0;
    }

    @Override
    public String getDescription() {
        return "收集物品";
    }
}
