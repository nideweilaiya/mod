package com.aiworkbench.companion.command;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.CompanionConfig;
import com.aiworkbench.companion.ai.TaskInterruptProtocol;
import com.aiworkbench.companion.ai.TaskInterruptProtocol.*;
import com.aiworkbench.companion.ai.TaskQueue;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.CompositeTask;
import com.aiworkbench.companion.skill.GoalDecomposer;
import com.aiworkbench.companion.skill.Skill;
import com.aiworkbench.companion.skill.SkillGenerator;
import com.aiworkbench.companion.skill.SkillLibrary;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLLoader;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 交互子命令：/companion chat &lt;message&gt;, model [name], gui
 * <p>
 * 聊天模式增强功能：
 * 1. 关键词匹配 — 常见任务直接触发技能，无需 AI
 * 2. AI 响应解析 — LLM 可在回复中加 [SKILL:name] 或 [GENERATE:desc] 来触发技能
 */
public class CompanionAICommands {

    // ==================== 关键词 → 技能映射 ====================

    private static final List<Map.Entry<String, String>> SKILL_KEYWORDS = List.of(
        // 资源采集类 → 统一触发智能采集模式 (gather)
        Map.entry("挖铁矿",   "gather"),
        Map.entry("挖铁",     "gather"),
        Map.entry("铁矿",     "gather"),
        Map.entry("铁矿石",   "gather"),
        Map.entry("挖煤矿",   "gather"),
        Map.entry("挖煤",     "gather"),
        Map.entry("煤矿",     "gather"),
        Map.entry("挖石头",   "gather"),
        Map.entry("挖矿",     "gather"),
        Map.entry("采矿",     "gather"),
        Map.entry("采集",     "gather"),
        Map.entry("挖钻石",   "gather"),
        Map.entry("钻石",     "gather"),
        Map.entry("挖金子",   "gather"),
        Map.entry("金子",     "gather"),
        Map.entry("挖矿道",   "gather"),
        // 砍伐类 → 统一触发智能采集模式 (gather)
        Map.entry("砍树",     "gather"),
        Map.entry("收集木头", "gather"),
        Map.entry("木头",     "gather"),
        Map.entry("木材",     "gather"),
        Map.entry("原木",     "gather"),
        Map.entry("砍木头",   "gather"),
        Map.entry("伐木",     "gather"),
        Map.entry("砍柴",     "gather"),
        // 种植类 → 开启种植模式 (farm)
        Map.entry("收割",     "farm"),
        Map.entry("种地",     "farm"),
        Map.entry("种田",     "farm"),
        Map.entry("种植",     "farm"),
        Map.entry("耕地",     "farm"),
        Map.entry("收菜",     "farm"),
        Map.entry("收小麦",   "farm"),
        Map.entry("收胡萝卜", "farm"),
        Map.entry("收土豆",   "farm"),
        Map.entry("农场",     "farm"),
        Map.entry("收割庄稼", "farm"),
        Map.entry("种菜",     "farm"),
        // 战斗类
        Map.entry("打僵尸",   "fightZombie"),
        Map.entry("战斗",     "fightZombie"),
        Map.entry("打怪",     "fightZombie"),
        Map.entry("僵尸",     "fightZombie"),
        Map.entry("杀怪",     "fightZombie"),
        Map.entry("攻击",     "fightZombie"),
        Map.entry("保护我",   "fightZombie"),
        Map.entry("守护",     "fightZombie"),
        // 收集类
        Map.entry("收集掉落", "collectDrops"),
        Map.entry("捡东西",   "collectDrops"),
        Map.entry("捡",       "collectDrops"),
        Map.entry("收集",     "collectDrops"),
        Map.entry("拾取",     "collectDrops"),
        // 合成类
        Map.entry("合成木棍", "craftStick"),
        Map.entry("木棍",     "craftStick"),
        Map.entry("做木棍",   "craftStick"),
        Map.entry("合成木镐", "craftWoodenPickaxe"),
        Map.entry("木镐",     "craftWoodenPickaxe"),
        Map.entry("做镐子",   "craftWoodenPickaxe"),
        Map.entry("合成熔炉", "craftFurnace"),
        Map.entry("造熔炉",   "craftFurnace"),
        Map.entry("熔炉",     "craftFurnace"),
        Map.entry("合成",     "craftFurnace"),
        Map.entry("做熔炉",   "craftFurnace"),
        // 建筑类
        Map.entry("造房子",   "buildShelter"),
        Map.entry("庇护所",   "buildShelter"),
        Map.entry("小屋",     "buildShelter"),
        Map.entry("建房子",   "buildShelter"),
        Map.entry("盖房子",   "buildShelter"),
        Map.entry("搭房子",   "buildShelter"),
        Map.entry("造家",     "buildShelter"),
        Map.entry("建家",     "buildShelter"),
        // 冶炼类
        Map.entry("冶炼铁锭", "smeltIronIngot"),
        Map.entry("烧铁",     "smeltIronIngot"),
        Map.entry("铁锭",     "smeltIronIngot"),
        Map.entry("冶炼",     "smeltIronIngot"),
        Map.entry("烧矿",     "smeltIronIngot"),
        // 移动类
        Map.entry("看向我",   "lookAtOwner"),
        Map.entry("看我",     "lookAtOwner"),
        Map.entry("前进",     "moveForward"),
        Map.entry("向前",     "moveForward"),
        Map.entry("过来",     "moveForward"),
        Map.entry("过来这里", "moveForward"),
        Map.entry("跟我走",   "moveForward"),
        Map.entry("跟上",     "moveForward")
    );

