package com.aiworkbench.companion.core.action.impl;

import com.aiworkbench.companion.core.action.ActionResult;
import com.aiworkbench.companion.core.action.IAction;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.entity.AutomatonEntity;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

/**
 * 拾取附近掉落物的动作原语。
 *
 * <p>只负责将已经在附近的物品拉入背包。不执行寻路——
 * 距离较远的物品需要先用 MoveToAction 走到附近。</p>
 *
 * <p>微调移动：物品在 2.5 格内但未自动拾取时，向物品方向走一步。
 * 这是无寻路的直线趋近，不替代 MoveToAction。</p>
 *
 * <h3>组合示例</h3>
 * <pre>{@code
 *   // 完整采集原木：
 *   MoveToAction(nearTree) → BreakBlockAction(treePos) → PickupItemAction("log")
 *
 *   // 如果掉落物散落较远，先走到掉落物附近：
 *   MoveToAction(dropPos) → PickupItemAction("log")
 * }</pre>
 */
public class PickupItemAction implements IAction {

    private final AutomatonEntity entity;
    private final Predicate<ItemEntity> filter;

    private int elapsedTicks;
    private int emptyTicks; // 连续无物品的 tick 数

    /** 自动拾取感应范围 */
    private static final double PICKUP_RANGE = 2.5;
    /** 微调趋近范围（稍大于自动拾取范围） */
    private static final double APPROACH_RANGE_SQ = 3.0 * 3.0;
    /** 视为无物品的最长持续时间 */
    private static final int MAX_EMPTY_TICKS = 40;
    /** 最大执行时间 */
    private static final int MAX_TICKS = 200;

    public PickupItemAction(AutomatonEntity entity, Predicate<ItemEntity> filter) {
        this.entity = entity;
        this.filter = filter;
    }

    /** 拾取所有物品（无过滤） */
    public PickupItemAction(AutomatonEntity entity) {
        this(entity, e -> true);
    }

    /** 按物品 ID 关键字过滤（如 "oak_log" 匹配所有原木） */
    public static PickupItemAction byItemId(AutomatonEntity entity, String keyword) {
        return new PickupItemAction(entity, e -> {
            String id = e.getItem().getItem().builtInRegistryHolder()
                .key().location().getPath();
            return id.contains(keyword);
        });
    }

    // ==================== IAction 接口 ====================

    @Override
    public boolean canExecute(PerceptionData perception) {
        return !findMatchingItems().isEmpty();
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        elapsedTicks++;

        if (elapsedTicks > MAX_TICKS) {
            return ActionResult.FAILURE;
        }

        List<ItemEntity> items = findMatchingItems();

        if (items.isEmpty()) {
            emptyTicks++;
            // 连续无物品 → 确认已全部拾取
            if (emptyTicks > MAX_EMPTY_TICKS) {
                return ActionResult.SUCCESS;
            }
            return ActionResult.IN_PROGRESS;
        }

        emptyTicks = 0;

        // 向最近的物品微调趋近
        ItemEntity nearest = items.get(0);
        double distSq = entity.distanceToSqr(nearest);

        if (distSq > APPROACH_RANGE_SQ) {
            // 太远，超出微调范围 → 需要 MoveToAction
            return ActionResult.FAILURE;
        }

        if (distSq > PICKUP_RANGE * PICKUP_RANGE) {
            // 在微调范围内但未到拾取范围 → 直线趋近
            Vec3 dir = nearest.position().subtract(entity.position()).normalize();
            Vec3 target = entity.position().add(dir.scale(0.5));
            entity.getNavigation().moveTo(target.x, target.y, target.z, 0.4);
        }

        // 玩家/伙伴在 Minecraft 中会自动拾取 1.5 格内的物品
        // 只要持续靠近，物品会被自然吸入背包

        return ActionResult.IN_PROGRESS;
    }

    @Override
    public int getCost() {
        return 1; // 拾取是瞬时的，代价极小
    }

    // ==================== 内部方法 ====================

    private List<ItemEntity> findMatchingItems() {
        AABB box = entity.getBoundingBox().inflate(PICKUP_RANGE);
        return entity.level().getEntitiesOfClass(ItemEntity.class, box, e ->
            e.isAlive() && !e.isRemoved() && filter.test(e)
        );
    }
}
