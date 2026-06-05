package com.aiworkbench.companion.client.gui;

import com.aiworkbench.companion.CompanionConfig;
import com.aiworkbench.companion.client.CompanionClientState;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Companion Settings GUI - shows companion stats and action buttons.
 * v2.1: 修复面板重叠 — 单轴 Y 计数器替代多基点偏移量体系
 */
public class CompanionSettingsScreen extends Screen {
    private final Screen parent;
    private List<String> availableSkins = new ArrayList<>();
    private int selectedSkinIndex = 0;
    private String currentModel = "llama3.2:latest";
    private String statusMessage = "";

    // Scroll
    private double scrollOffset = 0;
    private static final int CONTENT_HEIGHT = 450;
    private int viewHeight;

    private static final String[] AI_MODELS = {
        "llama3.2:latest",
        "llama3.1:8b",
        "qwen3.5:4b"
    };

    private AutomatonEntity companion;

    public CompanionSettingsScreen(Screen parent) {
        this(parent, null);
    }

    public CompanionSettingsScreen(Screen parent, AutomatonEntity knownCompanion) {
        super(Component.literal("同伴设置"));
        this.parent = parent;
        if (Minecraft.getInstance().player != null) {
            currentModel = CompanionConfig.getModel(Minecraft.getInstance().player.getUUID());
        }
        companion = knownCompanion != null ? knownCompanion : CompanionClientState.getCompanion();
    }

