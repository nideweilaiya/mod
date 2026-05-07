package com.aiworkbench.companion.skill;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * /companion skill 子命令 —— 管理技能的学习、执行和查看。
 * <p>
 * 可用命令（无需 OP）：
 *   /companion skill list           — 列出已学技能
 *   /companion skill info <name>    — 查看技能详情
 *   /companion skill learn <name>   — 学习/执行技能
 *   /companion skill cancel         — 取消当前技能
 * <p>
 * 需要 OP：
 *   /companion skill forget <name>  — 遗忘技能
 *   /companion skill preset         — 注册所有预制技能
 */
public class SkillCommand {

    /**
     * Register skill subcommands under /companion skill.
     * Called from CompanionCommands.register().
     */
    public static void register(LiteralArgumentBuilder<CommandSourceStack> parent) {
        var skillBase = Commands.literal("skill");

        // === 无需 OP 的子命令 ===
        skillBase.then(Commands.literal("list")
                .executes(ctx -> listSkills(ctx.getSource())));

        skillBase.then(Commands.literal("info")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> skillInfo(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));

        skillBase.then(Commands.literal("learn")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> learnSkill(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));

        skillBase.then(Commands.literal("cancel")
                .executes(ctx -> cancelSkill(ctx.getSource())));

        // === 需要 OP 的子命令（permission level 2）===
        skillBase.then(Commands.literal("forget")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> forgetSkill(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));

        skillBase.then(Commands.literal("preset")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> registerPresets(ctx.getSource())));

        // === LLM 技能生成（无需 OP）===
        skillBase.then(Commands.literal("generate")
                .then(Commands.argument("description", StringArgumentType.greedyString())
                        .executes(ctx -> generateSkill(ctx.getSource(),
                                StringArgumentType.getString(ctx, "description")))));

