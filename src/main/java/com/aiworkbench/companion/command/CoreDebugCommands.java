package com.aiworkbench.companion.command;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.ai.PerceptionEngine;
import com.aiworkbench.companion.core.action.ActionExecutor;
import com.aiworkbench.companion.core.decision.ActionDecision;
import com.aiworkbench.companion.core.decision.MemorySnapshot;
import com.aiworkbench.companion.core.decision.RuleBasedDecisionMaker;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * P0-P5 闭环验证命令 — 在游戏内手动触发决策循环测试。
 *
 * <p>命令：{@code /companion coretest}
 * 执行流程：感知 → 决策 → 动作创建 → 输出结果到聊天框
 */
public class CoreDebugCommands {

    private static final RuleBasedDecisionMaker engine = new RuleBasedDecisionMaker();

    public static void register(LiteralArgumentBuilder<CommandSourceStack> parent) {
        parent.then(Commands.literal("coretest")
            .executes(ctx -> runCoreTest(ctx.getSource()))
            .then(Commands.literal("enable")
                .executes(ctx -> enableFramework(ctx.getSource())))
            .then(Commands.literal("disable")
                .executes(ctx -> disableFramework(ctx.getSource())))
        );
        parent.then(Commands.literal("mode")
            .then(Commands.literal("chop")
                .executes(ctx -> setMode(ctx.getSource(), "chop")))
            .then(Commands.literal("mine")
                .executes(ctx -> setMode(ctx.getSource(), "mine")))
            .then(Commands.literal("follow")
                .executes(ctx -> setMode(ctx.getSource(), "follow")))
        );
    }

    private static int enableFramework(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null) { source.sendFailure(Component.literal("无同伴")); return 0; }
        companion.enableNewFramework();
        source.sendSystemMessage(Component.literal("§a新框架已启用 — 感知→决策→执行 每tick驱动"));
        return 1;
    }

    private static int disableFramework(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null) { source.sendFailure(Component.literal("无同伴")); return 0; }
        companion.disableNewFramework();
        source.sendSystemMessage(Component.literal("§7新框架已禁用 — 回退旧Goal系统"));
        return 1;
    }

    private static int setMode(CommandSourceStack source, String mode) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null) { source.sendFailure(Component.literal("无同伴")); return 0; }
        if (!companion.isNewFrameworkActive()) {
            companion.enableNewFramework(mode);
        } else {
            companion.setMode(mode);
        }
        String modeName = "chop".equals(mode) ? "砍树" : "mine".equals(mode) ? "挖矿" : "跟随";
        source.sendSystemMessage(Component.literal("§a模式切换: " + modeName + " (新框架)"));
        return 1;
    }

    private static int runCoreTest(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("必须由玩家执行"));
            return 0;
        }

        AutomatonEntity companion = AICompanionMod.companionManager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) {
            source.sendFailure(Component.literal("你没有活跃的同伴"));
            return 0;
        }

        // ---- 步骤1: 感知 ----
        source.sendSystemMessage(Component.literal("§e[CoreTest] §7步骤1: 调用 PerceptionEngine.gatherStructured()..."));
        PerceptionData perception;
        try {
            perception = PerceptionEngine.gatherStructured(companion);
            source.sendSystemMessage(Component.literal(
                "§a  ✓ 感知数据已生成" +
                " | 方块: " + (perception.nearbyBlocks != null ? perception.nearbyBlocks.size() : 0) +
                " | 实体: " + (perception.nearbyEntities != null ? perception.nearbyEntities.size() : 0) +
                " | 威胁: " + (perception.threats != null ? perception.threats.size() : 0) +
                " | 背包: " + (perception.inventorySummary != null ? perception.inventorySummary.size() : 0) + "种物品" +
                " | 血量: " + (perception.self != null ? perception.self.health : "?")
            ));
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c  ✗ 感知失败: " + e.getMessage()));
            AICompanionMod.LOGGER.error("[CoreTest] Perception failed", e);
            return 0;
        }

        // ---- 步骤2: 决策 ----
        source.sendSystemMessage(Component.literal("§e[CoreTest] §7步骤2: 调用 RuleBasedDecisionMaker.decide()..."));
        ActionDecision decision;
        try {
            decision = engine.decide(perception, MemorySnapshot.empty());
            source.sendSystemMessage(Component.literal(
                "§a  ✓ 决策完成" +
                " | 动作: " + decision.actionId() +
                " | 来源: " + decision.source() +
                " | 理由: " + (decision.reasoning() != null && !decision.reasoning().isEmpty() ? decision.reasoning() : "(无)")
            ));
            if (!decision.params().isEmpty()) {
                source.sendSystemMessage(Component.literal("§7    参数: " + decision.params()));
            }
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c  ✗ 决策失败: " + e.getMessage()));
            AICompanionMod.LOGGER.error("[CoreTest] Decision failed", e);
            return 0;
        }

        // ---- 步骤3: 动作创建 ----
        if (!"idle".equals(decision.actionId())) {
            source.sendSystemMessage(Component.literal("§e[CoreTest] §7步骤3: 创建动作原语..."));
            try {
                ActionExecutor executor = new ActionExecutor(companion);
                executor.dispatch(decision);
                if (executor.isActive()) {
                    source.sendSystemMessage(Component.literal(
                        "§a  ✓ 动作已创建并开始执行: " + decision.actionId()
                    ));
                } else {
                    source.sendSystemMessage(Component.literal(
                        "§6  ⚠ 动作创建失败（canExecute 返回 false 或参数无效）"
                    ));
                }
            } catch (Exception e) {
                source.sendFailure(Component.literal("§c  ✗ 动作创建失败: " + e.getMessage()));
                AICompanionMod.LOGGER.error("[CoreTest] Action creation failed", e);
                return 0;
            }
        } else {
            source.sendSystemMessage(Component.literal("§7  → 决策为 idle，跳过动作创建"));
        }

        // ---- 步骤4: 附近摘要 ----
        if (perception.nearbyBlocks != null && !perception.nearbyBlocks.isEmpty()) {
            StringBuilder sb = new StringBuilder("§7  附近方块(top5): ");
            for (int i = 0; i < Math.min(perception.nearbyBlocks.size(), 5); i++) {
                var b = perception.nearbyBlocks.get(i);
                sb.append(b.blockType()).append("(").append((int)b.distance()).append("m) ");
            }
            source.sendSystemMessage(Component.literal(sb.toString()));
        }

        source.sendSystemMessage(Component.literal("§a§l[CoreTest] 闭环验证完成 ✓"));
        return 1;
    }
}
