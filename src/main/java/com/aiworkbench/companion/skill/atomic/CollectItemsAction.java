package com.aiworkbench.companion.skill.atomic;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.AtomicAction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.function.Predicate;

/**
 * 收集附近的掉落物 — v2.1 降频搜索
 *
 * 核心改动：
 * 1. 搜索降频：每 20 tick 才搜索一次，不每 tick 查实体列表
 * 2. 非搜索 tick 复用当前目标的位置继续导航
 */
public class CollectItemsAction implements AtomicAction {

    private static final double PICKUP_DISTANCE_SQ = 2.5 * 2.5;
    private static final int SEARCH_RADIUS = 8;
    private static final int TIMEOUT_TICKS = 600;
    private static final int SEARCH_INTERVAL = 20; // 每20tick搜索一次

    private final Predicate<ItemEntity> filter;
    private ItemEntity target;
    private int tickCounter;
    private int searchTimer;

    public CollectItemsAction(Predicate<ItemEntity> filter) {
        this.filter = filter;
        this.tickCounter = 0;
        this.searchTimer = 0;
    }

    public static CollectItemsAction all() {
        return new CollectItemsAction(e -> true);
    }

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

        // ── 降频搜索 ──
        searchTimer++;
        if (searchTimer >= SEARCH_INTERVAL) {
            searchTimer = 0;
            // 当前目标无效 → 重新找
            if (target == null || !target.isAlive() || target.isRemoved()) {
                target = findNearest(entity);
            }
        }

        if (target == null) {
            // 附近没有物品，超过20tick无目标就结束
            if (tickCounter > 20) return true;
            return false;
        }

        // 看向掉落物
        var pos = target.position();
        entity.getLookControl().setLookAt(pos.x, pos.y, pos.z);

        double distSq = entity.distanceToSqr(target);

        if (distSq > PICKUP_DISTANCE_SQ) {
            entity.getNavigation().moveTo(target, 0.9);
        } else {
            entity.getNavigation().stop();
            // 到达，标记拾取
            target = null;
        }

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
        searchTimer = 0;
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
        searchTimer = 0;
    }

    @Override
    public String getDescription() {
        return "收集物品";
    }
}
