package com.aiworkbench.companion.inventory;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.SlotItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 同伴背包 Container — 基于 Forge 标准 AbstractContainerMenu + PacketBuffer 实现。
 * <p>
 * 通过实体 ID 在服务端和客户端之间传递同伴引用，支持单人游戏和专用服务器。
 * <p>
 * 槽位布局（共 69 格）：
 *   0-5:   装备槽（主手、副手、头、胸、腿、脚）
 *   6-32:  同伴背包（9×3）
 *   33-59: 玩家背包（9×3）
 *   60-68: 玩家快捷栏（9）
 */
public class CompanionContainer extends AbstractContainerMenu {

    @Nullable
    private final AutomatonEntity companion;
    private final IItemHandlerModifiable companionInvHandler;

    /**
     * 通过实体 ID 构造（服务端和客户端通用）。
     * <p>
     * 槽位数量始终固定为69格（装备6+同伴背包27+玩家背包27+快捷栏9），
     * 无论 companion 是否找到。客户端找不到同伴时使用空Handler占位。
     */
    public CompanionContainer(int containerId, Inventory playerInv, int companionEntityId) {
        super(AICompanionMod.COMPANION_CONTAINER.get(), containerId);
        this.companion = findCompanion(playerInv.player.level(), playerInv.player, companionEntityId);
        this.companionInvHandler = companion != null ? new CompanionItemHandler(companion) : new EmptyHandler();
        addSlots(playerInv);

        // 背包打开：暂停autoEquip，防止覆盖玩家的手动装备操作
        if (this.companion != null) {
            this.companion.clearInventoryFullLatch();
            this.companion.setSuppressAutoEquip(true);
            AICompanionMod.LOGGER.debug("[CompanionContainer] Opened - autoEquip paused for companion {}",
                this.companion.getUUID());
        }
    }

    @Nullable
    private static AutomatonEntity findCompanion(Level level, Player player, int entityId) {
        // 服务端：直接通过实体ID查找
        if (entityId > 0) {
            Entity entity = level.getEntity(entityId);
            if (entity instanceof AutomatonEntity ae && ae.isAlive()) {
                return ae;
            }
        }
        // 客户端或fallback：扫描玩家附近的同伴实体
        for (AutomatonEntity ae : level.getEntitiesOfClass(AutomatonEntity.class,
                player.getBoundingBox().inflate(32.0))) {
            if (ae.isAlive()
                    && ae.getOwnerUUID() != null
                    && ae.getOwnerUUID().equals(player.getUUID())) {
                return ae;
            }
        }
        AICompanionMod.LOGGER.warn("[CompanionContainer] Companion not found for player {}", player.getName().getString());
        return null;
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
        int equipStartX = 8 + (9 * 18 - 6 * 18) / 2;
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
        int originalCount = original.getCount();
        Slot sourceSlot = this.slots.get(index);

        if (index < 33) {
            // 从装备槽(0-5)或同伴背包(6-32) → 玩家背包(33-68)
            if (!this.moveItemStackTo(stack, 33, 69, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 从玩家背包(33-68) → 先尝试同伴背包，再尝试装备槽
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

        // 计算实际取走的物品数量，传递给 onTake
        int takenCount = originalCount - stack.getCount();
        if (takenCount > 0) {
            ItemStack taken = original.copy();
            taken.setCount(takenCount);
            sourceSlot.onTake(player, taken);
        }
        return stack;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        if (companion == null) return false;
        return companion.isAlive()
            && companion.level().dimension().equals(player.level().dimension())
            && player.distanceToSqr(companion) <= 64.0;
    }

    @Override
    public void removed(@NotNull Player player) {
        super.removed(player);
        // 背包关闭：恢复autoEquip
        if (this.companion != null && this.companion.isAlive()) {
            this.companion.setSuppressAutoEquip(false);
            this.companion.notifyManualEquip(); // 同时设置冷却，防止关闭后立即被覆盖
            AICompanionMod.LOGGER.debug("[CompanionContainer] Closed - autoEquip resumed for companion {}",
                this.companion.getUUID());
        }
    }

    // ==================== 自定义槽位 ====================

    /**
     * 装备槽 — 直接读写 AutomatonEntity 的装备栏。
     * 客户端companion为null时回退到空容器。
     */
    private static class CompanionEquipmentSlot extends Slot {
        @Nullable
        private final AutomatonEntity companion;
        private final EquipmentSlot equipSlot;

        CompanionEquipmentSlot(@Nullable AutomatonEntity companion, EquipmentSlot equipSlot,
                               int index, int x, int y) {
            super(new DummyContainer(1), 0, x, y);
            this.companion = companion;
            this.equipSlot = equipSlot;
        }

        @Override
        public @NotNull ItemStack getItem() {
            return companion != null ? companion.getItemBySlot(equipSlot) : ItemStack.EMPTY;
        }

        @Override
        public void set(@NotNull ItemStack stack) {
            if (companion != null) {
                companion.setItemSlot(equipSlot, stack);
                companion.notifyManualEquip(); // 通知：玩家手动操作了装备
            }
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
                int toRemove = Math.min(amount, current.getCount());
                ItemStack removed = current.copy();
                removed.setCount(toRemove);
                current.shrink(toRemove);
                set(current.isEmpty() ? ItemStack.EMPTY : current);
                return removed;
            }
            return ItemStack.EMPTY;
        }
    }

    /**
     * 空Handler — 客户端找不到同伴时占位，确保槽位数量与服务端一致。
     */
    private static class EmptyHandler implements IItemHandlerModifiable {
        @Override public int getSlots() { return 27; }
        @Override public void setStackInSlot(int slot, @NotNull ItemStack stack) {}
        @Override public @NotNull ItemStack getStackInSlot(int slot) { return ItemStack.EMPTY; }
        @Override public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean sim) { return stack; }
        @Override public @NotNull ItemStack extractItem(int slot, int amount, boolean sim) { return ItemStack.EMPTY; }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, @NotNull ItemStack stack) { return false; }
    }

    /**
     * IItemHandler 适配器 — 将 AutomatonEntity 的内部 NonNullList 包装为标准接口。
     */
    private static class CompanionItemHandler implements IItemHandlerModifiable {
        private final AutomatonEntity companion;

        CompanionItemHandler(AutomatonEntity companion) {
            this.companion = companion;
        }

        @Override
        public int getSlots() { return 27; }

        @Override
        public void setStackInSlot(int slot, @NotNull ItemStack stack) {
            companion.setItem(slot, stack);
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
                if (existing.isEmpty()) companion.setItem(slot, ItemStack.EMPTY);
            }
            return result;
        }

        @Override
        public int getSlotLimit(int slot) { return 64; }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) { return true; }
    }

    /**
     * 空容器实现 — 用于装备槽的 Slot 构造参数占位。
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
