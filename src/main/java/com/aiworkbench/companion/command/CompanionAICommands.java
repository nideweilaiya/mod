package com.aiworkbench.companion.command;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.CompanionConfig;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLLoader;

/**
 * AI 交互子命令：/companion chat &lt;message&gt;, model [name], gui
 */
public class CompanionAICommands {

    public static void register(LiteralArgumentBuilder<CommandSourceStack> parent) {
        parent.then(Commands.literal("chat").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> chatWithAI(ctx.getSource(), StringArgumentType.getString(ctx, "message")))))
                .then(Commands.literal("model")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> setModel(ctx.getSource(), StringArgumentType.getString(ctx, "name"))))
                        .executes(ctx -> showModel(ctx.getSource())))
                .then(Commands.literal("gui")
                        .executes(ctx -> openGUI(ctx.getSource())));
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

        var ai = AICompanionMod.aiManager.getAI(companion);
        ai.sendMessage(message).thenAccept(response -> {
            AICompanionMod.server.execute(() -> {
                player.sendSystemMessage(Component.literal("§d[小助手]§f: " + response));
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

        CompanionConfig.setModel(player.getUUID(), modelName);

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

        // 专用服务器上没有 GUI，提示用快捷键
        if (!FMLLoader.getDist().isClient()) {
            source.sendSuccess(() -> Component.literal("§e请在客户端按 C 键打开同伴面板"), false);
            return 1;
        }

        openSettingsScreen();
        return 1;
    }

    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static void openSettingsScreen() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null) {
            mc.setScreen(new com.aiworkbench.companion.client.gui.CompanionSettingsScreen(mc.screen));
        }
    }
}
