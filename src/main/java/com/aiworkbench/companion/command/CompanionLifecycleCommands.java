package com.aiworkbench.companion.command;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 生命周期子命令：/companion status, revive, teleport, hide, come, down
 */
public class CompanionLifecycleCommands {

    /**
     * Register non-OP commands: status, teleport, hide, come, down
     */
    public static void registerNonOp(LiteralArgumentBuilder<CommandSourceStack> parent) {
        parent.then(Commands.literal("status")
                        .executes(ctx -> showStatus(ctx.getSource())))
                .then(Commands.literal("teleport")
                        .executes(ctx -> teleportToPlayer(ctx.getSource())))
                .then(Commands.literal("hide")
                        .executes(ctx -> toggleHide(ctx.getSource())))
                .then(Commands.literal("come")
                        .executes(ctx -> comeToPlayer(ctx.getSource())))
                .then(Commands.literal("down")
                        .executes(ctx -> goDown(ctx.getSource())))
                .then(Commands.literal("name")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> setName(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));
    }

    /**
     * Register OP-only commands: revive
     */
    public static void registerAdmin(LiteralArgumentBuilder<CommandSourceStack> parent) {
        parent.then(Commands.literal("revive")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> reviveCompanion(ctx.getSource())));
    }

    private static int showStatus(CommandSourceStack source) {
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

        source.sendSuccess(() -> Component.literal("=== Companion Status ==="), false);
        source.sendSuccess(() -> Component.literal("Mode: " + companion.getCurrentModeString()), false);
        source.sendSuccess(() -> Component.literal("Guard: " + (companion.isGuardModeEnabled() ? "ON" : "OFF")), false);
        source.sendSuccess(() -> Component.literal("Mine: " + (companion.isMineModeEnabled() ? "ON" : "OFF")), false);
        source.sendSuccess(() -> Component.literal("Chop: " + (companion.isChopModeEnabled() ? "ON" : "OFF")), false);
        source.sendSuccess(() -> Component.literal("HP: " + (int)companion.getHealth() + "/" + (int)companion.getMaxHealth()), false);
        // 显示装备
        source.sendSuccess(() -> Component.literal("§6装备:"), false);
        net.minecraft.world.entity.EquipmentSlot[] equipSlots = {
            net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            net.minecraft.world.entity.EquipmentSlot.OFFHAND,
            net.minecraft.world.entity.EquipmentSlot.HEAD,
            net.minecraft.world.entity.EquipmentSlot.CHEST,
            net.minecraft.world.entity.EquipmentSlot.LEGS,
            net.minecraft.world.entity.EquipmentSlot.FEET
        };
        String[] equipNames = {"主手", "副手", "头盔", "胸甲", "护腿", "靴子"};
        for (int i = 0; i < equipSlots.length; i++) {
            net.minecraft.world.item.ItemStack eqStack = companion.getItemBySlot(equipSlots[i]);
            if (!eqStack.isEmpty()) {
                final String name = eqStack.getDisplayName().getString();
                final int idx = i;
                source.sendSuccess(() -> Component.literal("  §e" + equipNames[idx] + "§f: " + name), false);
            }
        }
        source.sendSuccess(() -> Component.literal("Has Items: " + (companion.hasItems() ? "YES" : "NO")), false);
        source.sendSuccess(() -> Component.literal("============================"), false);

        return 1;
    }

    private static int reviveCompanion(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player"));
            return 0;
        }

        UUID playerId = player.getUUID();
        var manager = AICompanionMod.companionManager;

        if (manager == null) {
            source.sendFailure(Component.literal("Companion system not initialized"));
            return 0;
        }

        AutomatonEntity oldCompanion = manager.getCompanion(playerId);

        String characterId = "default_companion";
        int skinType = 0;
        String skinValue = "";
        if (oldCompanion != null) {
            characterId = oldCompanion.getCharacterId();
            skinType = oldCompanion.getSkinType();
            skinValue = oldCompanion.getSkinValue();
        }

        manager.removeCompanion(playerId);

        ServerLevel level = player.serverLevel();
        AutomatonEntity companion = AutomatonEntity.create(level, characterId, player);
        boolean success = level.addFreshEntity(companion);

        if (success) {
            if (skinType == 1) {
                companion.setSkinFromUrl(skinValue);
            } else if (skinType == 2) {
                companion.setSkinFromPlayer(skinValue);
            }

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

    private static int teleportToPlayer(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player"));
            return 0;
        }

        var manager = AICompanionMod.companionManager;
        if (manager == null) {
            source.sendFailure(Component.literal("Companion system not initialized"));
            return 0;
        }

        AutomatonEntity companion = manager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("You don't have a companion NPC"));
            return 0;
        }

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

    private static int setName(CommandSourceStack source, String name) {
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

        // Set custom name with color support
        String coloredName = name.replace("&", "§");
        companion.setCustomName(Component.literal(coloredName));
        companion.setCustomNameVisible(true);
        source.sendSuccess(() -> Component.literal("§a[改名] Companion renamed to: " + coloredName), true);
        AICompanionMod.LOGGER.info("Companion renamed to: {} for player {}", name, player.getName().getString());
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