    // 协商状态 — 等待玩家确认是否切换任务
    private static final Map<UUID, String> pendingNegotiations = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, String> negotiationNewSkill = new java.util.concurrent.ConcurrentHashMap<>();

    // 任务完成后续询问追踪
    private static final Map<UUID, Boolean> pendingFollowUp = new java.util.concurrent.ConcurrentHashMap<>();

    // AI 响应的技能标记正则
    private static final Pattern SKILL_PATTERN = Pattern.compile("\\[SKILL:(\\w+)]");
    private static final Pattern GENERATE_PATTERN = Pattern.compile("\\[GENERATE:(.+?)]");

    // ==================== 命令注册 ====================

    /**
     * Register non-OP AI commands: chat, chat clear, gui
     */
    public static void registerNonOp(LiteralArgumentBuilder<CommandSourceStack> parent) {
        parent.then(Commands.literal("chat")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> chatWithAI(ctx.getSource(), StringArgumentType.getString(ctx, "message"))))
                        .then(Commands.literal("clear")
                                .executes(ctx -> clearChat(ctx.getSource()))))
                .then(Commands.literal("gui")
                        .executes(ctx -> openGUI(ctx.getSource())))
                .then(Commands.literal("queue")
                        .executes(ctx -> showQueue(ctx.getSource()))
                        .then(Commands.literal("clear")
                                .executes(ctx -> clearQueue(ctx.getSource())))
                        .then(Commands.literal("list")
                                .executes(ctx -> showQueue(ctx.getSource()))))
                .then(Commands.literal("goal")
                        .then(Commands.argument("description", StringArgumentType.greedyString())
                                .executes(ctx -> setGoal(ctx.getSource(), StringArgumentType.getString(ctx, "description")))));
    }

    /**
     * Register OP-only AI commands: model
     */
    public static void register(LiteralArgumentBuilder<CommandSourceStack> parent) {
        parent.then(Commands.literal("model")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> setModel(ctx.getSource(), StringArgumentType.getString(ctx, "name"))))
                        .executes(ctx -> showModel(ctx.getSource())));
    }

    // ==================== 聊天处理 ====================

    private static int chatWithAI(CommandSourceStack source, String message) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("§c你没有同伴"));
            return 0;
        }

        UUID playerUUID = player.getUUID();

        // ===== 消息栈匹配：玩家自然语言回应待处理提议 =====
        com.aiworkbench.companion.ai.DialogueStack.PendingMessage matched =
            companion.getDialogueStack().matchAndPop(message);
        if (matched != null) {
            if ("rejected".equals(matched.response)) {
                source.sendSuccess(() -> Component.literal("§7已忽略: " + matched.text), false);
                companion.showDialogue("§7好的", 40);
                return 1;
            }
            source.sendSuccess(() -> Component.literal("§a确认: " + matched.text), false);
            if (matched.linkedSkill != null && !matched.linkedSkill.isEmpty()) {
                executePresetSkill(player, companion, playerUUID, matched.linkedSkill);
            }
            return 1;
        }

        // ===== 协商状态处理 =====
        if (pendingNegotiations.containsKey(playerUUID)) {
            Boolean confirmed = TaskInterruptProtocol.isConfirmation(message);
            if (confirmed != null && confirmed) {
                // 玩家确认 → 中断当前技能，开始新技能
                String newSkill = negotiationNewSkill.remove(playerUUID);
                String oldSkill = pendingNegotiations.remove(playerUUID);
                companion.getSkillEngine().cancelSkill(companion);
                source.sendSuccess(() -> Component.literal("§e已取消 " + oldSkill + "，切换到 " + newSkill), false);
                companion.showDialogue("§b执行: " + newSkill, 60);
                executePresetSkill(player, companion, playerUUID, newSkill);
                return 1;
            } else if (confirmed != null && !confirmed) {
                // 玩家拒绝 → 继续当前任务
                pendingNegotiations.remove(playerUUID);
                negotiationNewSkill.remove(playerUUID);
                source.sendSuccess(() -> Component.literal("§a继续当前任务"), false);
                companion.showDialogue("§a好的，继续！", 40);
                return 1;
            }
        }

        // ===== 任务完成后续询问 =====
        if (pendingFollowUp.containsKey(playerUUID)) {
            pendingFollowUp.remove(playerUUID);
            // 玩家回复 → 可能是新任务请求，继续正常流程
        }

        // ===== 快速意图分类（无需 AI） =====
        Intent intent = TaskInterruptProtocol.classifyQuick(message);

        // 紧急情况 → 无条件中断
        if (intent == Intent.EMERGENCY && companion.isSkillActive()) {
            companion.getSkillEngine().cancelSkill(companion);
            companion.returnToFollow();
            companion.showDialogue("§c紧急！已停止当前任务", 60);
            source.sendSuccess(() -> Component.literal("§c⚠ 紧急！已停止当前技能，切换到跟随模式"), false);
        }

        // ===== 技能中断协议 =====
        if (companion.isSkillActive() && intent != Intent.EMERGENCY) {
            String currentSkillName = companion.getSkillEngine().getCurrentSkillName();
            Integer currentPrio = TaskInterruptProtocol.priorityOf(currentSkillName);

            // 简单问题 → 回复但不中断
            if (intent == Intent.QUESTION) {
                String answer = generateQuickAnswer(message, companion);
                source.sendSuccess(() -> Component.literal("§b[你]§f: " + message), false);
                source.sendSuccess(() -> Component.literal("§d[小助手]§f: " + answer), false);
                companion.showDialogue(answer);
                return 1;
            }

            // 新任务请求 → 检查优先级
            String matchedSkill = matchKeyword(message);
            if (matchedSkill != null) {
                Integer newPrio = TaskInterruptProtocol.priorityOf(matchedSkill);
                Decision d = TaskInterruptProtocol.decide(Intent.TASK, currentPrio, newPrio);

                switch (d) {
                    case FORCE_INTERRUPT -> {
                        companion.getSkillEngine().cancelSkill(companion);
                        source.sendSuccess(() -> Component.literal("§e[切换] 已停止 " + currentSkillName), false);
                        source.sendSuccess(() -> Component.literal("§b[你]§f: " + message), false);
                        executePresetSkill(player, companion, playerUUID, matchedSkill);
                        return 1;
                    }
                    case NEGOTIATE -> {
                        pendingNegotiations.put(playerUUID, currentSkillName);
                        negotiationNewSkill.put(playerUUID, matchedSkill);
                        String askMsg = "主人，我正在" + currentSkillName + "，要切换吗？";
                        source.sendSuccess(() -> Component.literal("§e[协商] " + askMsg), false);
                        source.sendSuccess(() -> Component.literal("§7回复 §f是/不 §7确认"), false);
                        companion.showDialogue(askMsg, 80);
                        return 1;
                    }
                    case REPLY_ONLY -> {
                        source.sendSuccess(() -> Component.literal("§e同伴正在 " + currentSkillName + "，完成后处理"), false);
                        return 1;
                    }
                }
            }

            // 未匹配到技能 → 纯聊天，不中断
            source.sendSuccess(() -> Component.literal("§b[你]§f: " + message), false);
            sendToAIForChat(player, companion, playerUUID, message, false);
            return 1;
        }

        // ===== 正常流程：无任务运行 =====
        // 第一步：关键词匹配（快速路径）
        String matchedSkill = matchKeyword(message);
        if (matchedSkill != null) {
            source.sendSuccess(() -> Component.literal("§b[你]§f: " + message), false);
            executePresetSkill(player, companion, playerUUID, matchedSkill);
            return 1;
        }

        // 第二步：发送给 AI 聊天
        source.sendSuccess(() -> Component.literal("§b[你]§f: " + message), false);

        var ai = AICompanionMod.aiManager.getAI(companion);
        ai.sendMessage(message).thenAccept(response -> {
            AICompanionMod.server.execute(() -> {
                if (response == null || response.isEmpty() || response.equals("...")) {
                    player.sendSystemMessage(Component.literal("§cAI 没有回应（LLM 可能未运行）"));
                    return;
                }

                // 第三步：解析 AI 响应中的技能标记
                boolean executedSkill = parseAndExecuteSkill(player, companion, playerUUID, response);

                // 清理标记，只显示对话文本
                String displayText = cleanResponse(response);
                if (!displayText.isEmpty()) {
                    player.sendSystemMessage(Component.literal("§d[小助手]§f: " + displayText));
                    companion.showDialogue(displayText);
                } else if (!executedSkill) {
                    // 既无对话文本也没有执行技能，显示原始响应
                    player.sendSystemMessage(Component.literal("§d[小助手]§f: " + response));
                    companion.showDialogue(response.length() > 40 ? response.substring(0, 40) : response);
                }

                AICompanionMod.LOGGER.info("AI response to {}: {}", player.getName().getString(), response);
            });
        });

        return 1;
    }

    // ==================== 关键词匹配 ====================

    /**
     * 在消息中匹配技能关键词。按定义顺序匹配（精确匹配优先）。
     * @return 匹配到的技能名，无匹配返回 null
     */
    private static String matchKeyword(String message) {
        for (Map.Entry<String, String> entry : SKILL_KEYWORDS) {
            if (message.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    // ==================== AI 响应解析 ====================

    /**
     * 解析 AI 响应中的 [SKILL:name] 和 [GENERATE:description] 标记并执行。
     * @return true 如果执行了任何技能
     */
    private static boolean parseAndExecuteSkill(ServerPlayer player, AutomatonEntity companion,
                                                UUID playerUUID, String response) {
        if (response == null || response.isEmpty()) return false;

        // 检查 [GENERATE:description] —— 动态生成新技能
        Matcher genMatcher = GENERATE_PATTERN.matcher(response);
        if (genMatcher.find()) {
            String description = genMatcher.group(1).trim();
            generateAndExecuteSkill(player, companion, description);
            return true;
        }

        // 检查 [SKILL:name] —— 执行已有技能
        Matcher skillMatcher = SKILL_PATTERN.matcher(response);
        if (skillMatcher.find()) {
            String skillName = skillMatcher.group(1);
            return executePresetSkill(player, companion, playerUUID, skillName);
        }

        return false;
    }

    /**
     * 去除 AI 响应中的 [SKILL:] 和 [GENERATE:] 标记，只保留对话文本。
     */
    private static String cleanResponse(String response) {
        if (response == null) return "";
        String cleaned = response.replaceAll("\\[SKILL:\\w+]", "").trim();
        cleaned = cleaned.replaceAll("\\[GENERATE:.+?]", "").trim();
        return cleaned;
    }

    // ==================== 技能执行 ====================

    /**
     * 执行一个预制技能。如果玩家未学习，自动学习。
     * @return true 如果技能成功启动
     */
    private static boolean executePresetSkill(ServerPlayer player, AutomatonEntity companion,
                                              UUID playerUUID, String skillName) {
        // "gather" 是特殊动作：开启智能采集模式而非技能
        if ("gather".equals(skillName)) {
            companion.setGatherModeEnabled(true);
            player.sendSystemMessage(Component.literal("§6⛏ 已开启智能采集模式"));
            companion.showDialogue("§6开始采集资源！", 60);
            return true;
        }
        // "farm" 是特殊动作：开启种植模式
        if ("farm".equals(skillName)) {
            companion.setFarmModeEnabled(true);
            player.sendSystemMessage(Component.literal("§a🌾 已开启种植模式"));
            companion.showDialogue("§a开始收割庄稼！", 60);
            return true;
        }

        SkillLibrary lib = getLibrary();
        if (lib == null) return false;

        // 如果未学习，先学习
        if (!lib.hasSkill(playerUUID, skillName)) {
            Skill preset = lib.getPreset(skillName);
            if (preset == null) {
                player.sendSystemMessage(Component.literal("§c未知技能: " + skillName));
                return false;
            }
            boolean learned = lib.learnSkill(playerUUID, skillName);
            if (!learned) {
                // 尝试直接执行
            }
        }

        // 获取技能并执行
        Skill skill = lib.getPlayerSkill(playerUUID, skillName);
        if (skill == null) {
            skill = lib.getPreset(skillName);
            if (skill == null) {
                player.sendSystemMessage(Component.literal("§c技能数据错误: " + skillName));
                return false;
            }
        }

        boolean started = companion.getSkillEngine().startSkill(skill, companion);
        if (started) {
            player.sendSystemMessage(Component.literal("§a▶ 开始执行: " + skillName));
            companion.showDialogue("§b执行: " + skillName, 60);
        } else {
            player.sendSystemMessage(Component.literal("§c技能 '" + skillName + "' 无法启动（可能已有技能在运行）"));
        }
        return started;
    }

    /**
     * 调用 LLM 动态生成技能并执行。
     */
    private static void generateAndExecuteSkill(ServerPlayer player, AutomatonEntity companion,
                                                String description) {
        if (description == null || description.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c技能描述为空"));
            return;
        }

        player.sendSystemMessage(Component.literal("§e正在用 AI 生成新技能: " + description + " ..."));

        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return SkillGenerator.generate(player.getName().getString(), description);
            } catch (Exception e) {
                AICompanionMod.LOGGER.error("[ChatGen] Error: {}", e.getMessage());
                return null;
            }
        }).thenAcceptAsync(skill -> {
            if (skill == null) {
                player.sendSystemMessage(Component.literal("§c技能生成失败（LLM 未响应）"));
                return;
            }

            SkillLibrary lib = getLibrary();
            if (lib == null) return;

            String skillName = SkillGenerator.registerGeneratedSkill(lib, skill, companion);
            if (skillName != null) {
                player.sendSystemMessage(Component.literal("§a✅ AI 生成了新技能: §e" + skillName));
                player.sendSystemMessage(Component.literal(" §7" + skill.getDescription()));

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
    }

    // ==================== 其他命令 ====================

    private static int setModel(CommandSourceStack source, String modelName) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        CompanionConfig.setModel(player.getUUID(), modelName);

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion != null && companion.isAlive()) {
            var ai = AICompanionMod.aiManager.getAI(companion);
            ai.setModel(modelName);
        }

        source.sendSuccess(() -> Component.literal("AI 模型设置为: " + modelName), true);
        return 1;
    }

    private static int showModel(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        String currentModel = CompanionConfig.getModel(player.getUUID());
        source.sendSuccess(() -> Component.literal("当前 AI 模型: " + currentModel), false);

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion != null && companion.isAlive()) {
            var ai = AICompanionMod.aiManager.getAI(companion);
            source.sendSuccess(() -> Component.literal("活跃 AI 模型: " + ai.getModel()), false);
        }

        return 1;
    }

    private static int openGUI(CommandSourceStack source) {
        if (source.getPlayer() == null) return 0;

        if (!FMLLoader.getDist().isClient()) {
            source.sendSuccess(() -> Component.literal("§e请在客户端按 C 键打开同伴面板"), false);
            return 1;
        }

        openSettingsScreen();
        return 1;
    }

    private static int clearChat(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("§c你没有同伴"));
            return 0;
        }

        var ai = AICompanionMod.aiManager.getAI(companion);
        ai.clearHistory();
        source.sendSuccess(() -> Component.literal("§a[对话] 对话历史已清除"), false);
        AICompanionMod.LOGGER.info("Chat history cleared for player {}", player.getName().getString());
        return 1;
    }

    // ==================== 快速问答 ====================

    /**
     * 生成快速回复（无需 LLM），用于任务执行中的简单问题。
     */
    private static String generateQuickAnswer(String message, AutomatonEntity companion) {
        if (message.contains("还有多远") || message.contains("还有多久") || message.contains("快好了吗")) {
            return "快了，正在努力干活呢！";
        }
        if (message.contains("好了没") || message.contains("完成了吗")) {
            return "还没呢，再等一下~";
        }
        if (message.contains("你还好吗") || message.contains("怎么样")) {
            return "我很好，正在认真干活！";
        }
        if (message.contains("在哪")) {
            return "就在你附近呀！";
        }
        return "嗯嗯~";
    }

    /**
     * 异步调用 AI 进行纯聊天（不触发技能）。
     */
    private static void sendToAIForChat(ServerPlayer player, AutomatonEntity companion,
                                         UUID playerUUID, String message, boolean isChat) {
        var ai = AICompanionMod.aiManager.getAI(companion);
        ai.sendMessage(message).thenAccept(response -> {
            AICompanionMod.server.execute(() -> {
                if (response == null || response.isEmpty() || response.equals("...")) {
                    player.sendSystemMessage(Component.literal("§cAI 没有回应（LLM 可能未运行）"));
                    return;
                }
                String displayText = cleanResponse(response);
                if (!displayText.isEmpty()) {
                    player.sendSystemMessage(Component.literal("§d[小助手]§f: " + displayText));
                    companion.showDialogue(displayText);
                }
            });
        });
    }

    /**
     * 在同伴完成技能后调用，询问是否继续。
     */
    public static void onSkillCompleted(ServerPlayer player, AutomatonEntity companion,
                                         String skillName) {
        if (player == null || companion == null) return;
        UUID playerUUID = player.getUUID();
        pendingFollowUp.put(playerUUID, true);
        companion.showDialogue("主人，" + skillName + "完成了！还要继续吗？", 80);
        player.sendSystemMessage(Component.literal("§a✅ 技能 " + skillName + " 完成！"));
        player.sendSystemMessage(Component.literal("§7直接发送新任务或回复\"不用\""));
    }

    // ==================== 任务队列命令 ====================

    private static int showQueue(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("§c你没有同伴"));
            return 0;
        }

        TaskQueue queue = companion.getTaskQueue();
        if (queue.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7任务队列为空"), false);
            return 1;
        }

        java.util.List<String> descs = queue.getQueueDescriptions();
        source.sendSuccess(() -> Component.literal("§6📋 任务队列 (" + queue.size() + "):\n§7" + String.join("\n§7", descs)), false);
        return 1;
    }

    private static int clearQueue(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("§c你没有同伴"));
            return 0;
        }

        companion.getTaskQueue().clear();
        source.sendSuccess(() -> Component.literal("§a任务队列已清空"), false);
        return 1;
    }

    // ==================== 目标分解命令 ====================

    private static int setGoal(CommandSourceStack source, String description) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("§c你没有同伴"));
            return 0;
        }

        CompositeTask task = GoalDecomposer.decompose(description);
        if (task == null) {
            source.sendSuccess(() -> Component.literal("§c无法理解目标: " + description + "\n§7试试: 铁镐 / 钻石镐 / 铁甲全套 / 附魔台"), false);
            return 0;
        }

        boolean ok = companion.getTaskQueue().enqueueComposite(task);
        if (ok) {
            String desc = GoalDecomposer.describeDecomposition(task);
            source.sendSuccess(() -> Component.literal(desc), false);
            companion.showDialogue("§b🎯 " + task.goalDescription, 80);
        } else {
            source.sendFailure(Component.literal("§c当前有任务在执行，请先完成或清空队列"));
        }
        return 1;
    }

    // ==================== 工具方法 ====================

    @javax.annotation.Nullable
    private static SkillLibrary getLibrary() {
        return AICompanionMod.skillLibrary;
    }

    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static void openSettingsScreen() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null) {
            mc.setScreen(new com.aiworkbench.companion.client.gui.CompanionSettingsScreen(mc.screen));
        }
    }
}
