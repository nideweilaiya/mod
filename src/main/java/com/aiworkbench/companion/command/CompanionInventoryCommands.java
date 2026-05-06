package com.aiworkbench.companion.command;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLLoader;

/**
 * 背包子命令：/companion inventory, openinv, syncinventory, put, take, takeone,
 * putplayer, takeoneplayer, giveall, givehalf
 */
public class CompanionInventoryCommands {

    public static void register(LiteralArgumentBuilder<CommandSourceStack> parent) {
        parent.then(Commands.literal("inventory").requires(source -> source.hasPermission(2))
                        .executes(ctx -> showInventory(ctx.getSource())))
                .then(Commands.literal("openinv")
                        .executes(ctx -> openInventoryGUI(ctx.getSource())))
                .then(Commands.literal("syncinventory")
                        .executes(ctx -> syncInventory(ctx.getSource())))
                .then(Commands.literal("put")
                        .then(Commands.argument("slot", StringArgumentType.string())
                                .executes(ctx -> putItem(ctx.getSource(), StringArgumentType.getString(ctx, "slot")))))
                .then(Commands.literal("take")
                        .then(Commands.argument("slot", StringArgumentType.string())
                                .executes(ctx -> takeItem(ctx.getSource(), StringArgumentType.getString(ctx, "slot")))))
                .then(Commands.literal("takeone")
                        .then(Commands.argument("slot", StringArgumentType.string())
                                .executes(ctx -> takeOneItem(ctx.getSource(), StringArgumentType.getString(ctx, "slot")))))
                .then(Commands.literal("putplayer")
                        .then(Commands.argument("slot", StringArgumentType.string())
                                .executes(ctx -> putItemToPlayer(ctx.getSource(), StringArgumentType.getString(ctx, "slot")))))
                .then(Commands.literal("takeoneplayer")
                        .then(Commands.argument("slot", StringArgumentType.string())
                                .executes(ctx -> takeOneItemToPlayer(ctx.getSource(), StringArgumentType.getString(ctx, "slot")))))
                .then(Commands.literal("giveall")
                        .executes(ctx -> giveAllItems(ctx.getSource())))
                .then(Commands.literal("givehalf")
                        .executes(ctx -> giveHalfItems(ctx.getSource())))
                .then(Commands.literal("equip")
                        .then(Commands.argument("slot", StringArgumentType.word())
                                .executes(ctx -> equipItem(ctx.getSource(), StringArgumentType.getString(ctx, "slot")))))
                .then(Commands.literal("unequip")
                        .then(Commands.argument("slot", StringArgumentType.word())
                                .executes(ctx -> unequipItem(ctx.getSource(), StringArgumentType.getString(ctx, "slot")))));
    }

    private static int showInventory(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player"));
            return 0;
        }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("You don't have a companion NPC"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("§6=== Companion Inventory (27 slots) ==="), false);

