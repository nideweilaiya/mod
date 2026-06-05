package com.aiworkbench.companion.command;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;

/**
 * 皮肤子命令：/companion skin &lt;name&gt;, list, default
 */
public class CompanionSkinCommands {

    public static void register(LiteralArgumentBuilder<CommandSourceStack> parent) {
        parent.then(Commands.literal("skin").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> setSkin(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("list")
                        .executes(ctx -> listSkins(ctx.getSource())))
                .then(Commands.literal("default")
                        .executes(ctx -> setDefaultSkin(ctx.getSource())));
    }

    private static int setSkin(CommandSourceStack source, String skinName) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("必须由玩家执行"));
            return 0;
        }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
            return 0;
        }

        File skinsDir = new File(
                player.server.getWorldPath(LevelResource.ROOT).toFile(),
                "aicompanion/skins");
        File skinFile = new File(skinsDir, skinName + ".png");

        if (!skinFile.exists()) {
            source.sendFailure(Component.literal("皮肤未找到: " + skinName + ".png"));
            return 0;
        }

        companion.setSkinFromUrl(skinName);
        source.sendSuccess(() -> Component.literal("同伴皮肤设置为: " + skinName), true);
        return 1;
    }

    private static int setDefaultSkin(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("必须由玩家执行"));
            return 0;
        }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
            return 0;
        }

        companion.setDefaultSkin();
        source.sendSuccess(() -> Component.literal("同伴皮肤已恢复默认"), true);
        return 1;
    }

    private static int listSkins(CommandSourceStack source) {
        File worldDir = source.getServer().getWorldPath(LevelResource.ROOT).toFile();
        File skinsDir = new File(worldDir, "aicompanion/skins");

        source.sendSuccess(() -> Component.literal("检查路径: " + worldDir.getAbsolutePath()), false);

        if (!skinsDir.exists() || !skinsDir.isDirectory()) {
            source.sendSuccess(() -> Component.literal("皮肤文件夹不存在: " + skinsDir.getAbsolutePath()), false);
            return 0;
        }

        File[] files = skinsDir.listFiles((dir, name) -> name.endsWith(".png"));
        if (files == null || files.length == 0) {
            source.sendSuccess(() -> Component.literal("皮肤文件夹为空"), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("可用皮肤:"), false);
        for (File f : files) {
            source.sendSuccess(() -> Component.literal("  " + f.getName().replace(".png", "")), false);
        }
        return files.length;
    }
}
