package com.aiworkbench.companion.core.policy;

import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;

import java.util.Map;

/**
 * Shared pickup policy for capability cleanup and autonomous daily pickup.
 */
public final class ItemPickupPolicy {

    public static final String PROFILE_DEFAULT = "default";
    public static final String PROFILE_WOOD_HARVEST = "wood_harvest";

    private static final int EMPTY_SLOT_COMFORT = 3;
    private static final int SAPLING_STACK_LIMIT = 64;

    private ItemPickupPolicy() {
    }

    public static boolean shouldPickup(ItemStack stack, AutomatonEntity entity, String profile) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String itemId = itemId(stack);
        if (isHighValue(itemId, profile)) {
            return true;
        }
        if (canStackIntoExistingSlot(stack, entity)) {
            return true;
        }
        if (countEmptySlots(entity) >= EMPTY_SLOT_COMFORT) {
            return true;
        }
        return !isLowValue(itemId, stack.getCount(), profile);
    }

    public static boolean shouldPickup(String itemId, int count, AutomatonEntity entity, String profile) {
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        String normalized = normalize(itemId);
        if (isHighValue(normalized, profile)) {
            return true;
        }
        if (canStackIntoExistingSlot(normalized, entity)) {
            return true;
        }
        if (countEmptySlots(entity) >= EMPTY_SLOT_COMFORT) {
            return true;
        }
        return !isLowValue(normalized, count, profile);
    }

    public static boolean shouldPickup(
        String itemId,
        int count,
        Map<String, Integer> inventorySummary,
        String profile
    ) {
        String normalized = normalize(itemId);
        if (normalized.isBlank()) {
            return false;
        }
        if (isHighValue(normalized, profile)) {
            return true;
        }
        if (canStackIntoExistingSlot(normalized, inventorySummary)) {
            return true;
        }
        return !isLowValue(normalized, count, profile);
    }

    public static boolean isHighValue(ItemStack stack, String profile) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        if (item instanceof TieredItem || stack.isEdible()) {
            return true;
        }
        return isHighValue(itemId(stack), profile);
    }

    public static boolean isHighValue(String itemId, String profile) {
        String id = normalize(itemId);
        if (isWoodHarvestProfile(profile) && (isLogLike(id) || "apple".equals(id))) {
            return true;
        }
        return isLogLike(id)
            || id.contains("diamond")
            || id.contains("emerald")
            || id.contains("netherite")
            || id.endsWith("_ingot")
            || id.endsWith("_nugget")
            || id.endsWith("_ore")
            || id.startsWith("raw_")
            || id.contains("tool")
            || id.contains("sword")
            || id.contains("axe")
            || id.contains("pickaxe")
            || id.contains("shovel")
            || id.contains("hoe")
            || id.contains("bow")
            || id.contains("shield")
            || "apple".equals(id)
            || "bread".equals(id)
            || "cooked_beef".equals(id)
            || "cooked_porkchop".equals(id)
            || "cooked_chicken".equals(id);
    }

    public static boolean canStackIntoExistingSlot(ItemStack stack, AutomatonEntity entity) {
        if (stack == null || stack.isEmpty() || entity == null) {
            return false;
        }
        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack slot = entity.getItem(i);
            if (slot.isEmpty()) {
                continue;
            }
            if (ItemStack.isSameItemSameTags(slot, stack) && slot.getCount() < slot.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    public static boolean canStackIntoExistingSlot(String itemId, AutomatonEntity entity) {
        String normalized = normalize(itemId);
        if (normalized.isBlank() || entity == null) {
            return false;
        }
        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack slot = entity.getItem(i);
            if (slot.isEmpty() || slot.getCount() >= slot.getMaxStackSize()) {
                continue;
            }
            if (normalize(itemId(slot)).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    public static boolean canStackIntoExistingSlot(String itemId, Map<String, Integer> inventorySummary) {
        String normalized = normalize(itemId);
        if (normalized.isBlank() || inventorySummary == null) {
            return false;
        }
        Integer count = inventorySummary.get(normalized);
        return count != null && count > 0 && count % 64 != 0;
    }

    public static int countEmptySlots(AutomatonEntity entity) {
        if (entity == null) {
            return 0;
        }
        int emptySlots = 0;
        for (int i = 0; i < entity.getInventorySize(); i++) {
            if (entity.getItem(i).isEmpty()) {
                emptySlots++;
            }
        }
        return emptySlots;
    }

    public static boolean isLowValue(ItemStack stack, String profile) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return isLowValue(itemId(stack), stack.getCount(), profile);
    }

    public static boolean isLowValue(String itemId, int count, String profile) {
        String id = normalize(itemId);
        if ("dirt".equals(id) || "cobblestone".equals(id)) {
            return true;
        }
        return id.endsWith("_sapling") && count >= SAPLING_STACK_LIMIT;
    }

    private static boolean isWoodHarvestProfile(String profile) {
        return PROFILE_WOOD_HARVEST.equals(profile);
    }

    private static boolean isLogLike(String id) {
        return id.contains("_log")
            || id.contains("_stem")
            || id.endsWith("_wood")
            || id.endsWith("_hyphae");
    }

    private static String itemId(ItemStack stack) {
        return stack.getItem().builtInRegistryHolder().key().location().getPath();
    }

    private static String normalize(String itemId) {
        if (itemId == null) {
            return "";
        }
        int colon = itemId.indexOf(':');
        return colon >= 0 ? itemId.substring(colon + 1) : itemId;
    }
}
