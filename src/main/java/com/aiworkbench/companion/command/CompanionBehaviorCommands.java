package com.aiworkbench.companion.command;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 行为模式子命令：/companion guard, mine, chop, follow, followtoggle, patrol, stop
 */
public class CompanionBehaviorCommands {

    public static void register(LiteralArgumentBuilder<CommandSourceStack> parent) {
        parent.then(Commands.literal("guard")
                        .executes(ctx -> toggleGuard(ctx.getSource())))
                .then(Commands.literal("mine")
                        .executes(ctx -> toggleMine(ctx.getSource())))
                .then(Commands.literal("chop")
                        .executes(ctx -> toggleChop(ctx.getSource())))
                .then(Commands.literal("follow")
                        .executes(ctx -> setFollowMode(ctx.getSource())))
                .then(Commands.literal("followtoggle")
                        .executes(ctx -> toggleFollowMode(ctx.getSource())))
                .then(Commands.literal("patrol")
                        .executes(ctx -> togglePatrol(ctx.getSource())))
                .then(Commands.literal("stop")
                        .executes(ctx -> stopMovement(ctx.getSource())));
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

        boolean wasInTaskMode = companion.isGuardModeEnabled() || companion.isMineModeEnabled() || companion.isChopModeEnabled();
        companion.returnToFollow();

        if (wasInTaskMode) {
            source.sendSuccess(() -> Component.literal("§a[Follow] Companion is now following you!"), true);
        } else {
            source.sendSuccess(() -> Component.literal("§a[Follow] Companion is already following you."), true);
        }

        return 1;
    }

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

        companion.toggleFollowMode();
        String modeStr = companion.getCurrentModeString();
        source.sendSuccess(() -> Component.literal("§e[Mode] " + modeStr), true);

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
}