    @Override
    protected void init() {
        clearWidgets();
        viewHeight = this.height - 30;
        int cx = this.width / 2;

        companion = CompanionClientState.getCompanion();
        if (companion == null) {
            CompanionClientState.forceRefresh();
            companion = CompanionClientState.getCompanion();
        }

        // ===== 单轴 Y：所有组件从此走，永不重叠 =====
        int y = 15 - (int) scrollOffset;

        // ── 标题行 ──
        if (companion != null) {
            String name = companion.getCustomName() != null
                ? companion.getCustomName().getString() : "Companion";
            int lv = companion.getLevel();
            int hp = (int) companion.getHealth();
            int maxHp = (int) companion.getMaxHealth();
            String mode = companion.getWorkingMode();
            String modeStr = switch (mode) {
                case "guard" -> "§cGuard";
                case "mine"  -> "§bMine";
                case "chop"  -> "§6Chop";
                case "patrol"-> "§7Patrol";
                default      -> "§aFollow";
            };
            addRenderableWidget(Button.builder(
                Component.literal("§6§l" + name + "  §eLv." + lv + "  " + modeStr + "  §7HP: " + hp + "/" + maxHp),
                btn -> {}).bounds(cx - 120, y, 240, 16).build());
        } else {
            addRenderableWidget(Button.builder(
                Component.literal("§7无同伴数据"),
                btn -> {}).bounds(cx - 60, y, 120, 16).build());
        }
        y += 24;

        // ── 模式行 ──
        addRenderableWidget(Button.builder(Component.literal("§a跟随"), btn -> sendCmd("companion follow"))
            .bounds(cx - 100, y, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("§c守护"), btn -> sendCmd("companion guard"))
            .bounds(cx - 35, y, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("§6采集"), btn -> sendCmd("companion gather"))
            .bounds(cx + 30, y, 60, 20).build());

        boolean autoMode = companion != null && companion.isAutonomousMode();
        addRenderableWidget(Button.builder(
            Component.literal(autoMode ? "§a🤖" : "§7🤖"),
            btn -> {
                sendCmd(companion != null && companion.isAutonomousMode() ?
                    "companion autonomous off" : "companion autonomous on");
                statusMessage = companion != null && companion.isAutonomousMode() ?
                    "§7自主模式已关闭" : "§a自主模式已开启";
                init();
            }
        ).bounds(cx + 120, y, 22, 20).build());
        y += 26;

        // ── 操作行 ──
        addRenderableWidget(Button.builder(Component.literal("§3传送"), btn -> sendCmd("companion teleport"))
            .bounds(cx - 120, y, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("§7隐藏"), btn -> sendCmd("companion hide"))
            .bounds(cx - 60, y, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("§e背包"), btn -> {
            if (this.minecraft.player != null && this.minecraft.player.connection != null) {
                this.minecraft.player.connection.sendCommand("companion openinv");
            }
            this.onClose();
        }).bounds(cx, y, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("§a状态"), btn -> sendCmd("companion status"))
            .bounds(cx + 60, y, 55, 20).build());
        y += 28;

        // ── 聊天 / 等级 ──
        addRenderableWidget(Button.builder(Component.literal("§b💬 和同伴聊天"), btn -> {
            this.minecraft.setScreen(new ChatScreen("/companion chat "));
        }).bounds(cx - 100, y, 200, 18).build());
        y += 22;
        addRenderableWidget(Button.builder(Component.literal("§e📊 查看等级"), btn -> sendCmd("companion level"))
            .bounds(cx - 100, y, 200, 18).build());
        y += 22;

        // ── 属性加点区域 ──
        if (companion != null) {
            int avail = companion.getAvailablePoints();
            addRenderableWidget(Button.builder(
                Component.literal("§6§l属性加点  §e可用点数: §a" + avail), btn -> {})
                .bounds(cx - 120, y, 240, 14).build());
            y += 18;

            addStatRow("§c体力", "vitality", companion.getVitalityPoints(), y, cx);
            y += 22;
            addStatRow("§b力量", "strength", companion.getStrengthPoints(), y, cx);
            y += 22;
            addStatRow("§a速度", "speed", companion.getSpeedPoints(), y, cx);
            y += 22;
            addStatRow("§7防御", "defense", companion.getDefensePoints(), y, cx);
            y += 26;

            addRenderableWidget(Button.builder(Component.literal("§e重置全部"), btn -> {
                sendCmd("companion stats reset");
                statusMessage = "§e属性已重置";
            }).bounds(cx - 40, y, 80, 16).build());
            y += 20;
        } else {
            addRenderableWidget(Button.builder(
                Component.literal("§c⚠ 未找到同伴"), btn -> {})
                .bounds(cx - 60, y, 120, 14).build());
            y += 18;
            addRenderableWidget(Button.builder(
                Component.literal("§7可能原因: 同伴在另一维度 / 太远未加载 / 已死亡"), btn -> {})
                .bounds(cx - 130, y, 260, 14).build());
            y += 18;
            addRenderableWidget(Button.builder(
                Component.literal("§a🔄 重新查找"), btn -> {
                    companion = CompanionClientState.getCompanion();
                    init();
                }).bounds(cx - 40, y, 80, 18).build());
            y += 22;
            addRenderableWidget(Button.builder(
                Component.literal("§e💡 也可用命令: /companion stats add <属性> <点数>"), btn -> {})
                .bounds(cx - 120, y, 240, 14).build());
            y += 18;
        }

        // ── 待确认提议 ──
        String pendingProposal = companion != null ? companion.getPendingProposalText() : null;
        boolean hasProposal = pendingProposal != null && !pendingProposal.isEmpty();
        if (hasProposal) {
            y += 4;
            addRenderableWidget(Button.builder(
                Component.literal("§e💡 " + pendingProposal), btn -> {}
            ).bounds(cx - 125, y, 250, 16).build());
            y += 20;
            addRenderableWidget(Button.builder(
                Component.literal("§a✅ 确认执行"),
                btn -> { sendCmd("companion confirm"); statusMessage = "§a已确认执行"; init(); }
            ).bounds(cx - 50, y, 100, 18).build());
            y += 24;
        }

        // ── 皮肤选择 ──
        loadAvailableSkins();
        addRenderableWidget(Button.builder(Component.literal("§e皮肤: "), btn -> {})
            .bounds(cx - 120, y, 40, 16).build());

        String skinName = availableSkins.isEmpty() ? "无" : availableSkins.get(selectedSkinIndex);
        String displaySkin = skinName.length() > 12 ? skinName.substring(0, 10) + ".." : skinName;

        addRenderableWidget(Button.builder(Component.literal("<"), btn -> {
            if (!availableSkins.isEmpty()) {
                selectedSkinIndex = (selectedSkinIndex - 1 + availableSkins.size()) % availableSkins.size();
                init();
            }
        }).bounds(cx - 80, y, 25, 16).build());

        addRenderableWidget(Button.builder(Component.literal("§f" + displaySkin), btn -> {})
            .bounds(cx - 52, y, 70, 16).build());

        addRenderableWidget(Button.builder(Component.literal(">"), btn -> {
            if (!availableSkins.isEmpty()) {
                selectedSkinIndex = (selectedSkinIndex + 1) % availableSkins.size();
                init();
            }
        }).bounds(cx + 20, y, 25, 16).build());

        addRenderableWidget(Button.builder(Component.literal("§b应用"), btn -> {
            if (!availableSkins.isEmpty()) {
                String skin = availableSkins.get(selectedSkinIndex);
                sendCmd("companion skin " + skin);
                statusMessage = "§a皮肤: " + skin;
            } else {
                statusMessage = "§c无皮肤";
            }
        }).bounds(cx + 50, y, 40, 16).build());
        y += 24;

        // ── AI 模型选择 ──
        addRenderableWidget(Button.builder(Component.literal("§eAI模型: "), btn -> {})
            .bounds(cx - 120, y, 55, 16).build());

        addRenderableWidget(Button.builder(Component.literal("<"), btn -> {
            int idx = getCurrentModelIndex();
            idx = (idx - 1 + AI_MODELS.length) % AI_MODELS.length;
            currentModel = AI_MODELS[idx];
            init();
        }).bounds(cx - 60, y, 25, 16).build());

        String displayModel = currentModel.length() > 14 ? currentModel.substring(0, 12) + ".." : currentModel;
        addRenderableWidget(Button.builder(Component.literal("§f" + displayModel), btn -> {})
            .bounds(cx - 32, y, 80, 16).build());

        addRenderableWidget(Button.builder(Component.literal(">"), btn -> {
            int idx = getCurrentModelIndex();
            idx = (idx + 1) % AI_MODELS.length;
            currentModel = AI_MODELS[idx];
            init();
        }).bounds(cx + 50, y, 25, 16).build());

        addRenderableWidget(Button.builder(Component.literal("§d应用"), btn -> {
            sendCmd("companion model " + currentModel);
            statusMessage = "§a模型: " + currentModel;
        }).bounds(cx + 80, y, 40, 16).build());
        y += 24;

        // ── 状态消息 ──
        if (!statusMessage.isEmpty()) {
            addRenderableWidget(Button.builder(Component.literal(statusMessage), btn -> {})
                .bounds(cx - 60, y, 160, 14).build());
            y += 18;
        }

        // ── 拾取控制区域 ──
        addRenderableWidget(Button.builder(Component.literal("§6§l拾取设置"), btn -> {}).bounds(cx - 60, y, 120, 14).build());
        y += 18;

        addRenderableWidget(Button.builder(Component.literal(companion != null && companion.isAutoPickupEnabled() ? "§a拾取: ON" : "§7拾取: OFF"),
            btn -> sendCmd("companion pickup")).bounds(cx - 120, y, 75, 18).build());
        addRenderableWidget(Button.builder(Component.literal(companion != null && companion.isPickupOnlyValuable() ? "§e贵重模式" : "§7全物品"),
            btn -> sendCmd("companion pickup valuable")).bounds(cx - 42, y, 75, 18).build());
        addRenderableWidget(Button.builder(Component.literal("§b范围+"), btn -> sendCmd("companion pickup range 10"))
            .bounds(cx + 36, y, 50, 18).build());
        addRenderableWidget(Button.builder(Component.literal("§7范围-"), btn -> sendCmd("companion pickup range 3"))
            .bounds(cx + 88, y, 35, 18).build());
        y += 22;

        // ── 自主模式开关 ──
        addRenderableWidget(Button.builder(
            Component.literal(autoMode ? "§a🤖 自主模式: 开启中" : "§7🤖 自主模式: 关闭"),
            btn -> {
                sendCmd(companion != null && companion.isAutonomousMode() ?
                    "companion autonomous off" : "companion autonomous on");
                statusMessage = companion != null && companion.isAutonomousMode() ?
                    "§7自主模式已关闭 - 同伴等待你的指令" : "§a自主模式已开启 - 同伴会自行决策";
                init();
            }
        ).bounds(cx - 120, y, 240, 20).build());
        y += 26;

        // ── 采集详情入口 ──
        addRenderableWidget(Button.builder(Component.literal("§6⛏ 资源采集详情"), btn -> {
            this.minecraft.setScreen(new GatheringDetailScreen(this));
        }).bounds(cx - 70, y, 140, 22).build());
        y += 28;

        // ── 快捷操作 ──
        addRenderableWidget(Button.builder(Component.literal("§d⚔ 战斗"), btn -> sendCmd("companion skill learn fightZombie"))
            .bounds(cx - 120, y, 55, 18).build());
        addRenderableWidget(Button.builder(Component.literal("§a⛏ 采集"), btn -> sendCmd("companion gather"))
            .bounds(cx - 60, y, 55, 18).build());
        addRenderableWidget(Button.builder(Component.literal("§3💬 对话"), btn -> {
            this.minecraft.setScreen(new ChatScreen("/companion chat "));
        }).bounds(cx, y, 55, 18).build());
        boolean chatOn = com.aiworkbench.companion.client.CompanionClientState.isChatMode();
        addRenderableWidget(Button.builder(Component.literal(chatOn ? "§bJ=聊天ON" : "§7J=聊天OFF"),
            btn -> { }).bounds(cx + 60, y, 55, 18).build());
        y += 24;

        // ── 关闭按钮 ──
        addRenderableWidget(Button.builder(Component.literal("§c关闭"), btn -> onClose())
            .bounds(cx - 30, y, 60, 18).build());
    }

