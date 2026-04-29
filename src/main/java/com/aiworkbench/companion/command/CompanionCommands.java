package com.aiworkbench.companion.command;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.manager.CompanionManager;
import com.aiworkbench.companion.CompanionConfig;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.network.CompanionInventorySyncPacket;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import com.aiworkbench.companion.client.gui.CompanionSettingsScreen;
import com.aiworkbench.companion.client.gui.CompanionInventoryScreen;

import java.io.File;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;

/**
 * Companion commands for testing.
 * Usage: /companion skin <name> - set companion skin
 *       /companion list - list available skins
 *       /companion default - reset to default skin
 *       /companion model <name> - set AI model
 *       /companion model - show current AI model
 * Skins must be PNG files in .minecraft/aicompanion/skins/ folder
 */
public class CompanionCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("companion")
                .requires(source -> source.hasPermission(2))
                .then(
                    Commands.literal("skin")
                        .then(
                            Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> setSkin(ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                        )
                )
                .then(
                    Commands.literal("list")
                        .executes(ctx -> listSkins(ctx.getSource()))
                )
                .then(
                    Commands.literal("default")
                        .executes(ctx -> setDefaultSkin(ctx.getSource()))
                )
                .then(
                    Commands.literal("chat")
                        .then(
                            Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> chatWithAI(ctx.getSource(), StringArgumentType.getString(ctx, "message")))
                        )
                )
                .then(
                    Commands.literal("model")
                        .then(
                            Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> setModel(ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                        )
                        .executes(ctx -> showModel(ctx.getSource()))
                )
                .then(
                    Commands.literal("gui")
                        .executes(ctx -> openGUI(ctx.getSource()))
                )
                .then(
                    Commands.literal("inventory")
                        .executes(ctx -> showInventory(ctx.getSource()))
                )
                .then(
                    Commands.literal("openinv")
                        .executes(ctx -> openInventoryGUI(ctx.getSource()))
                )
                .then(
                    Commands.literal("syncinventory")
                        .executes(ctx -> syncInventory(ctx.getSource()))
                )
                .then(
                    Commands.literal("put")
                        .then(
                            Commands.argument("slot", StringArgumentType.string())
                                .executes(ctx -> putItem(ctx.getSource(), StringArgumentType.getString(ctx, "slot")))
                        )
                )
                .then(
                    Commands.literal("take")
                        .then(
                            Commands.argument("slot", StringArgumentType.string())
                                .executes(ctx -> takeItem(ctx.getSource(), StringArgumentType.getString(ctx, "slot")))
                        )
                )
                .then(
                    Commands.literal("takeone")
                        .then(
                            Commands.argument("slot", StringArgumentType.string())
                                .executes(ctx -> takeOneItem(ctx.getSource(), StringArgumentType.getString(ctx, "slot")))
                        )
                )
                .then(
                    Commands.literal("putplayer")
                        .then(
                            Commands.argument("slot", StringArgumentType.string())
                                .executes(ctx -> putItemToPlayer(ctx.getSource(), StringArgumentType.getString(ctx, "slot")))
                        )
                )
                .then(
                    Commands.literal("takeoneplayer")
                        .then(
                            Commands.argument("slot", StringArgumentType.string())
                                .executes(ctx -> takeOneItemToPlayer(ctx.getSource(), StringArgumentType.getString(ctx, "slot")))
                        )
                )
                .then(
                    Commands.literal("giveall")
                        .executes(ctx -> giveAllItems(ctx.getSource()))
                )
                .then(
                    Commands.literal("givehalf")
                        .executes(ctx -> giveHalfItems(ctx.getSource()))
                )
                .then(
                    Commands.literal("guard")
                        .executes(ctx -> toggleGuard(ctx.getSource()))
                )
                .then(
                    Commands.literal("mine")
                        .executes(ctx -> toggleMine(ctx.getSource()))
                )
                .then(
                    Commands.literal("chop")
                        .executes(ctx -> toggleChop(ctx.getSource()))
                )
                .then(
                    Commands.literal("follow")
                        .executes(ctx -> setFollowMode(ctx.getSource()))
                )
                .then(
                    Commands.literal("followtoggle")
                        .executes(ctx -> toggleFollowMode(ctx.getSource()))
                )
                .then(
                    Commands.literal("status")
                        .executes(ctx -> showStatus(ctx.getSource()))
                )
                .then(
                    Commands.literal("revive")
                        .executes(ctx -> reviveCompanion(ctx.getSource()))
                )
                .then(
                    Commands.literal("teleport")
                        .executes(ctx -> teleportToPlayer(ctx.getSource()))
                )
                .then(
                    Commands.literal("hide")
                        .executes(ctx -> toggleHide(ctx.getSource()))
                )
                .then(
                    Commands.literal("stop")
                        .executes(ctx -> stopMovement(ctx.getSource()))
                )
                .then(
                    Commands.literal("patrol")
                        .executes(ctx -> togglePatrol(ctx.getSource()))
                )
                .then(
                    Commands.literal("come")
                        .executes(ctx -> comeToPlayer(ctx.getSource()))
                )
                .then(
                    Commands.literal("down")
                        .executes(ctx -> goDown(ctx.getSource()))
                )
        );
    }

    private static int setSkin(CommandSourceStack source, String skinName) {
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

        // Check if skin file exists
        File skinsDir = new File(
            player.server.getWorldPath(LevelResource.ROOT).toFile(),
            "aicompanion/skins"
        );
        File skinFile = new File(skinsDir, skinName + ".png");

        if (!skinFile.exists()) {
            source.sendFailure(Component.literal("Skin not found: " + skinName + ".png in aicompanion/skins/"));
            return 0;
        }

        companion.setSkinFromUrl(skinName);
        source.sendSuccess(() -> Component.literal("Companion skin set to: " + skinName), true);
        return 1;
    }

    private static int setDefaultSkin(CommandSourceStack source) {
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

        companion.setDefaultSkin();
        source.sendSuccess(() -> Component.literal("Companion skin reset to default"), true);
        return 1;
    }

    private static int listSkins(CommandSourceStack source) {
        File worldDir = source.getServer().getWorldPath(LevelResource.ROOT).toFile();
        File skinsDir = new File(worldDir, "aicompanion/skins");

        source.sendSuccess(() -> Component.literal("Checking path: " + worldDir.getAbsolutePath()), false);

        if (!skinsDir.exists() || !skinsDir.isDirectory()) {
            source.sendSuccess(() -> Component.literal("No skins folder at: " + skinsDir.getAbsolutePath()), false);
            return 0;
        }

        File[] files = skinsDir.listFiles((dir, name) -> name.endsWith(".png"));
        if (files == null || files.length == 0) {
            source.sendSuccess(() -> Component.literal("No skins found in aicompanion/skins/"), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Available skins:"), false);
        for (File f : files) {
            source.sendSuccess(() -> Component.literal("  " + f.getName().replace(".png", "")), false);
        }
        return files.length;
    }

    private static int chatWithAI(CommandSourceStack source, String message) {
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

        source.sendSuccess(() -> Component.literal("§b[你]§f: " + message), false);

        // Send to AI
        var ai = AICompanionMod.aiManager.getAI(companion);
        ai.sendMessage(message).thenAccept(response -> {
            // Send AI response to player
            AICompanionMod.server.execute(() -> {
                player.sendSystemMessage(Component.literal("§d[小助手]§f: " + response));
                // Also show dialogue floating above companion
                companion.showDialogue(response);
                AICompanionMod.LOGGER.info("AI response to " + player.getName().getString() + ": " + response);
            });
        });

        return 1;
    }

    private static int setModel(CommandSourceStack source, String modelName) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player"));
            return 0;
        }

        // Save to config
        CompanionConfig.setModel(player.getUUID(), modelName);

        // Update existing AI instance if any
        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion != null && companion.isAlive()) {
            var ai = AICompanionMod.aiManager.getAI(companion);
            ai.setModel(modelName);
        }

        source.sendSuccess(() -> Component.literal("AI model set to: " + modelName), true);
        return 1;
    }

    private static int showModel(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player"));
            return 0;
        }

        String currentModel = CompanionConfig.getModel(player.getUUID());
        source.sendSuccess(() -> Component.literal("Current AI model: " + currentModel), false);

        // Also check if companion exists and has different AI
        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion != null && companion.isAlive()) {
            var ai = AICompanionMod.aiManager.getAI(companion);
            source.sendSuccess(() -> Component.literal("Active AI model: " + ai.getModel()), false);
        }

        return 1;
    }

    private static int openGUI(CommandSourceStack source) {
        if (source.getPlayer() == null) {
            source.sendFailure(Component.literal("Must be used by a player"));
            return 0;
        }

        // Open GUI on client side
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.screen != null) {
            mc.setScreen(new CompanionSettingsScreen(mc.screen));
        } else if (mc != null) {
            mc.setScreen(new CompanionSettingsScreen(null));
        }

        return 1;
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
                String itemName = stack.getDisplayName().getString();
                int count = stack.getCount();
                final int slot = i;
                final String name = itemName;
                final int qty = count;
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

        // Get companion
        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("You don't have a companion NPC"));
            return 0;
        }

        // Send inventory as special chat messages (client will parse these)
        source.sendSuccess(() -> Component.literal("[INV_START]"), false);
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (!stack.isEmpty()) {
                // Format: [INV_SLOT:slot:id:count] or [INV_SLOT:slot:id:count:tag]
                String tagStr = stack.getTag() != null ? ":" + stack.getTag().toString() : "";
                final String msg = "[INV_SLOT:" + i + ":" + stack.getItem().builtInRegistryHolder().key().location() + ":" + stack.getCount() + tagStr + "]";
                source.sendSuccess(() -> Component.literal(msg), false);
            }
        }
        source.sendSuccess(() -> Component.literal("[INV_END]"), false);

        // Open GUI on client side
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null) {
            mc.setScreen(new CompanionInventoryScreen());
        }

        return 1;
    }

    /**
     * Sync inventory data to client (called when client requests sync)
     */
    private static int syncInventory(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }

        // Get companion
        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            return 0;
        }

        // Send inventory as special chat messages (same format as openInventoryGUI)
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

        // Move item from player hotbar to companion
        ItemStack playerItem = player.getInventory().getSelected();
        if (playerItem.isEmpty()) {
            source.sendFailure(Component.literal("You are not holding any item"));
            return 0;
        }

        ItemStack companionSlot = companion.getItem(slot);
        if (!companionSlot.isEmpty()) {
            source.sendFailure(Component.literal("Companion slot is not empty. Use /companion swap " + slot + " first."));
            return 0;
        }

        companion.setItem(slot, playerItem.copy());
        player.getInventory().setItem(player.getInventory().selected, ItemStack.EMPTY);
        source.sendSuccess(() -> Component.literal("Put " + playerItem.getDisplayName().getString() + " into companion slot " + slot), true);

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

        // Check if player can pick up
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

        // Give one item to player
        ItemStack oneItem = companionItem.split(1);
        if (!player.getInventory().add(oneItem)) {
            // Can't pick up, put it back
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
            source.sendFailure(Component.literal("Invalid slot number: " + slotStr));
            return 0;
        }

        if (slot < 0 || slot >= 27) {
            source.sendFailure(Component.literal("Invalid player inventory slot: " + slot));
            return 0;
        }

        // Try to find and move matching item from companion to player
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
        // Same as takeOneItem for now
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
                        // Put it back if can't add
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

    private static int toggleGuard(CommandSourceStack source) {
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

        boolean currentMode = companion.isGuardModeEnabled();
        companion.setGuardModeEnabled(!currentMode);

        if (!currentMode) {
            source.sendSuccess(() -> Component.literal("§a[Guard] Companion is now defending you!"), true);
        } else {
            source.sendSuccess(() -> Component.literal("§7[Guard] Companion guard mode disabled."), true);
        }

        return 1;
    }

    private static int toggleMine(CommandSourceStack source) {
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

        boolean currentMode = companion.isMineModeEnabled();
        companion.setMineModeEnabled(!currentMode);

        if (!currentMode) {
            source.sendSuccess(() -> Component.literal("§a[Mine] Companion is now mining blocks!"), true);
        } else {
            source.sendSuccess(() -> Component.literal("§7[Mine] Companion mine mode disabled."), true);
        }

        return 1;
    }

    private static int toggleChop(CommandSourceStack source) {
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

        boolean currentMode = companion.isChopModeEnabled();
        companion.setChopModeEnabled(!currentMode);

        if (!currentMode) {
            source.sendSuccess(() -> Component.literal("§a[Chop] Companion is now chopping trees!"), true);
        } else {
            source.sendSuccess(() -> Component.literal("§7[Chop] Companion chop mode disabled."), true);
        }

        return 1;
    }

    private static int setFollowMode(CommandSourceStack source) {
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

        // Use returnToFollow to disable all task modes and return to follow mode
        boolean wasInTaskMode = companion.isGuardModeEnabled() || companion.isMineModeEnabled() || companion.isChopModeEnabled();
        companion.returnToFollow();

        if (wasInTaskMode) {
            source.sendSuccess(() -> Component.literal("§a[Follow] Companion is now following you!"), true);
        } else {
            source.sendSuccess(() -> Component.literal("§a[Follow] Companion is already following you."), true);
        }

        return 1;
    }

    /**
     * Toggle between follow mode and task mode (F key)
     * Cycles: follow -> guard -> mine -> chop -> follow
     */
    private static int toggleFollowMode(CommandSourceStack source) {
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

        boolean nowFollowing = companion.toggleFollowMode();
        String modeStr = companion.getCurrentModeString();
        source.sendSuccess(() -> Component.literal("§e[Mode] " + modeStr), true);

        return 1;
    }

    private static int showStatus(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player"));
            return 0;
        }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("You dont have a companion NPC"));
            return 0;
        }

        String modeStr = companion.getCurrentModeString();
        boolean hasItems = companion.hasItems();

        source.sendSuccess(() -> Component.literal("=== Companion Status ==="), false);
        source.sendSuccess(() -> Component.literal("Mode: " + modeStr), false);
        source.sendSuccess(() -> Component.literal("Guard: " + (companion.isGuardModeEnabled() ? "ON" : "OFF")), false);
        source.sendSuccess(() -> Component.literal("Mine: " + (companion.isMineModeEnabled() ? "ON" : "OFF")), false);
        source.sendSuccess(() -> Component.literal("Chop: " + (companion.isChopModeEnabled() ? "ON" : "OFF")), false);
        source.sendSuccess(() -> Component.literal("Has Items: " + (hasItems ? "YES" : "NO")), false);
        source.sendSuccess(() -> Component.literal("============================"), false);

        return 1;
    }

    /**
     * 复活同伴 - 保持原有的characterId和皮肤设置
     */
    private static int reviveCompanion(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player"));
            return 0;
        }

        UUID playerId = player.getUUID();
        CompanionManager manager = AICompanionMod.companionManager;

        if (manager == null) {
            source.sendFailure(Component.literal("Companion system not initialized"));
            return 0;
        }

        AutomatonEntity oldCompanion = manager.getCompanion(playerId);

        // 获取原有设置
        String characterId = "default_companion";
        int skinType = 0;
        String skinValue = "";
        if (oldCompanion != null) {
            characterId = oldCompanion.getCharacterId();
            skinType = oldCompanion.getSkinType();
            skinValue = oldCompanion.getSkinValue();
        }

        // 移除旧的死亡实体
        manager.removeCompanion(playerId);

        // 生成新同伴，保留原有设置
        ServerLevel level = player.serverLevel();
        AutomatonEntity companion = AutomatonEntity.create(level, characterId, player);
        boolean success = level.addFreshEntity(companion);

        if (success) {
            // 恢复皮肤设置
            if (skinType == 1) {  // URL skin
                companion.setSkinFromUrl(skinValue);
            } else if (skinType == 2) {  // Player name skin
                companion.setSkinFromPlayer(skinValue);
            }
            // 否则使用默认皮肤

            manager.addCompanion(playerId, companion);
            source.sendSuccess(() -> Component.literal("§a[Revive] Your companion has been revived!"), true);
            AICompanionMod.LOGGER.info("Companion revived for player {} (characterId={}, skinType={})",
                player.getName().getString(), characterId, skinType);
            return 1;
        } else {
            source.sendFailure(Component.literal("Failed to revive companion"));
            return 0;
        }
    }

    /**
     * 传送同伴到玩家身边
     */
    private static int teleportToPlayer(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player"));
            return 0;
        }

        UUID playerId = player.getUUID();
        CompanionManager manager = AICompanionMod.companionManager;

        if (manager == null) {
            source.sendFailure(Component.literal("Companion system not initialized"));
            return 0;
        }

        AutomatonEntity companion = manager.getCompanion(playerId);
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("You don't have a companion NPC"));
            return 0;
        }

        // 传送到玩家身边
        companion.teleportTo(player.getX(), player.getY(), player.getZ());
        companion.setDeltaMovement(0, 0, 0);
        companion.fallDistance = 0;

        source.sendSuccess(() -> Component.literal("§a[TP] Companion teleported to you!"), true);
        AICompanionMod.LOGGER.info("Companion teleported to player {}", player.getName().getString());
        return 1;
    }

    private static int toggleHide(CommandSourceStack source) {
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

        companion.toggleHidden();
        boolean hidden = companion.isHidden();
        source.sendSuccess(() -> Component.literal(hidden ? "§7[隐藏] Companion hidden" : "§a[显示] Companion visible"), true);
        return 1;
    }

    private static int stopMovement(CommandSourceStack source) {
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

        companion.toggleMovementStopped();
        boolean stopped = companion.isMovementStopped();
        source.sendSuccess(() -> Component.literal(stopped ? "§c[停止] Companion stopped" : "§a[移动] Companion moving"), true);
        return 1;
    }

    private static int togglePatrol(CommandSourceStack source) {
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

        boolean enabled = !companion.isPatrolModeEnabled();
        companion.setPatrolModeEnabled(enabled);
        source.sendSuccess(() -> Component.literal(enabled ? "§a[巡逻] Patrol mode enabled" : "§7[巡逻] Patrol mode disabled"), true);
        return 1;
    }

    private static int comeToPlayer(CommandSourceStack source) {
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

        companion.teleportToOwner();
        source.sendSuccess(() -> Component.literal("§a[来了] Companion coming!"), true);
        return 1;
    }

    private static int goDown(CommandSourceStack source) {
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

        companion.teleportDown();
        source.sendSuccess(() -> Component.literal("§b[下方] Companion going down!"), true);
        return 1;
    }
}
