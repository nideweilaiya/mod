package com.aiworkbench.companion.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

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

        dispatcher.register(base);
    }
}
