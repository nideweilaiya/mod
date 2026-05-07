package com.aiworkbench.companion.inventory;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.SlotItemHandler;
import org.jetbrains.annotations.NotNull;

/**
 * 同伴背包 Container — 基于 AbstractContainerMenu 的标准实现。
 * <p>
 * 槽位布局（共 69 格）：
 *   0-5:   装备槽（主手、副手、头、胸、腿、脚）
 *   6-32:  同伴背包（9×3）
 *   33-59: 玩家背包（9×3）
 *   60-68: 玩家快捷栏（9）
 */
public class CompanionContainer extends AbstractContainerMenu {

    // 静态引用：在 openMenu 前设置，Container 构造时读取
    private static final java.util.Map<java.util.UUID, com.aiworkbench.companion.entity.AutomatonEntity>
        pendingCompanions = new java.util.concurrent.ConcurrentHashMap<>();

    public static void setPendingCompanion(java.util.UUID playerId, com.aiworkbench.companion.entity.AutomatonEntity companion) {
        pendingCompanions.put(playerId, companion);
    }

    private final AutomatonEntity companion;
    private final IItemHandler companionInvHandler;

    /** MenuType 构造器（客户端和服务端通用） */
    public CompanionContainer(int containerId, Inventory playerInv) {
        super(null, containerId);
        // 从静态映射中获取同伴引用
        AutomatonEntity c = pendingCompanions.remove(playerInv.player.getUUID());
        this.companion = c != null ? c : null;
        this.companionInvHandler = companion != null ? new CompanionItemHandler(companion) : null;

        if (companion == null) {
            AICompanionMod.LOGGER.warn("[CompanionContainer] Companion not found for player {}", playerInv.player.getName().getString());
            return;
        }

        addSlots(playerInv);
    }

    /** 直接构造器（服务端 createMenu 使用） */
    public CompanionContainer(int containerId, Inventory playerInv, AutomatonEntity companion) {
        super(null, containerId);
        this.companion = companion;
        this.companionInvHandler = new CompanionItemHandler(companion);
        addSlots(playerInv);
    }

    private void addSlots(Inventory playerInv) {
        int equipY = 18;
        int compInvY = 54;
        int playerInvY = 122;
        int hotbarY = 180;

        // 装备槽 (0-5)：主手、副手、头、胸、腿、脚
        EquipmentSlot[] equipSlots = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET
        };
        int equipStartX = 8 + (9 * 18 - 6 * 18) / 2; // 居中 6 个装备槽
        for (int i = 0; i < equipSlots.length; i++) {
            this.addSlot(new CompanionEquipmentSlot(companion, equipSlots[i], i,
                equipStartX + i * 18, equipY));
        }

