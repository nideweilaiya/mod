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
 *   /companion skill feedback       — 查看最近技能反馈和验证结果
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

        skillBase.then(Commands.literal("feedback")
                .executes(ctx -> showFeedback(ctx.getSource())));

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

        // === 语义搜索（无需 OP，使用 VectorSkillLibrary）===
        skillBase.then(Commands.literal("search")
                .then(Commands.argument("description", StringArgumentType.greedyString())
                        .executes(ctx -> searchSkill(ctx.getSource(),
                                StringArgumentType.getString(ctx, "description")))));

        // === 重建嵌入索引（OP）===
        skillBase.then(Commands.literal("reindex")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> reindexSkills(ctx.getSource())));

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

    /** 查看最近一次技能执行的反馈和验证结果 */
    private static int showFeedback(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null) {
            player.sendSystemMessage(Component.literal("§7你还没有同伴"));
            return 0;
        }

        SkillEngine engine = companion.getSkillEngine();
        FeedbackCollector.SkillFeedback feedback = engine.getLastFeedback();

        if (feedback == null) {
            player.sendSystemMessage(Component.literal("§7还没有技能执行记录，先让同伴执行一个技能吧"));
            return 0;
        }

        player.sendSystemMessage(Component.literal("§6§l--- 最近技能反馈 ---"));
        player.sendSystemMessage(Component.literal(" §b技能: §f" + feedback.skillName));

        // 简化结果
        if (feedback.failureReason == FeedbackCollector.FailureReason.SUCCESS) {
            player.sendSystemMessage(Component.literal(" §a结果: ✓ 成功"));
        } else if (feedback.failureReason == FeedbackCollector.FailureReason.INTERRUPTED) {
            player.sendSystemMessage(Component.literal(" §e结果: ⚠ 被中断"));
        } else {
            player.sendSystemMessage(Component.literal(" §c结果: ✗ 失败 - " + feedback.failureReason.getDescription()));
        }

        // 执行耗时
        double seconds = feedback.executionTicks / 20.0;
        player.sendSystemMessage(Component.literal(" §7耗时: " + String.format("%.1f", seconds) + "秒"));

        // 收获总结
        int brokenCount = feedback.brokenBlocks.values().stream().mapToInt(Integer::intValue).sum();
        int collectedCount = feedback.collectedItems.values().stream().mapToInt(Integer::intValue).sum();
        if (brokenCount > 0 || collectedCount > 0) {
            StringBuilder summary = new StringBuilder(" §7完成: ");
            if (brokenCount > 0) summary.append("破坏").append(brokenCount).append("个方块 ");
            if (collectedCount > 0) summary.append("收集").append(collectedCount).append("个物品");
            player.sendSystemMessage(Component.literal(summary.toString()));
        }

        // LLM验证
        SkillVerifier.VerificationResult verification = engine.getLastVerification();
        if (verification != null) {
            if (verification.success) {
                player.sendSystemMessage(Component.literal(" §aAI验证: 通过 ✓"));
            } else {
                player.sendSystemMessage(Component.literal(" §eAI评价: " + truncate(verification.critique, 80)));
            }
            if (verification.suggestion != null && !verification.suggestion.isEmpty()) {
                player.sendSystemMessage(Component.literal(" §7建议: " + truncate(verification.suggestion, 80)));
            }
        } else {
            player.sendSystemMessage(Component.literal(" §7(AI验证结果尚未返回)"));
        }

        return 1;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
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

    /** 语义搜索技能 */
    private static int searchSkill(CommandSourceStack source, String description) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        SkillLibrary lib = getLibrary();
        if (lib == null) return 0;

        VectorSkillLibrary vectorLib = lib.getVectorLibrary();
        if (vectorLib == null) {
            player.sendSystemMessage(Component.literal("§c向量技能库未初始化"));
            return 0;
        }

        player.sendSystemMessage(Component.literal("§6§l=== 语义搜索: §f" + description + " §6§l==="));
        player.sendSystemMessage(Component.literal("§7正在计算嵌入向量并检索...（请稍候）"));

        UUID uuid = player.getUUID();
        List<VectorSkillLibrary.SkillScore> scores = vectorLib.searchSimilar(description, uuid, 5);

        if (scores.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c未找到匹配技能。"));
            player.sendSystemMessage(Component.literal("§7提示：确保 Ollama 正在运行（nomic-embed-text 模型）。"));
            player.sendSystemMessage(Component.literal("§7使用 §f/companion skill reindex §7可重建索引。"));
            return 1;
        }

        player.sendSystemMessage(Component.literal("§a找到 " + scores.size() + " 个匹配技能："));
        for (int i = 0; i < scores.size(); i++) {
            VectorSkillLibrary.SkillScore score = scores.get(i);
            String percentage = String.format("%.0f%%", score.similarity * 100);
            String bar = getScoreBar(score.similarity);

            // 尝试解析为 Skill 对象获取更多信息
            Skill skill = lib.getPreset(score.skillName);
            if (skill == null) {
                skill = lib.getPlayerSkill(uuid, score.skillName);
            }

            String skillName = score.skillName;
            String suffix = "";
            if (skill != null) {
                String cat = skill.getCategory().getDisplayName();
                suffix = " §7[" + cat + "]";
            }

            player.sendSystemMessage(Component.literal(
                " §e" + (i + 1) + ". " + skillName + suffix + " §8" + bar + " §b" + percentage));
        }

        player.sendSystemMessage(Component.literal("§7使用 §f/companion skill learn <技能名> §7执行技能"));
        return 1;
    }

    /** 用 BAR 可视化相似度 */
    private static String getScoreBar(double score) {
        int bars = (int) Math.round(score * 10);
        StringBuilder sb = new StringBuilder("§a");
        for (int i = 0; i < 10; i++) {
            if (i < bars) {
                sb.append("█");
            } else {
                sb.append("§8░");
            }
        }
        return sb.toString();
    }

    /** 重建所有技能的嵌入索引（OP） */
    private static int reindexSkills(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        SkillLibrary lib = getLibrary();
        if (lib == null) return 0;

        int presetCount = lib.getAllPresets().size();
        player.sendSystemMessage(Component.literal("§e正在为 " + presetCount + " 个预制技能重建嵌入向量...（请稍候）"));

        // 在后台线程执行（可能耗时较长，需要调用 Ollama API）
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                lib.reindexAllPresets();
                int indexed = lib.getVectorLibrary().getIndexedSkillNames().size();
                // 回到服务器线程发送结果
                if (player.getServer() != null) {
                    player.getServer().execute(() -> {
                        player.sendSystemMessage(Component.literal(
                            "§a✅ 嵌入索引重建完成！已索引 " + indexed + "/" + presetCount + " 个技能"));
                    });
                }
            } catch (Exception e) {
                AICompanionMod.LOGGER.error("[SkillCmd] Reindex error: {}", e.getMessage());
                if (player.getServer() != null) {
                    player.getServer().execute(() -> {
                        player.sendSystemMessage(Component.literal("§c重建索引失败: " + e.getMessage()));
                    });
                }
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