        parent.then(skillBase);
    }

    // ================ 命令处理 ================

    /** 列出已学技能 */
    private static int listSkills(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        UUID uuid = player.getUUID();
        SkillLibrary lib = getLibrary();
        if (lib == null) return 0;

        List<Skill> skills = lib.getPlayerSkills(uuid);
        player.sendSystemMessage(Component.literal("§6§l=== 已学技能 (" + skills.size() + "个) ==="));

        if (skills.isEmpty()) {
            player.sendSystemMessage(Component.literal("§7还没有学习任何技能。"));
            player.sendSystemMessage(Component.literal("§7可用基础技能："));
            for (Skill preset : lib.getAllPresets()) {
                player.sendSystemMessage(Component.literal(
                        " §e" + preset.getName() + " §7- " + preset.getDescription()));
            }
            player.sendSystemMessage(Component.literal("§7使用 §f/companion skill learn <技能名> §7学习"));
            return 1;
        }

        for (Skill skill : skills) {
            String cat = skill.getCategory().getDisplayName();
            player.sendSystemMessage(Component.literal(
                    " §b" + skill.getName() + " §7[" + cat + "] §8- " + skill.getDescription()));
        }

        // 显示当前正在执行的技能
        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(uuid);
        if (companion != null && companion.isSkillActive()) {
            player.sendSystemMessage(Component.literal(
                    "§e▶ 当前执行: " + companion.getSkillEngine().getCurrentSkillName()));
        }

        return 1;
    }

    /** 查看技能详情 */
    private static int skillInfo(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        SkillLibrary lib = getLibrary();
        if (lib == null) return 0;

        Skill skill = lib.getPlayerSkill(player.getUUID(), name);
        if (skill == null) {
            skill = lib.getPreset(name);
            if (skill == null) {
                player.sendSystemMessage(Component.literal("§c未找到技能: " + name));
                return 0;
            }
            player.sendSystemMessage(Component.literal("§e" + name + " §7(未学习)"));
        } else {
            player.sendSystemMessage(Component.literal("§a" + name + " §7(已学习)"));
        }

        player.sendSystemMessage(Component.literal(" §7类别: " + skill.getCategory().getDisplayName()));
        player.sendSystemMessage(Component.literal(" §7描述: " + skill.getDescription()));

        if (!skill.getPrerequisites().isEmpty()) {
            player.sendSystemMessage(Component.literal(" §7前置技能: " + String.join(", ", skill.getPrerequisites())));
        }

        if (skill.getAction() != null) {
            player.sendSystemMessage(Component.literal(" §7步骤数: " + skill.getAction().getTotalSteps()));
        }

        return 1;
    }

    /** 学习/执行技能 */
    private static int learnSkill(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            player.sendSystemMessage(Component.literal("§c你没有同伴"));
            return 0;
        }

        // 如果同伴正在执行技能，提示先取消
        if (companion.isSkillActive()) {
            player.sendSystemMessage(Component.literal("§c同伴正在执行技能: " + companion.getSkillEngine().getCurrentSkillName()));
            player.sendSystemMessage(Component.literal("§7使用 §f/companion skill cancel §7先取消当前技能"));
            return 0;
        }

        SkillLibrary lib = getLibrary();
        if (lib == null) return 0;

        // 如果未学习，先学习（注册预制技能给玩家）
        if (!lib.hasSkill(player.getUUID(), name)) {
            boolean learned = lib.learnSkill(player.getUUID(), name);
            if (!learned) {
                player.sendSystemMessage(Component.literal("§c无法学习技能 '" + name + "'（可能未找到或前置技能不足）"));
                return 0;
            }
            player.sendSystemMessage(Component.literal("§a学会了新技能: " + name));
        }

        // 执行技能
        Skill skill = lib.getPlayerSkill(player.getUUID(), name);
        if (skill == null) {
            player.sendSystemMessage(Component.literal("§c技能数据错误: " + name));
            return 0;
        }

        boolean started = companion.getSkillEngine().startSkill(skill, companion);
        if (started) {
            player.sendSystemMessage(Component.literal("§a开始执行技能: " + name));
            companion.showDialogue("§b执行技能: " + name, 60);
        } else {
            player.sendSystemMessage(Component.literal("§c技能无法启动"));
        }

        return 1;
    }

    /** 取消当前技能 */
    private static int cancelSkill(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            player.sendSystemMessage(Component.literal("§c你没有同伴"));
            return 0;
        }

        if (!companion.isSkillActive()) {
            player.sendSystemMessage(Component.literal("§7同伴没有正在执行的技能"));
            return 0;
        }

        companion.getSkillEngine().cancelSkill(companion);
        player.sendSystemMessage(Component.literal("§e技能已取消"));
        return 1;
    }

    /** 遗忘技能（OP） */
    private static int forgetSkill(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        SkillLibrary lib = getLibrary();
        if (lib == null) return 0;

        boolean forgot = lib.forgetSkill(player.getUUID(), name);
        if (forgot) {
            player.sendSystemMessage(Component.literal("§e已遗忘技能: " + name));
        } else {
            player.sendSystemMessage(Component.literal("§c未找到已学技能: " + name));
        }
        return 1;
    }

    /** 注册所有预制技能（OP） */
    private static int registerPresets(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        SkillLibrary lib = getLibrary();
        if (lib == null) return 0;

        lib.learnAllPresets(player.getUUID());
        int count = lib.getPlayerSkills(player.getUUID()).size();
        player.sendSystemMessage(Component.literal("§a已注册 " + count + " 个预制技能"));
        return 1;
    }

    /** 使用 LLM 生成新技能（OP） */
    private static int generateSkill(CommandSourceStack source, String description) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            player.sendSystemMessage(Component.literal("§c你没有同伴"));
            return 0;
        }

        player.sendSystemMessage(Component.literal("§e正在用 AI 生成技能...（请稍候）"));

        // 在后台线程调用 LLM，然后切换回服务器线程
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return SkillGenerator.generate(player.getName().getString(), description);
            } catch (Exception e) {
                AICompanionMod.LOGGER.error("[SkillGen] Error: {}", e.getMessage());
                return null;
            }
        }).thenAcceptAsync(skill -> {
            if (skill == null) {
                player.sendSystemMessage(Component.literal("§c技能生成失败（LLM 未响应或解析错误）"));
                return;
            }

            SkillLibrary lib = getLibrary();
            if (lib == null) return;

            String skillName = SkillGenerator.registerGeneratedSkill(lib, skill, companion);
            if (skillName != null) {
                player.sendSystemMessage(Component.literal("§a✅ AI 生成了新技能: §e" + skillName));
                player.sendSystemMessage(Component.literal(" §7" + skill.getDescription()));
                player.sendSystemMessage(Component.literal(" §7共 " + skill.getAction().getTotalSteps() + " 步"));

                // 自动执行
                boolean started = companion.getSkillEngine().startSkill(
                    lib.getPreset(skillName), companion);
                if (started) {
                    player.sendSystemMessage(Component.literal("§a开始执行新技能"));
                    companion.showDialogue("§b执行新技能: " + skillName, 60);
                }
            } else {
                player.sendSystemMessage(Component.literal("§c技能注册失败"));
            }
        }, context -> {
            if (player.getServer() != null) {
                player.getServer().execute((Runnable) context);
            }
        });

        return 1;
    }

    // ================ 工具方法 ================

    @javax.annotation.Nullable
    private static SkillLibrary getLibrary() {
        return AICompanionMod.skillLibrary;
    }
}
