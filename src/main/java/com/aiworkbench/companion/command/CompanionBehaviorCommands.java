package com.aiworkbench.companion.command;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.List;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 行为模式子命令：/companion guard, mine, chop, follow, followtoggle, patrol, stop
 */
public class CompanionBehaviorCommands {

    private static final List<String> COMMON_GATHER_TARGETS = List.of(
        "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
        "minecraft:coal_ore", "minecraft:deepslate_coal_ore",
        "minecraft:copper_ore", "minecraft:deepslate_copper_ore",
        "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
        "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore",
        "minecraft:gold_ore", "minecraft:deepslate_gold_ore",
        "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
        "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore",
        "minecraft:ancient_debris", "minecraft:nether_gold_ore",
        "minecraft:nether_quartz_ore",
        "minecraft:oak_log", "minecraft:birch_log", "minecraft:spruce_log",
        "minecraft:jungle_log", "minecraft:acacia_log", "minecraft:dark_oak_log",
        "minecraft:stone", "minecraft:deepslate", "minecraft:sand", "minecraft:gravel"
    );

    public static void register(LiteralArgumentBuilder<CommandSourceStack> parent) {
        parent.then(Commands.literal("guard")
                        .executes(ctx -> toggleGuard(ctx.getSource())))
                .then(Commands.literal("pickup").executes(ctx -> togglePickup(ctx.getSource())))
                .then(Commands.literal("auto").executes(ctx -> toggleAuto(ctx.getSource())))
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
                                                StringArgumentType.getString(ctx, "resources")))))
                        .then(Commands.literal("target")
                                .then(Commands.argument("block", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            for (String id : COMMON_GATHER_TARGETS) {
                                                String remaining = builder.getRemaining().toLowerCase();
                                                if (id.contains(remaining)) builder.suggest(id);
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> setGatherTarget(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "block")))))
                        .then(Commands.literal("info")
                                .executes(ctx -> showGatherInfo(ctx.getSource()))))
                .then(Commands.literal("farm")
                        .executes(ctx -> toggleFarm(ctx.getSource())))
                .then(Commands.literal("mine")
                        .executes(ctx -> toggleMine(ctx.getSource())))
                .then(Commands.literal("chop")
                        .executes(ctx -> toggleChop(ctx.getSource())))
                .then(Commands.literal("follow")
                        .executes(ctx -> setFollowMode(ctx.getSource())))
                .then(Commands.literal("idle")
                        .executes(ctx -> setIdleMode(ctx.getSource())))
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
                        .then(Commands.literal("pickup").executes(ctx -> togglePickup(ctx.getSource())))
                .then(Commands.literal("auto").executes(ctx -> toggleAuto(ctx.getSource())))
                .then(Commands.literal("gather")
                                .executes(ctx -> testGather(ctx.getSource()))))
                .then(Commands.literal("build")
                        .then(Commands.argument("blueprint", StringArgumentType.greedyString())
                                .executes(ctx -> startBuild(ctx.getSource(), StringArgumentType.getString(ctx, "blueprint"))))
                        .executes(ctx -> listBlueprints(ctx.getSource())));
    }

    // ==================== 建造命令 ====================

    private static int startBuild(CommandSourceStack source, String blueprintName) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("§c你没有同伴"));
            return 0;
        }

        com.aiworkbench.companion.building.BuildingBlueprint bp =
            com.aiworkbench.companion.building.BlueprintLibrary.get(blueprintName);
        if (bp == null) {
            source.sendSuccess(() -> Component.literal("§c找不到蓝图: " + blueprintName +
                "\n§7可用: " + String.join(", ", com.aiworkbench.companion.building.BlueprintLibrary.getNames())), false);
            return 0;
        }

        var missing = com.aiworkbench.companion.building.MaterialGatherer.checkMissing(companion, bp);
        if (!missing.isEmpty()) {
            String desc = com.aiworkbench.companion.building.MaterialGatherer.describeMissing(missing);
            source.sendSuccess(() -> Component.literal(desc + "\n§7预计需 " + bp.totalBlocks() + " 个方块"), false);
        }

        var action = new com.aiworkbench.companion.skill.atomic.BuildStructureAction(bp);
        com.aiworkbench.companion.skill.SkillAction skillAction =
            new com.aiworkbench.companion.skill.SkillAction(List.of(action), false);
        com.aiworkbench.companion.skill.Skill skill = new com.aiworkbench.companion.skill.Skill(
            "build_" + bp.name, "建造" + bp.name, List.of(), skillAction,
            com.aiworkbench.companion.skill.SkillCategory.INTERACTION, false);
        companion.getSkillEngine().startSkill(skill, companion);
        companion.showDialogue("§6🏗 建造: " + bp.name, 80);
        source.sendSuccess(() -> Component.literal("§a开始建造 §6" + bp.name + " §7(" + bp.totalBlocks() + "块)"), false);
        return 1;
    }

    private static int listBlueprints(CommandSourceStack source) {
        var names = com.aiworkbench.companion.building.BlueprintLibrary.listAll();
        source.sendSuccess(() -> Component.literal("§6🏗 可用蓝图:\n§7" + String.join("\n§7", names)), false);
        return 1;
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
        return setCompanionMode(source, "mine", "§6[Mine] 已切换到挖矿模式");
    }

    private static int toggleChop(CommandSourceStack source) {
        return setCompanionMode(source, "chop", "§2[Chop] 已切换到砍树模式");
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
        companion.setCompanionMode("follow");

        if (wasInTaskMode) {
            source.sendSuccess(() -> Component.literal("§a[Follow] Companion is now following you!"), true);
        } else {
            source.sendSuccess(() -> Component.literal("§a[Follow] Companion is already following you."), true);
        }

        return 1;
    }

    private static int setIdleMode(CommandSourceStack source) {
        return setCompanionMode(source, "idle", "§7[Idle] 已切换到待机模式");
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

        companion.setCompanionMode(enable ? "autonomous" : "follow");
        String msg = enable
                ? "§a自主模式已开启 - 同伴将自行决策"
                : "§7自主模式已关闭 - 同伴等待你的指令";
        source.sendSuccess(() -> Component.literal(msg), true);
        return 1;
    }

    private static int setCompanionMode(CommandSourceStack source, String modeId, String successMessage) {
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

        companion.setCompanionMode(modeId);
        source.sendSuccess(() -> Component.literal(successMessage), true);
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

    private static int setGatherTarget(CommandSourceStack source, String blockId) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("必须由玩家执行")); return 0; }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
            return 0;
        }

        companion.setGatherTargetBlock(blockId);
        source.sendSuccess(() -> Component.literal("§e🎯 指定采集目标: " + blockId), true);
        return 1;
    }

    private static int showGatherInfo(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("必须由玩家执行")); return 0; }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
            return 0;
        }

        String filter = companion.getGatherFilter();
        String target = companion.getGatherTargetBlock();
        java.util.Set<String> priorities = companion.getGatherPriorityResources();
        String filterDesc = switch (filter) {
            case "ores" -> "仅矿石";
            case "wood" -> "仅木材";
            default -> "全部资源";
        };
        String targetDesc = (target != null && !target.isEmpty()) ? target : "无";
        String priorityDesc = priorities.isEmpty() ? "默认" : String.join(", ", priorities);

        source.sendSuccess(() -> Component.literal("§6⛏ 采集配置"), false);
        source.sendSuccess(() -> Component.literal("  §e类型: §f" + filterDesc), false);
        source.sendSuccess(() -> Component.literal("  §e目标: §f" + targetDesc), false);
        source.sendSuccess(() -> Component.literal("  §e优先级: §f" + priorityDesc), false);
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

    private static int toggleFarm(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("必须由玩家执行")); return 0; }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
            return 0;
        }

        boolean enabled = !companion.isFarmModeEnabled();
        companion.setFarmModeEnabled(enabled);
        source.sendSuccess(() -> Component.literal(enabled
                ? "§a🌾 种植模式开启 — 同伴将自动收割和补种作物"
                : "§7种植模式已关闭"), true);
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

    private static int togglePickup(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("必须由玩家执行")); return 0; }
        AutomatonEntity c = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (c == null) { source.sendFailure(Component.literal("没有同伴")); return 0; }
        boolean now = !c.isGatherModeEnabled();
        c.setGatherModeEnabled(now);
        source.sendSuccess(() -> Component.literal(now ? "§e拾取模式已开启" : "§7拾取模式已关闭"), false);
        return 1;
    }

    private static int toggleAuto(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("必须由玩家执行")); return 0; }
        AutomatonEntity c = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (c == null) { source.sendFailure(Component.literal("没有同伴")); return 0; }
        boolean now = !c.isGuardModeEnabled();
        c.setGuardModeEnabled(now);
        source.sendSuccess(() -> Component.literal(now ? "§a自主模式已开启" : "§7自主模式已关闭"), false);
        return 1;
    }
}
