package com.aiworkbench.companion.command;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

import com.aiworkbench.companion.manager.CompanionRole;

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
                .then(Commands.literal("level")
                        .executes(ctx -> showLevel(ctx.getSource())))
                .then(Commands.literal("name")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> setName(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                // Pickup commands
                .then(Commands.literal("pickup")
                        .executes(ctx -> togglePickup(ctx.getSource()))
                        .then(Commands.literal("on")
                                .executes(ctx -> setPickup(ctx.getSource(), true)))
                        .then(Commands.literal("off")
                                .executes(ctx -> setPickup(ctx.getSource(), false)))
                        .then(Commands.literal("range")
                                .then(Commands.argument("range", IntegerArgumentType.integer(1, 16))
                                        .executes(ctx -> setPickupRange(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "range")))))
                        .then(Commands.literal("valuable")
                                .executes(ctx -> toggleValuable(ctx.getSource()))))
                // Chat message toggle
                .then(Commands.literal("chatmsg")
                        .then(Commands.literal("on")
                                .executes(ctx -> setChatMsg(ctx.getSource(), true)))
                        .then(Commands.literal("off")
                                .executes(ctx -> setChatMsg(ctx.getSource(), false))))
                // Stats commands
                .then(Commands.literal("stats")
                        .executes(ctx -> showStats(ctx.getSource()))
                        .then(Commands.literal("add")
                                .then(Commands.argument("stat", StringArgumentType.word())
                                        .then(Commands.argument("points", IntegerArgumentType.integer(1, 30))
                                                .executes(ctx -> addStats(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "stat"),
                                                    IntegerArgumentType.getInteger(ctx, "points"))))))
                        .then(Commands.literal("reset")
                                .executes(ctx -> resetStats(ctx.getSource()))))
                // Squad commands
                .then(buildSquadCommands());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildSquadCommands() {
        var node = Commands.literal("squad");
        node.executes(ctx -> squadStatus(ctx.getSource()));
        node.then(Commands.literal("create")
                .then(Commands.argument("role", StringArgumentType.word())
                        .executes(ctx -> squadCreate(ctx.getSource(), StringArgumentType.getString(ctx, "role")))));
        node.then(Commands.literal("dismiss")
                .executes(ctx -> squadDismiss(ctx.getSource())));
        node.then(Commands.literal("list")
                .executes(ctx -> squadStatus(ctx.getSource())));
        node.then(Commands.literal("switch")
                .executes(ctx -> squadSwitch(ctx.getSource())));
        return node;
    }

    // ==================== Squad Commands ====================

    private static int squadStatus(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        var squad = AICompanionMod.companionManager.getSquad(player.getUUID());
        if (squad.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7你没有同伴。使用 §e/companion squad create <角色> §7创建"), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("§6👥 同伴小队 (" + squad.size() + "/" + com.aiworkbench.companion.manager.CompanionManager.MAX_COMPANIONS + ")\n");
        for (var c : squad) {
            String marker = AICompanionMod.companionManager.getCompanion(player.getUUID()) == c ? " §a◀" : "";
            sb.append(String.format("§7  %s §fLv.%d §7%s%s\n", c.getRole().icon, c.getLevel(), c.getRole().chineseName, marker));
        }
        boolean canRecruit = AICompanionMod.companionManager.canRecruitNewCompanion(player.getUUID());
        if (canRecruit && squad.size() < 3) {
            sb.append("§a可以招募新同伴! 使用 §e/companion squad create <角色>");
        } else if (!canRecruit && squad.size() < 3) {
            sb.append("§7需要所有同伴满级(Lv." + com.aiworkbench.companion.entity.AutomatonEntity.MAX_LEVEL + ")才能招募下一个");
        }
        final String msg = sb.toString();
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int squadCreate(CommandSourceStack source, String roleName) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        var manager = AICompanionMod.companionManager;
        if (!manager.canRecruitNewCompanion(player.getUUID())) {
            if (manager.squadSize(player.getUUID()) >= 3) {
                source.sendFailure(Component.literal("§c已达最大同伴数量(3个)!"));
            } else {
                source.sendFailure(Component.literal("§c需要当前同伴满级(Lv." + com.aiworkbench.companion.entity.AutomatonEntity.MAX_LEVEL + ")才能招募新同伴!"));
            }
            return 0;
        }

        CompanionRole role;
        try { role = CompanionRole.valueOf(roleName.toUpperCase()); }
        catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("§c未知角色: " + roleName + "。可用: miner/guard/farmer/builder/explorer/general"));
            return 0;
        }

        ServerLevel level = player.serverLevel();
        AutomatonEntity companion = AutomatonEntity.create(level, role.chineseName + "_companion", player);
        companion.setRole(role);
        if (!level.addFreshEntity(companion)) {
            source.sendFailure(Component.literal("§c生成同伴失败!"));
            return 0;
        }
        manager.addCompanion(player.getUUID(), companion);
        companion.playSpawnParticles();
        companion.showDialogue("§b" + role.icon + " " + role.chineseName + " 报到!", 80);
        source.sendSuccess(() -> Component.literal("§a新同伴已招募: " + role.icon + " " + role.chineseName + " (" + manager.squadSize(player.getUUID()) + "/3)"), false);
        return 1;
    }

    private static int squadDismiss(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        AutomatonEntity active = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (active == null) {
            source.sendFailure(Component.literal("§c没有可解散的同伴"));
            return 0;
        }
        String name = active.getRole().chineseName;
        AICompanionMod.companionManager.removeCompanionById(active.getUUID());
        source.sendSuccess(() -> Component.literal("§e已解散: " + name), false);
        return 1;
    }

    private static int squadSwitch(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        AutomatonEntity next = AICompanionMod.companionManager.cycleActiveCompanion(player.getUUID());
        if (next == null) {
            source.sendFailure(Component.literal("§c没有可切换的同伴"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§a切换到: " + next.getRole().icon + " " + next.getRole().chineseName + " Lv." + next.getLevel()), false);
        return 1;
    }

    /**
     * Register OP-only commands: revive
     */
    public static void registerAdmin(LiteralArgumentBuilder<CommandSourceStack> parent) {
        parent.then(Commands.literal("revive")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> reviveCompanion(ctx.getSource())));
        parent.then(Commands.literal("level")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("set")
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> setLevel(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "level"))))));
    }

    private static int showStatus(CommandSourceStack source) {
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

        source.sendSuccess(() -> Component.literal("=== Companion Status ==="), false);
        source.sendSuccess(() -> Component.literal("模式: " + companion.getCurrentModeString()), false);
        source.sendSuccess(() -> Component.literal("Guard: " + (companion.isGuardModeEnabled() ? "ON" : "OFF")), false);
        source.sendSuccess(() -> Component.literal("Gather: " + (companion.isGatherModeEnabled() ? "ON" : "OFF")), false);
        source.sendSuccess(() -> Component.literal("HP: " + (int)companion.getHealth() + "/" + (int)companion.getMaxHealth()), false);
        // 技能状态
        if (companion.isSkillActive()) {
            source.sendSuccess(() -> Component.literal("§b技能: §e" + companion.getSkillEngine().getCurrentSkillName()
                    + " §7[" + companion.getSkillEngine().getCurrentStepDescription() + "]"), false);
        } else {
            source.sendSuccess(() -> Component.literal("§b技能: §7无"), false);
        }
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
        source.sendSuccess(() -> Component.literal("携带物品: " + (companion.hasItems() ? "YES" : "NO")), false);
        source.sendSuccess(() -> Component.literal("============================"), false);

        return 1;
    }

    private static int reviveCompanion(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("必须由玩家执行"));
            return 0;
        }

        UUID playerId = player.getUUID();
        var manager = AICompanionMod.companionManager;

        if (manager == null) {
            source.sendFailure(Component.literal("同伴系统未初始化"));
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
            source.sendFailure(Component.literal("复活同伴失败"));
            return 0;
        }
    }

    private static int teleportToPlayer(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("必须由玩家执行"));
            return 0;
        }

        var manager = AICompanionMod.companionManager;
        if (manager == null) {
            source.sendFailure(Component.literal("同伴系统未初始化"));
            return 0;
        }

        AutomatonEntity companion = manager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
            return 0;
        }

        // Cross-dimension check
        if (!player.level().dimension().equals(companion.level().dimension())) {
            companion.teleportTo(player.serverLevel(), player.getX(), player.getY(), player.getZ(),
                java.util.Set.of(), player.getYRot(), player.getXRot());
        } else {
            companion.teleportTo(player.getX(), player.getY(), player.getZ());
        }
        companion.setDeltaMovement(0, 0, 0);
        companion.fallDistance = 0;

        source.sendSuccess(() -> Component.literal("§a[TP] Companion teleported to you!"), true);
        AICompanionMod.LOGGER.info("Companion teleported to player {}", player.getName().getString());
        return 1;
    }

    private static int toggleHide(CommandSourceStack source) {
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

        companion.toggleHidden();
        boolean hidden = companion.isHidden();
        source.sendSuccess(() -> Component.literal(hidden ? "§7[隐藏] Companion hidden" : "§a[显示] Companion visible"), true);
        return 1;
    }

    private static int comeToPlayer(CommandSourceStack source) {
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

        companion.teleportToOwner();
        source.sendSuccess(() -> Component.literal("§a[来了] Companion coming!"), true);
        return 1;
    }

    private static int setName(CommandSourceStack source, String name) {
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

        // Set custom name with color support
        String coloredName = name.replace("&", "§");
        companion.setCustomName(Component.literal(coloredName));
        companion.setCustomNameVisible(true);
        source.sendSuccess(() -> Component.literal("§a[改名] Companion renamed to: " + coloredName), true);
        AICompanionMod.LOGGER.info("Companion renamed to: {} for player {}", name, player.getName().getString());
        return 1;
    }

    private static int showLevel(CommandSourceStack source) {
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

        int level = companion.getLevel();
        int xp = companion.getXp();
        int xpToNext = companion.getXpToNext();
        float progress = xpToNext > 0 ? (float) xp / xpToNext * 100 : 0;

        source.sendSuccess(() -> Component.literal("§6=== 同伴等级 ==="), false);
        source.sendSuccess(() -> Component.literal("§e等级: §f" + level + " §7(MAX " + 100 + ")"), false);
        source.sendSuccess(() -> Component.literal("§e经验: §f" + xp + " / " + xpToNext + " §7(" + String.format("%.1f", progress) + "%)"), false);
        source.sendSuccess(() -> Component.literal("§e生命: §f" + (int)companion.getMaxHealth() + " §7(基础 120 + 等级加成 " + ((level-1)*2) + ")"), false);
        source.sendSuccess(() -> Component.literal("§e攻击: §f" + String.format("%.1f", companion.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE).getValue())), false);
        source.sendSuccess(() -> Component.literal("§6================="), false);
        return 1;
    }

    private static int setLevel(CommandSourceStack source, int newLevel) {
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

        companion.setLevel(newLevel);
        source.sendSuccess(() -> Component.literal("§a[等级] 同伴等级已设置为 " + newLevel), true);
        AICompanionMod.LOGGER.info("Companion level set to {} for player {} (admin)", newLevel, player.getName().getString());
        return 1;
    }

    private static int goDown(CommandSourceStack source) {
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

        companion.teleportDown();
        source.sendSuccess(() -> Component.literal("§b[下方] Companion going down!"), true);
        return 1;
    }

    // ==================== Pickup Commands ====================

    private static int togglePickup(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
            return 0;
        }
        boolean on = !companion.isAutoPickupEnabled();
        companion.setAutoPickupEnabled(on);
        source.sendSuccess(() -> Component.literal(on ? "§a自动拾取: 开启" : "§7自动拾取: 关闭"), false);
        return 1;
    }

    private static int setPickup(CommandSourceStack source, boolean enabled) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
            return 0;
        }
        companion.setAutoPickupEnabled(enabled);
        source.sendSuccess(() -> Component.literal(enabled ? "§a自动拾取: 开启" : "§7自动拾取: 关闭"), false);
        return 1;
    }

    private static int setPickupRange(CommandSourceStack source, int range) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
            return 0;
        }
        companion.setPickupRadius(range);
        source.sendSuccess(() -> Component.literal("§a拾取范围: " + range + " 格"), false);
        return 1;
    }

    private static int toggleValuable(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
            return 0;
        }
        boolean valuable = !companion.isPickupOnlyValuable();
        companion.setPickupOnlyValuable(valuable);
        source.sendSuccess(() -> Component.literal(valuable ? "§e贵重模式: 仅拾取贵重物品" : "§a全部模式: 拾取所有物品"), false);
        return 1;
    }

    private static int setChatMsg(CommandSourceStack source, boolean enabled) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
            return 0;
        }
        companion.setChatMsgEnabled(enabled);
        source.sendSuccess(() -> Component.literal(enabled ? "§a聊天消息: 开启" : "§7聊天消息: 关闭（头顶对话仍有效）"), false);
        return 1;
    }

    // ==================== Stats Commands ====================

    private static int showStats(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
            return 0;
        }
        int avail = companion.getAvailablePoints();
        source.sendSuccess(() -> Component.literal("§6=== 同伴属性 ==="), false);
        source.sendSuccess(() -> Component.literal("§e等级: §f" + companion.getLevel()
            + "  §e可用点数: §a" + avail), false);
        source.sendSuccess(() -> Component.literal("§c体力: §f+" + companion.getVitalityPoints()
            + "  §b力量: §f+" + companion.getStrengthPoints()
            + "  §a速度: §f+" + companion.getSpeedPoints()
            + "  §7防御: §f+" + companion.getDefensePoints()), false);
        source.sendSuccess(() -> Component.literal("§7使用 §f/companion stats add <体力|力量|速度|防御> <点数> §7加点"), false);
        if (avail > 0) {
            source.sendSuccess(() -> Component.literal("§e你有 " + avail + " 点可用属性点！"), false);
        }
        return 1;
    }

    private static int addStats(CommandSourceStack source, String stat, int points) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
            return 0;
        }
        boolean ok = companion.allocateStat(stat.toLowerCase(), points);
        if (ok) {
            String displayName = switch (stat.toLowerCase()) {
                case "vitality", "vit", "体力" -> "体力";
                case "strength", "str", "力量" -> "力量";
                case "speed", "spd", "速度" -> "速度";
                case "defense", "def", "防御" -> "防御";
                default -> stat;
            };
            source.sendSuccess(() -> Component.literal("§a✅ " + displayName + " +" + points
                + " (" + companion.getAvailablePoints() + " 点剩余)"), false);
        } else {
            source.sendFailure(Component.literal("§c分配失败: 点数不足或已达上限"));
        }
        return 1;
    }

    private static int resetStats(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有同伴"));
            return 0;
        }
        companion.resetAllStats();
        source.sendSuccess(() -> Component.literal("§e属性点已重置，所有点数已返还"), false);
        return 1;
    }
}