    private void addStatRow(String label, String stat, int currentPoints, int y, int cx) {
        addRenderableWidget(Button.builder(Component.literal(label + " +" + currentPoints), btn -> {})
            .bounds(cx - 120, y, 70, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+1"), btn -> {
            sendCmd("companion stats add " + stat + " 1");
        }).bounds(cx - 45, y, 30, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+5"), btn -> {
            sendCmd("companion stats add " + stat + " 5");
        }).bounds(cx - 10, y, 30, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+10"), btn -> {
            sendCmd("companion stats add " + stat + " 10");
        }).bounds(cx + 25, y, 35, 18).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = Math.max(0, CONTENT_HEIGHT - viewHeight);
        scrollOffset -= verticalAmount * 20;
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        init();
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xC01a1a2e);
        graphics.fill(0, 0, this.width, this.height / 2, 0xC016213e);
        super.render(graphics, mouseX, mouseY, partialTick);

        if (companion != null) {
            int cx = this.width / 2;
            int barX = cx - 80;
            int barY = 35 - (int) scrollOffset;
            float health = companion.getHealth();
            float maxHealth = companion.getMaxHealth();
            float pct = Math.min(health / maxHealth, 1.0f);
            graphics.fill(barX, barY, barX + 160, barY + 4, 0xFF555555);
            int fillW = (int) (160 * pct);
            int color = pct > 0.5f ? 0xFF00AA00 : (pct > 0.25f ? 0xFFFFAA00 : 0xFFFF5555);
            if (fillW > 0) {
                graphics.fill(barX, barY, barX + fillW, barY + 4, color);
            }
        }

        if (CONTENT_HEIGHT > viewHeight) {
            int sx = this.width - 15;
            int maxScroll = Math.max(1, CONTENT_HEIGHT - viewHeight);
            int thumbH = 60;
            int thumbY = (int) (scrollOffset / maxScroll * (viewHeight - thumbH)) + 10;
            graphics.fill(sx, 10, sx + 6, 10 + viewHeight, 0x80334155);
            graphics.fill(sx, thumbY, sx + 6, thumbY + thumbH, 0xFF10B981);
        }
    }

    private void loadAvailableSkins() {
        availableSkins.clear();
        java.io.File skinsDir = new java.io.File(getSkinsPath());
        if (skinsDir.exists() && skinsDir.isDirectory()) {
            java.io.File[] files = skinsDir.listFiles((dir, name) -> name.endsWith(".png"));
            if (files != null) {
                for (java.io.File f : files) {
                    availableSkins.add(f.getName().replace(".png", ""));
                }
            }
        }
        if (availableSkins.isEmpty()) {
            availableSkins.add("default");
        }
    }

    private String getSkinsPath() {
        var mc = Minecraft.getInstance();
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            return mc.getSingleplayerServer()
                .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .toUri().resolve("aicompanion/skins").getPath();
        }
        return mc.gameDirectory.getPath() + "/aicompanion/skins";
    }

    private int getCurrentModelIndex() {
        for (int i = 0; i < AI_MODELS.length; i++) {
            if (AI_MODELS[i].equals(currentModel)) return i;
        }
        return 0;
    }

    private void sendCmd(String cmd) {
        if (this.minecraft != null && this.minecraft.getConnection() != null) {
            this.minecraft.getConnection().sendCommand(cmd);
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    if (this.minecraft.screen == CompanionSettingsScreen.this) {
                        this.minecraft.setScreen(new CompanionSettingsScreen(parent, companion));
                    }
                });
            }
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
