package com.aiworkbench.companion.command;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.mojang.brigadier.arguments.StringArgumentType;
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
                .then(Commands.literal("gather")
                        .executes(ctx -> toggleGather(ctx.getSource()))
                        .then(Commands.literal("ores")
                                .executes(ctx -> setGatherFilter(ctx.getSource(), "ores")))
                        .then(Commands.literal("wood")
                                .executes(ctx -> setGatherFilter(ctx.getSource(), "wood")))
                        .then(Commands.literal("all")
                                .executes(ctx -> setGatherFilter(ctx.getSource(), "all")))
                        .then(Commands.literal("priority")
                                .then(Commands.argument("resources", StringArgumentType.greedyString())
                                        .executes(ctx -> setGatherPriority(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "resources"))))))
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
                        .executes(ctx -> stopMovement(ctx.getSource())))
                .then(Commands.literal("autonomous")
                        .then(Commands.literal("on")
                                .executes(ctx -> setAutonomous(ctx.getSource(), true)))
                        .then(Commands.literal("off")
                                .executes(ctx -> setAutonomous(ctx.getSource(), false))))
                .then(Commands.literal("confirm")
                        .executes(ctx -> confirmProposal(ctx.getSource())))
                .then(Commands.literal("test")
                        .then(Commands.literal("scan")
                                .executes(ctx -> testScan(ctx.getSource())))
                        .then(Commands.literal("upgrade")
                                .executes(ctx -> testUpgrade(ctx.getSource())))
                        .then(Commands.literal("gather")
                                .executes(ctx -> testGather(ctx.getSource()))));
    }

    private static int toggleGuard(CommandSourceStack source) {
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
        // 废弃：重定向到 gather 模式
        return toggleGather(source);
    }

    private static int toggleChop(CommandSourceStack source) {
        // 废弃：重定向到 gather 模式
        return toggleGather(source);
    }

    private static int setFollowMode(CommandSourceStack source) {
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

        boolean wasInTaskMode = companion.isGuardModeEnabled() || companion.isGatherModeEnabled();
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
            source.sendFailure(Component.literal("必须由玩家执行"));
            return 0;
        }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
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
            source.sendFailure(Component.literal("必须由玩家执行"));
            return 0;
        }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
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
            source.sendFailure(Component.literal("必须由玩家执行"));
            return 0;
        }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
            return 0;
        }

        companion.toggleMovementStopped();
        boolean stopped = companion.isMovementStopped();
        source.sendSuccess(() -> Component.literal(stopped ? "§c[停止] Companion stopped" : "§a[移动] Companion moving"), true);
        return 1;
    }

    private static int setAutonomous(CommandSourceStack source, boolean enable) {
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

        companion.setAutonomousMode(enable);
        String msg = enable
                ? "§a自主模式已开启 - 同伴将自行决策"
                : "§7自主模式已关闭 - 同伴等待你的指令";
        source.sendSuccess(() -> Component.literal(msg), true);
        return 1;
    }

    // ==================== Gather Mode ====================

    private static int toggleGather(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("必须由玩家执行")); return 0; }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
            return 0;
        }

        boolean enabled = !companion.isGatherModeEnabled();
        companion.setGatherModeEnabled(enabled);
        source.sendSuccess(() -> Component.literal(enabled
                ? "§6⛏ 采集模式开启 — 同伴将自动采集周围资源"
                : "§7采集模式已关闭"), true);
        return 1;
    }

    private static int setGatherFilter(CommandSourceStack source, String filter) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("必须由玩家执行")); return 0; }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
            return 0;
        }

        companion.setGatherFilter(filter);
        String desc = switch (filter) {
            case "ores" -> "仅矿石";
            case "wood" -> "仅木材";
            default -> "全部资源";
        };
        source.sendSuccess(() -> Component.literal("§e采集过滤: " + desc), true);
        return 1;
    }

    private static int setGatherPriority(CommandSourceStack source, String resources) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("必须由玩家执行")); return 0; }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
            return 0;
        }

        companion.setGatherPriority(resources);
        source.sendSuccess(() -> Component.literal("§e优先采集: " + resources), true);
        return 1;
    }

    // ==================== 测试工具 ====================

    private static int testScan(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        AutomatonEntity c = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (c == null) { source.sendFailure(Component.literal("没有同伴")); return 0; }

        java.util.List<String> resources = com.aiworkbench.companion.entity.goal.CompanionGatherGoal.getResourceSummary(c, 10);
        source.sendSuccess(() -> Component.literal("§6=== 周围资源扫描 ==="), false);
        if (resources.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7未发现资源"), false);
        } else {
            for (String r : resources)
                source.sendSuccess(() -> Component.literal(" §e" + r), false);
        }
        source.sendSuccess(() -> Component.literal("位置: " + c.blockPosition().toShortString()), false);
        source.sendSuccess(() -> Component.literal("手持: " + c.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND).getDisplayName().getString()), false);
        return 1;
    }

    private static int testUpgrade(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        AutomatonEntity c = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (c == null) { source.sendFailure(Component.literal("没有同伴")); return 0; }

        source.sendSuccess(() -> Component.literal("§6=== 工具升级检查 ==="), false);
        String result = com.aiworkbench.companion.entity.goal.AutoUpgrader.tryUpgrade(c);
        if (result != null) {
            source.sendSuccess(() -> Component.literal("§a✅ 升级成功: " + result), false);
            source.sendSuccess(() -> Component.literal("手持: " + c.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND).getDisplayName().getString()), false);
        } else {
            source.sendSuccess(() -> Component.literal("§7当前工具已是最佳"), false);
        }
        return 1;
    }

    private static int testGather(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        AutomatonEntity c = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (c == null) { source.sendFailure(Component.literal("没有同伴")); return 0; }

        // 开启采集 + 最高日志级别
        c.setGatherModeEnabled(true);
        source.sendSuccess(() -> Component.literal("§a采集模式已开启（测试模式，含完整日志）"), false);
        source.sendSuccess(() -> Component.literal("§7查看日志: tail -f logs/latest.log | grep GatherGoal"), false);
        return 1;
    }

    private static int confirmProposal(CommandSourceStack source) {
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

        if (companion.getPendingProposal() == null) {
            source.sendFailure(Component.literal("§7当前没有待确认的提议"));
            return 0;
        }

        companion.confirmProposal();
        source.sendSuccess(() -> Component.literal("§a已确认提议！同伴开始执行"), true);
        return 1;
    }
}