        int itemCount = 0;
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (!stack.isEmpty()) {
                itemCount++;
                final int slot = i;
                final String name = stack.getDisplayName().getString();
                final int qty = stack.getCount();
                source.sendSuccess(() -> Component.literal("  §e[" + slot + "]§f: " + name + " x" + qty), false);
            }
        }

        if (itemCount == 0) {
            source.sendSuccess(() -> Component.literal("  §7(Inventory is empty)"), false);
        }

        source.sendSuccess(() -> Component.literal("§6================================"), false);
        return itemCount;
    }

    private static int openInventoryGUI(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player"));
            return 0;
        }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("You don't have a companion NPC"));
            return 0;
        }

        // Send inventory data as chat messages (client will parse)
        source.sendSuccess(() -> Component.literal("[INV_START]"), false);
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (!stack.isEmpty()) {
                String tagStr = stack.getTag() != null ? ":" + stack.getTag().toString() : "";
                final String msg = "[INV_SLOT:" + i + ":" + stack.getItem().builtInRegistryHolder().key().location() + ":" + stack.getCount() + tagStr + "]";
                source.sendSuccess(() -> Component.literal(msg), false);
            }
        }
        // Send equipment data (virtual slots 27-32)
        sendEquipmentSlots(source, companion);
        source.sendSuccess(() -> Component.literal("[INV_END]"), false);

        // 专用服务器上没有 GUI，跳过客户端代码
        if (FMLLoader.getDist().isClient()) {
            openInventoryScreen();
        }

        return 1;
    }

    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static void openInventoryScreen() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null) {
            mc.setScreen(new com.aiworkbench.companion.client.gui.CompanionInventoryScreen());
        }
    }

    private static int syncInventory(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            return 0;
        }

        source.sendSuccess(() -> Component.literal("[INV_START]"), false);
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (!stack.isEmpty()) {
                String tagStr = stack.getTag() != null ? ":" + stack.getTag().toString() : "";
                final String msg = "[INV_SLOT:" + i + ":" + stack.getItem().builtInRegistryHolder().key().location() + ":" + stack.getCount() + tagStr + "]";
                source.sendSuccess(() -> Component.literal(msg), false);
            }
        }
        source.sendSuccess(() -> Component.literal("[INV_END]"), false);

        return 1;
    }

    private static int putItem(CommandSourceStack source, String slotStr) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player"));
            return 0;
        }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("You don't have a companion NPC"));
            return 0;
        }

        int slot;
        try {
            slot = Integer.parseInt(slotStr);
        } catch (NumberFormatException e) {
            source.sendFailure(Component.literal("Invalid slot number: " + slotStr));
            return 0;
        }

        if (slot < 0 || slot >= companion.getInventorySize()) {
            source.sendFailure(Component.literal("Slot out of range: " + slot));
            return 0;
        }

        ItemStack companionSlot = companion.getItem(slot);
        if (!companionSlot.isEmpty()) {
            source.sendFailure(Component.literal("Companion slot is not empty. Use /companion swap " + slot + " first."));
            return 0;
        }

        // Try selected hotbar slot first, then search entire inventory
        ItemStack playerItem = player.getInventory().getSelected();
        int sourceSlot = player.getInventory().selected;

        if (playerItem.isEmpty()) {
            // Search entire inventory (main + hotbar) for any item
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (!s.isEmpty()) {
                    playerItem = s.copy();
                    sourceSlot = i;
                    break;
                }
            }
            if (playerItem.isEmpty()) {
                source.sendFailure(Component.literal("You are not holding any item"));
                return 0;
            }
            // Take item from the found slot
            player.getInventory().setItem(sourceSlot, ItemStack.EMPTY);
        } else {
            // Take from selected hotbar slot
            player.getInventory().setItem(player.getInventory().selected, ItemStack.EMPTY);
        }

        companion.setItem(slot, playerItem);
        final ItemStack putItem = playerItem;
        source.sendSuccess(() -> Component.literal("Put " + putItem.getDisplayName().getString() + " into companion slot " + slot), true);

        return 1;
    }

    private static int takeItem(CommandSourceStack source, String slotStr) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player"));
            return 0;
        }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("You don't have a companion NPC"));
            return 0;
        }

        int slot;
        try {
            slot = Integer.parseInt(slotStr);
        } catch (NumberFormatException e) {
            source.sendFailure(Component.literal("Invalid slot number: " + slotStr));
            return 0;
        }

        if (slot < 0 || slot >= companion.getInventorySize()) {
            source.sendFailure(Component.literal("Slot out of range: " + slot));
            return 0;
        }

        ItemStack companionItem = companion.getItem(slot);
        if (companionItem.isEmpty()) {
            source.sendFailure(Component.literal("Companion slot is empty"));
            return 0;
        }

        if (!player.getInventory().add(companionItem)) {
            source.sendFailure(Component.literal("Your inventory is full"));
            return 0;
        }

        companion.setItem(slot, ItemStack.EMPTY);
        source.sendSuccess(() -> Component.literal("Took " + companionItem.getDisplayName().getString() + " from companion slot " + slot), true);

        return 1;
    }

    private static int takeOneItem(CommandSourceStack source, String slotStr) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player"));
            return 0;
        }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("You don't have a companion NPC"));
            return 0;
        }

        int slot;
        try {
            slot = Integer.parseInt(slotStr);
        } catch (NumberFormatException e) {
            source.sendFailure(Component.literal("Invalid slot number: " + slotStr));
            return 0;
        }

        if (slot < 0 || slot >= companion.getInventorySize()) {
            source.sendFailure(Component.literal("Slot out of range: " + slot));
            return 0;
        }

        ItemStack companionItem = companion.getItem(slot);
        if (companionItem.isEmpty()) {
            source.sendFailure(Component.literal("Companion slot is empty"));
            return 0;
        }

        ItemStack oneItem = companionItem.split(1);
        if (!player.getInventory().add(oneItem)) {
            companionItem.grow(1);
            source.sendFailure(Component.literal("Your inventory is full"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Took 1 " + companionItem.getDisplayName().getString() + " from slot " + slot), true);
        return 1;
    }

    private static int putItemToPlayer(CommandSourceStack source, String slotStr) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player"));
            return 0;
        }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("You don't have a companion NPC"));
            return 0;
        }

        int slot;
        try {
            slot = Integer.parseInt(slotStr);
        } catch (NumberFormatException e) {
            source.sendFailure(Component.literal("Invalid player inventory slot: " + slotStr));
            return 0;
        }

        if (slot < 0 || slot >= 27) {
            source.sendFailure(Component.literal("Invalid player inventory slot: " + slot));
            return 0;
        }

        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack compItem = companion.getItem(i);
            if (!compItem.isEmpty()) {
                ItemStack playerItem = player.getInventory().getItem(slot);
                if (playerItem.isEmpty() || (playerItem.getItem() == compItem.getItem() && playerItem.getCount() < playerItem.getMaxStackSize())) {
                    int toAdd = Math.min(compItem.getCount(), playerItem.getMaxStackSize() - playerItem.getCount());
                    if (toAdd > 0) {
                        if (playerItem.isEmpty()) {
                            player.getInventory().setItem(slot, new ItemStack(compItem.getItem(), toAdd));
                        } else {
                            playerItem.grow(toAdd);
                        }
                        compItem.shrink(toAdd);
                        if (compItem.isEmpty()) {
                            companion.setItem(i, ItemStack.EMPTY);
                        }
                        source.sendSuccess(() -> Component.literal("Moved " + toAdd + " items to your inventory slot " + slot), true);
                        return 1;
                    }
                }
            }
        }

        source.sendFailure(Component.literal("No matching item found or inventory slot is full"));
        return 0;
    }

    private static int takeOneItemToPlayer(CommandSourceStack source, String slotStr) {
        return takeOneItem(source, slotStr);
    }

    private static int giveAllItems(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player"));
            return 0;
        }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("You don't have a companion NPC"));
            return 0;
        }

        final int[] moved = {0};
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack compItem = companion.getItem(i);
            if (!compItem.isEmpty()) {
                if (player.getInventory().add(compItem)) {
                    companion.setItem(i, ItemStack.EMPTY);
                    moved[0]++;
                }
            }
        }

        if (moved[0] > 0) {
            source.sendSuccess(() -> Component.literal("Moved " + moved[0] + " item stacks to your inventory"), true);
        } else {
            source.sendFailure(Component.literal("Companion inventory is empty or your inventory is full"));
        }

        return moved[0];
    }

    private static int giveHalfItems(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player"));
            return 0;
        }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("You don't have a companion NPC"));
            return 0;
        }

        final int[] moved = {0};
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack compItem = companion.getItem(i);
            if (!compItem.isEmpty()) {
                int half = compItem.getCount() / 2;
                if (half > 0) {
                    ItemStack toMove = compItem.split(half);
                    if (player.getInventory().add(toMove)) {
                        moved[0]++;
                    } else {
                        compItem.grow(half);
                    }
                }
            }
        }

        if (moved[0] > 0) {
            source.sendSuccess(() -> Component.literal("Moved " + moved[0] + " item stacks (half) to your inventory"), true);
        } else {
            source.sendFailure(Component.literal("Companion inventory is empty or your inventory is full"));
        }

        return moved[0];
    }

    // ==================== Equipment Commands ====================

    private static EquipmentSlot parseEquipmentSlot(String name) {
        return switch (name.toLowerCase()) {
            case "mainhand" -> EquipmentSlot.MAINHAND;
            case "offhand" -> EquipmentSlot.OFFHAND;
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            default -> null;
        };
    }

    /**
     * Send equipment slot data using the [INV_SLOT] sync protocol.
     * Equipment slots are mapped to virtual indices 27-32:
     *   27=mainhand, 28=offhand, 29=feet, 30=legs, 31=chest, 32=head
     */
    private static void sendEquipmentSlots(CommandSourceStack source, AutomatonEntity companion) {
        EquipmentSlot[] slots = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.FEET, EquipmentSlot.LEGS,
            EquipmentSlot.CHEST, EquipmentSlot.HEAD
        };
        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = companion.getItemBySlot(slots[i]);
            if (!stack.isEmpty()) {
                String tagStr = stack.getTag() != null ? ":" + stack.getTag().toString() : "";
                int virtualSlot = 27 + i;
                final String msg = "[INV_SLOT:" + virtualSlot + ":" +
                    stack.getItem().builtInRegistryHolder().key().location() + ":" +
                    stack.getCount() + tagStr + "]";
                source.sendSuccess(() -> Component.literal(msg), false);
            }
        }
    }

    private static int equipItem(CommandSourceStack source, String slotStr) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player"));
            return 0;
        }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("You don't have a companion NPC"));
            return 0;
        }

        EquipmentSlot equipSlot = parseEquipmentSlot(slotStr);
        if (equipSlot == null) {
            source.sendFailure(Component.literal("Invalid slot. Use: mainhand, offhand, head, chest, legs, feet"));
            return 0;
        }

        // Check if equipment slot already occupied
        if (!companion.getItemBySlot(equipSlot).isEmpty()) {
            source.sendFailure(Component.literal("Equipment slot already occupied. Use /companion unequip " + slotStr + " first"));
            return 0;
        }

        // Search companion inventory for a matching item
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (stack.isEmpty()) continue;

            // Mob.getEquipmentSlotForItem correctly identifies armor/hand slots
            EquipmentSlot naturalSlot = Mob.getEquipmentSlotForItem(stack);
            if (naturalSlot == equipSlot) {
                companion.setItemSlot(equipSlot, stack.copy());
                companion.setItem(i, ItemStack.EMPTY);
                source.sendSuccess(() -> Component.literal("§aEquipped " + stack.getDisplayName().getString() + " to " + slotStr), true);
                return 1;
            }
        }

        source.sendFailure(Component.literal("No suitable item found in companion inventory for slot: " + slotStr));
        return 0;
    }

    private static int unequipItem(CommandSourceStack source, String slotStr) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player"));
            return 0;
        }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("You don't have a companion NPC"));
            return 0;
        }

        EquipmentSlot equipSlot = parseEquipmentSlot(slotStr);
        if (equipSlot == null) {
            source.sendFailure(Component.literal("Invalid slot. Use: mainhand, offhand, head, chest, legs, feet"));
            return 0;
        }

        ItemStack equipped = companion.getItemBySlot(equipSlot);
        if (equipped.isEmpty()) {
            source.sendFailure(Component.literal("No item equipped in " + slotStr));
            return 0;
        }

        // Find first empty inventory slot
        for (int i = 0; i < companion.getInventorySize(); i++) {
            if (companion.getItem(i).isEmpty()) {
                companion.setItem(i, equipped.copy());
                companion.setItemSlot(equipSlot, ItemStack.EMPTY);
                source.sendSuccess(() -> Component.literal("§aUnequipped " + equipped.getDisplayName().getString() + " from " + slotStr), true);
                return 1;
            }
        }

        source.sendFailure(Component.literal("Companion inventory is full. Cannot unequip."));
        return 0;
    }
}