        // 同伴背包 (6-32)：27 格
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new SlotItemHandler(companionInvHandler, row * 9 + col,
                    8 + col * 18, compInvY + row * 18));
            }
        }

        // 玩家背包 (33-59)：27 格
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, 9 + row * 9 + col,
                    8 + col * 18, playerInvY + row * 18));
            }
        }

        // 玩家快捷栏 (60-68)：9 格
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, 8 + col * 18, hotbarY));
        }
    }

    // ==================== Shift+点击 快速传输 ====================

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        ItemStack stack = this.slots.get(index).getItem();
        if (stack.isEmpty()) return ItemStack.EMPTY;

        ItemStack original = stack.copy();
        Slot sourceSlot = this.slots.get(index);

        if (index < 33) {
            // 从装备槽(0-5)或同伴背包(6-32) → 玩家背包(33-68)
            if (!this.moveItemStackTo(stack, 33, 69, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 从玩家背包(33-68) → 先尝试同伴背包，再尝试装备槽
            // 直接用 stack 操作，moveItemStackTo 会修改它
            if (this.moveItemStackTo(stack, 6, 33, false)) {
                // 成功放入同伴背包
            } else if (this.moveItemStackTo(stack, 0, 6, false)) {
                // 成功放入装备槽
            } else if (index < 60) {
                // 玩家主背包(33-59) → 快捷栏(60-68)
                if (!this.moveItemStackTo(stack, 60, 69, false)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // 快捷栏(60-68) → 玩家主背包(33-59)
                if (!this.moveItemStackTo(stack, 33, 60, false)) {
                    return ItemStack.EMPTY;
                }
            }
        }

        if (stack.isEmpty()) {
            sourceSlot.set(ItemStack.EMPTY);
        } else {
            sourceSlot.setChanged();
        }

        sourceSlot.onTake(player, stack);
        return original;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        // 同维度且8格内，或不同维度不允许操作
        return companion.isAlive()
            && companion.level().dimension().equals(player.level().dimension())
            && player.distanceToSqr(companion) <= 64.0;
    }

    // ==================== 自定义槽位 ====================

    /**
     * 装备槽 — 直接读写 AutomatonEntity 的装备栏。
     */
    private static class CompanionEquipmentSlot extends Slot {
        private final AutomatonEntity companion;
        private final EquipmentSlot equipSlot;

        CompanionEquipmentSlot(AutomatonEntity companion, EquipmentSlot equipSlot,
                               int index, int x, int y) {
            super(new DummyContainer(1), 0, x, y); // Dummy container, overridden below
            this.companion = companion;
            this.equipSlot = equipSlot;
        }

        @Override
        public @NotNull ItemStack getItem() {
            return companion.getItemBySlot(equipSlot);
        }

        @Override
        public void set(@NotNull ItemStack stack) {
            companion.setItemSlot(equipSlot, stack);
            setChanged();
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            return Mob.getEquipmentSlotForItem(stack) == equipSlot;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public @NotNull ItemStack remove(int amount) {
            ItemStack current = getItem();
            if (!current.isEmpty() && amount > 0) {
                set(ItemStack.EMPTY);
                return current;
            }
            return ItemStack.EMPTY;
        }
    }

    /**
     * IItemHandler 适配器 — 将 AutomatonEntity 的内部 NonNullList 包装为标准接口。
     */
    private static class CompanionItemHandler implements IItemHandler {
        private final AutomatonEntity companion;

        CompanionItemHandler(AutomatonEntity companion) {
            this.companion = companion;
        }

        @Override
        public int getSlots() {
            return 27;
        }

        @Override
        public @NotNull ItemStack getStackInSlot(int slot) {
            return companion.getItem(slot);
        }

        @Override
        public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;

            ItemStack existing = companion.getItem(slot);
            int limit = Math.min(stack.getMaxStackSize(), getSlotLimit(slot));

            if (!existing.isEmpty()) {
                if (!ItemStack.isSameItemSameTags(stack, existing)) return stack;
                int space = limit - existing.getCount();
                if (space <= 0) return stack;
                int toAdd = Math.min(space, stack.getCount());
                if (!simulate) {
                    existing.grow(toAdd);
                    companion.setItem(slot, existing);
                }
                ItemStack remaining = stack.copy();
                remaining.shrink(toAdd);
                return remaining.isEmpty() ? ItemStack.EMPTY : remaining;
            } else {
                int toAdd = Math.min(limit, stack.getCount());
                if (!simulate) {
                    ItemStack copy = stack.copy();
                    copy.setCount(toAdd);
                    companion.setItem(slot, copy);
                }
                ItemStack remaining = stack.copy();
                remaining.shrink(toAdd);
                return remaining.isEmpty() ? ItemStack.EMPTY : remaining;
            }
        }

        @Override
        public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack existing = companion.getItem(slot);
            if (existing.isEmpty()) return ItemStack.EMPTY;

            int toRemove = Math.min(amount, existing.getCount());
            ItemStack result = existing.copy();
            result.setCount(toRemove);

            if (!simulate) {
                existing.shrink(toRemove);
                if (existing.isEmpty()) {
                    companion.setItem(slot, ItemStack.EMPTY);
                }
            }
            return result;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return true;
        }
    }

    /**
     * 空容器实现 — 用于装备槽的 Slot 构造参数占位。
     * 所有方法都通过 CompanionEquipmentSlot 被覆盖，实际数据不会被访问。
     */
    private static class DummyContainer implements net.minecraft.world.Container {
        private final ItemStack[] items;

        DummyContainer(int size) {
            this.items = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                items[i] = ItemStack.EMPTY;
            }
        }

        @Override public int getContainerSize() { return items.length; }
        @Override public boolean isEmpty() { return true; }
        @Override public @NotNull ItemStack getItem(int slot) { return items[slot]; }
        @Override public @NotNull ItemStack removeItem(int slot, int amount) { return ItemStack.EMPTY; }
        @Override public @NotNull ItemStack removeItemNoUpdate(int slot) { return ItemStack.EMPTY; }
        @Override public void setItem(int slot, @NotNull ItemStack stack) { items[slot] = stack; }
        @Override public void setChanged() {}
        @Override public boolean stillValid(@NotNull Player player) { return true; }
        @Override public void clearContent() {}
    }
}
