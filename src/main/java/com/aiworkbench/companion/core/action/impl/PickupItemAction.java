package com.aiworkbench.companion.core.action.impl;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.core.action.ActionResult;
import com.aiworkbench.companion.core.action.IAction;
import com.aiworkbench.companion.core.policy.ItemPickupPolicy;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Opportunistically picks up nearby drops without stalling the capability chain.
 */
public class PickupItemAction implements IAction {

    private static final double PICKUP_RANGE = 2.5;
    private static final double SCAN_RANGE = 12.0;
    private static final double APPROACH_RANGE_SQ = SCAN_RANGE * SCAN_RANGE;
    private static final int MAX_EMPTY_TICKS = 1;
    private static final int MAX_TICKS = 80;
    private static final int MAX_APPROACH_TICKS = 60;
    private static final int MAX_PICKUP_FAILURES = 3;

    private final AutomatonEntity entity;
    private final Predicate<ItemEntity> filter;
    private final String profile;

    private int elapsedTicks;
    private int emptyTicks;
    private int consecutivePickupFailures;

    public PickupItemAction(AutomatonEntity entity, Predicate<ItemEntity> filter, String profile) {
        this.entity = entity;
        this.filter = filter;
        this.profile = profile != null ? profile : ItemPickupPolicy.PROFILE_DEFAULT;
    }

    public PickupItemAction(AutomatonEntity entity, Predicate<ItemEntity> filter) {
        this(entity, filter, ItemPickupPolicy.PROFILE_DEFAULT);
    }

    public PickupItemAction(AutomatonEntity entity) {
        this(entity, e -> true);
    }

    public static PickupItemAction byItemId(AutomatonEntity entity, String keyword) {
        return byItemId(entity, keyword, ItemPickupPolicy.PROFILE_DEFAULT);
    }

    public static PickupItemAction byItemId(AutomatonEntity entity, String keyword, String profile) {
        return new PickupItemAction(entity, e -> {
            String id = e.getItem().getItem().builtInRegistryHolder().key().location().getPath();
            return keyword == null || keyword.isBlank() || id.contains(keyword);
        }, profile);
    }

    @Override
    public boolean canExecute(PerceptionData perception) {
        return findMatchingItems().stream().anyMatch(item ->
            !item.hasPickUpDelay() && ItemPickupPolicy.shouldPickup(item.getItem(), entity, profile)
        );
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        elapsedTicks++;
        if (elapsedTicks > MAX_TICKS) {
            resetFailureCounter();
            return ActionResult.SUCCESS;
        }

        List<ItemEntity> items = findMatchingItems();
        if (items.isEmpty()) {
            emptyTicks++;
            if (emptyTicks > MAX_EMPTY_TICKS) {
                resetFailureCounter();
                return ActionResult.SUCCESS;
            }
            return ActionResult.IN_PROGRESS;
        }

        emptyTicks = 0;
        ItemEntity nearest = items.get(0);
        double distSq = entity.distanceToSqr(nearest);
        if (distSq > APPROACH_RANGE_SQ) {
            resetFailureCounter();
            return ActionResult.SUCCESS;
        }

        if (distSq > PICKUP_RANGE * PICKUP_RANGE) {
            Vec3 dir = nearest.position().subtract(entity.position()).normalize();
            Vec3 target = entity.position().add(dir.scale(0.5));
            entity.getNavigation().moveTo(target.x, target.y, target.z, 1.15);
            return elapsedTicks >= MAX_APPROACH_TICKS ? ActionResult.SUCCESS : ActionResult.IN_PROGRESS;
        }

        boolean pickedAny = false;
        boolean policyAllowedAny = false;
        boolean inventoryFullDetected = false;
        for (ItemEntity item : items) {
            if (entity.distanceToSqr(item) > PICKUP_RANGE * PICKUP_RANGE) {
                continue;
            }
            if (item.hasPickUpDelay()) {
                continue;
            }

            ItemStack stack = item.getItem();
            if (stack.isEmpty()) {
                item.discard();
                continue;
            }
            if (!ItemPickupPolicy.shouldPickup(stack, entity, profile)) {
                continue;
            }
            policyAllowedAny = true;

            ItemStack original = stack.copy();
            ItemStack remaining = stack.copy();
            if (entity.addItemToInventory(remaining)) {
                item.discard();
                logPickup(original.getItem().builtInRegistryHolder().key().location().getPath(), original.getCount());
                pickedAny = true;
                continue;
            }

            if (remaining.getCount() < original.getCount()) {
                int pickedCount = original.getCount() - remaining.getCount();
                item.setItem(remaining);
                logPickup(original.getItem().builtInRegistryHolder().key().location().getPath(), pickedCount);
                pickedAny = true;
                continue;
            }

            if (!hasInventoryCapacityFor(original)) {
                AICompanionMod.LOGGER.debug(
                    "[PickupItemAction] Inventory rejected {} x{} for companion {} (emptySlots={})",
                    original.getItem().builtInRegistryHolder().key().location().getPath(),
                    original.getCount(),
                    entity.getUUID(),
                    countEmptyInventorySlots()
                );
                inventoryFullDetected = true;
            }
        }

        if (!policyAllowedAny) {
            resetFailureCounter();
            return ActionResult.SUCCESS;
        }

        if (pickedAny) {
            resetFailureCounter();
            return findMatchingItems().isEmpty() ? ActionResult.SUCCESS : ActionResult.IN_PROGRESS;
        }

        if (inventoryFullDetected) {
            consecutivePickupFailures++;
            if (consecutivePickupFailures >= MAX_PICKUP_FAILURES) {
                return ActionResult.INVENTORY_FULL;
            }
        } else {
            resetFailureCounter();
        }

        return elapsedTicks >= MAX_APPROACH_TICKS ? ActionResult.SUCCESS : ActionResult.IN_PROGRESS;
    }

    @Override
    public int getCost() {
        return 1;
    }

    private List<ItemEntity> findMatchingItems() {
        AABB box = entity.getBoundingBox().inflate(SCAN_RANGE);
        List<ItemEntity> items = entity.level().getEntitiesOfClass(ItemEntity.class, box, e ->
            e.isAlive() && !e.isRemoved() && filter.test(e)
        );
        items.sort(Comparator.comparingDouble(entity::distanceToSqr));
        return items;
    }

    private boolean hasInventoryCapacityFor(ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack slot = entity.getItem(i);
            if (slot.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameTags(slot, stack) && slot.getCount() < slot.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private void resetFailureCounter() {
        consecutivePickupFailures = 0;
    }

    private int countEmptyInventorySlots() {
        int emptySlots = 0;
        for (int i = 0; i < entity.getInventorySize(); i++) {
            if (entity.getItem(i).isEmpty()) {
                emptySlots++;
            }
        }
        return emptySlots;
    }

    private void logPickup(String itemId, int count) {
        if (count <= 0) {
            return;
        }
        AICompanionMod.LOGGER.info("[PickupItemAction] Picked up {} x{}", itemId, count);
    }
}
