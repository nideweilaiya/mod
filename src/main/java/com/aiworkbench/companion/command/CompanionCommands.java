package com.aiworkbench.companion.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * 主命令注册入口 —— 将子命令委托给按功能分组的命令类。
 *
 * 拆分后各模块：
 * - CompanionSkinCommands: skin, list, default
 * - CompanionAICommands: chat, model, gui
 * - CompanionInventoryCommands: inventory, openinv, syncinventory, put, take, takeone, putplayer, takeoneplayer, giveall, givehalf
 * - CompanionBehaviorCommands: guard, mine, chop, follow, followtoggle, patrol, stop
 * - CompanionLifecycleCommands: status, revive, teleport, hide, come, down
 */
public class CompanionCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var base = Commands.literal("companion");

        // Non-OP commands (basic companion interaction for survival players)
        CompanionLifecycleCommands.registerNonOp(base);
        CompanionBehaviorCommands.register(base);

        // OP-only commands (require permission level 2)
        CompanionLifecycleCommands.registerAdmin(base);
        CompanionSkinCommands.register(base);
        CompanionAICommands.register(base);
        CompanionInventoryCommands.register(base);

        // Quick control menu (no OP required)
        base.then(Commands.literal("menu")
                .executes(ctx -> sendControlMenu(ctx.getSource())));

        dispatcher.register(base);
    }

    /**
     * Send a clickable control menu to the player.
     * Each button runs the corresponding /companion subcommand when clicked.
     */
    private static int sendControlMenu(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player"));
            return 0;
        }

        // Build: header
        MutableComponent msg = Component.literal("");
        msg.append(Component.literal("§6§l=== ⚔ 同伴控制菜单 ===\n"));

        // Row 1: Guard | Mine | Chop
        msg.append(makeButton("§a[守护]", "/companion guard", "§7守护模式下自动攻击附近的敌对生物"))
            .append("  ");
        msg.append(makeButton("§e[挖掘]", "/companion mine", "§7自动挖掘周围的矿石"))
            .append("  ");
        msg.append(makeButton("§2[砍树]", "/companion chop", "§7自动砍伐周围的树木"));
        msg.append(Component.literal("\n"));

        // Row 2: Follow | Patrol | Stop
        msg.append(makeButton("§b[跟随]", "/companion follow", "§7回到跟随模式，跟在主人身后"))
            .append("  ");
        msg.append(makeButton("§d[巡逻]", "/companion patrol", "§7在当前位置周围巡逻"))
            .append("  ");
        msg.append(makeButton("§7[停止]", "/companion stop", "§7停止移动，停在原地"));
        msg.append(Component.literal("\n"));

        // Row 3: Teleport | Hide | Status
        msg.append(makeButton("§3[传送]", "/companion teleport", "§7将同伴传送到你身边"))
            .append("  ");
        msg.append(makeButton("§c[隐藏]", "/companion hide", "§7切换同伴的隐藏/显示状态"))
            .append("  ");
        msg.append(makeButton("§6[状态]", "/companion status", "§7查看同伴的详细状态信息"));
        msg.append(Component.literal("\n\n"));

        // Footer hint
        msg.append(Component.literal("§7§o点击按钮快速切换同伴模式"));

        player.sendSystemMessage(msg);
        return 1;
    }

    /**
     * Create a clickable chat button.
     */
    private static MutableComponent makeButton(String label, String command, String hoverText) {
        return Component.literal(label)
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hoverText))));
    }
}
