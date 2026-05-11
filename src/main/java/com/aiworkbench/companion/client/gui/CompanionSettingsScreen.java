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
 * Opened via G key or /companion gui command.
 */
public class CompanionSettingsScreen extends Screen {
    private final Screen parent;
    private List<String> availableSkins = new ArrayList<>();
    private int selectedSkinIndex = 0;
    private String currentModel = "llama3.2:latest";
    private String statusMessage = "";

    // Scroll
    private double scrollOffset = 0;
    private static final int CONTENT_HEIGHT = 600;
    private int viewHeight;

    private static final String[] AI_MODELS = {
        "llama3.2:latest",
        "llama3.1:8b",
        "qwen3.5:4b"
    };

    // Companion stats (cached from client state)
    private AutomatonEntity companion;

    public CompanionSettingsScreen(Screen parent) {
        super(Component.literal("同伴设置"));
        this.parent = parent;
        if (Minecraft.getInstance().player != null) {
            currentModel = CompanionConfig.getModel(Minecraft.getInstance().player.getUUID());
        }
        // Read companion from client state
        companion = CompanionClientState.getCompanion();
    }

    @Override
    protected void init() {
        clearWidgets();
        viewHeight = this.height - 30;
        int cx = this.width / 2;

        // Refresh companion reference
        companion = CompanionClientState.getCompanion();

        // ===== Stats Section (top, compact) =====
        int sy = 15 - (int) scrollOffset;

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
                btn -> {}).bounds(cx - 120, sy, 240, 16).build());
        } else {
            addRenderableWidget(Button.builder(
                Component.literal("§7无同伴数据"),
                btn -> {}).bounds(cx - 60, sy, 120, 16).build());
        }

        // ===== Mode Row =====
        int my = sy + 24;

        addRenderableWidget(Button.builder(Component.literal("§a跟随"), btn -> sendCmd("companion follow"))
            .bounds(cx - 100, my, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("§c守护"), btn -> sendCmd("companion guard"))
            .bounds(cx - 35, my, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("§6采集"), btn -> sendCmd("companion gather"))
            .bounds(cx + 30, my, 60, 20).build());

        // ===== 自主模式小指示器（顶部快捷入口）=====
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
        ).bounds(cx + 120, my, 22, 20).build());

        // ===== Action Row =====
        int ay = my + 26;

        addRenderableWidget(Button.builder(Component.literal("§3传送"), btn -> sendCmd("companion teleport"))
            .bounds(cx - 120, ay, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("§7隐藏"), btn -> sendCmd("companion hide"))
            .bounds(cx - 60, ay, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("§e背包"), btn -> {
            if (this.minecraft.player != null && this.minecraft.player.connection != null) {
                this.minecraft.player.connection.sendCommand("companion openinv");
            }
            this.onClose();
        }).bounds(cx, ay, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("§a状态"), btn -> sendCmd("companion status"))
            .bounds(cx + 60, ay, 55, 20).build());

        // ===== Chat / Level =====
        int cy = ay + 28;
        addRenderableWidget(Button.builder(Component.literal("§b💬 和同伴聊天"), btn -> {
            this.minecraft.setScreen(new ChatScreen("/companion chat "));
        }).bounds(cx - 100, cy, 200, 18).build());

        addRenderableWidget(Button.builder(Component.literal("§e📊 查看等级"), btn -> sendCmd("companion level"))
            .bounds(cx - 100, cy + 22, 200, 18).build());

        // ===== 待确认提议 =====
        int propY = cy + 48;
        String pendingProposal = companion != null ? companion.getPendingProposalText() : null;
        boolean hasProposal = pendingProposal != null && !pendingProposal.isEmpty();
        if (hasProposal) {
            addRenderableWidget(Button.builder(
                Component.literal("§e💡 " + pendingProposal), btn -> {}
            ).bounds(cx - 125, propY, 250, 16).build());
            addRenderableWidget(Button.builder(
                Component.literal("§a✅ 确认执行"),
                btn -> { sendCmd("companion confirm"); statusMessage = "§a已确认执行"; init(); }
            ).bounds(cx - 50, propY + 20, 100, 18).build());
        }

        // ===== Skin Section =====
        int sky = hasProposal ? propY + 44 : propY;
        loadAvailableSkins();

        addRenderableWidget(Button.builder(Component.literal("§e皮肤: "), btn -> {})
            .bounds(cx - 120, sky, 40, 16).build());

        String skinName = availableSkins.isEmpty() ? "无" : availableSkins.get(selectedSkinIndex);
        String displaySkin = skinName.length() > 12 ? skinName.substring(0, 10) + ".." : skinName;

        addRenderableWidget(Button.builder(Component.literal("<"), btn -> {
            if (!availableSkins.isEmpty()) {
                selectedSkinIndex = (selectedSkinIndex - 1 + availableSkins.size()) % availableSkins.size();
                init();
            }
        }).bounds(cx - 80, sky, 25, 16).build());

        addRenderableWidget(Button.builder(Component.literal("§f" + displaySkin), btn -> {})
            .bounds(cx - 52, sky, 70, 16).build());

        addRenderableWidget(Button.builder(Component.literal(">"), btn -> {
            if (!availableSkins.isEmpty()) {
                selectedSkinIndex = (selectedSkinIndex + 1) % availableSkins.size();
                init();
            }
        }).bounds(cx + 20, sky, 25, 16).build());

        addRenderableWidget(Button.builder(Component.literal("§b应用"), btn -> {
            if (!availableSkins.isEmpty()) {
                String skin = availableSkins.get(selectedSkinIndex);
                sendCmd("companion skin " + skin);
                statusMessage = "§a皮肤: " + skin;
            } else {
                statusMessage = "§c无皮肤";
            }
        }).bounds(cx + 50, sky, 40, 16).build());

        // ===== AI Model Section =====
        int mdy = sky + 24;

        addRenderableWidget(Button.builder(Component.literal("§eAI模型: "), btn -> {})
            .bounds(cx - 120, mdy, 55, 16).build());

        addRenderableWidget(Button.builder(Component.literal("<"), btn -> {
            int idx = getCurrentModelIndex();
            idx = (idx - 1 + AI_MODELS.length) % AI_MODELS.length;
            currentModel = AI_MODELS[idx];
            init();
        }).bounds(cx - 60, mdy, 25, 16).build());

        String displayModel = currentModel.length() > 14 ? currentModel.substring(0, 12) + ".." : currentModel;
        addRenderableWidget(Button.builder(Component.literal("§f" + displayModel), btn -> {})
            .bounds(cx - 32, mdy, 80, 16).build());

        addRenderableWidget(Button.builder(Component.literal(">"), btn -> {
            int idx = getCurrentModelIndex();
            idx = (idx + 1) % AI_MODELS.length;
            currentModel = AI_MODELS[idx];
            init();
        }).bounds(cx + 50, mdy, 25, 16).build());

        addRenderableWidget(Button.builder(Component.literal("§d应用"), btn -> {
            sendCmd("companion model " + currentModel);
            statusMessage = "§a模型: " + currentModel;
        }).bounds(cx + 80, mdy, 40, 16).build());

        // ===== Status message =====
        if (!statusMessage.isEmpty()) {
            addRenderableWidget(Button.builder(Component.literal(statusMessage), btn -> {})
                .bounds(cx - 60, mdy + 24, 160, 14).build());
        }

        // ===== 属性加点区域 =====
        int stY = mdy + 52;
        if (companion != null) {
            int avail = companion.getAvailablePoints();
            addRenderableWidget(Button.builder(
                Component.literal("§6§l属性加点  §e可用点数: §a" + avail), btn -> {})
                .bounds(cx - 120, stY, 240, 14).build());

            // 体力
            int r1 = stY + 18;
            addStatRow("§c体力", "vitality", companion.getVitalityPoints(), r1, cx);
            // 力量
            int r2 = r1 + 22;
            addStatRow("§b力量", "strength", companion.getStrengthPoints(), r2, cx);
            // 速度
            int r3 = r2 + 22;
            addStatRow("§a速度", "speed", companion.getSpeedPoints(), r3, cx);
            // 防御
            int r4 = r3 + 22;
            addStatRow("§7防御", "defense", companion.getDefensePoints(), r4, cx);

            // 重置按钮
            addRenderableWidget(Button.builder(Component.literal("§e重置全部"), btn -> {
                sendCmd("companion stats reset");
                statusMessage = "§e属性已重置";
            }).bounds(cx - 40, r4 + 26, 80, 16).build());
        }

        // ===== 拾取控制区域 =====
        int pkY = stY + 136;
        addRenderableWidget(Button.builder(Component.literal("§6§l拾取设置"), btn -> {}).bounds(cx - 60, pkY, 120, 14).build());

        addRenderableWidget(Button.builder(Component.literal(companion != null && companion.isAutoPickupEnabled() ? "§a拾取: ON" : "§7拾取: OFF"),
            btn -> sendCmd("companion pickup")).bounds(cx - 120, pkY + 18, 75, 18).build());
        addRenderableWidget(Button.builder(Component.literal(companion != null && companion.isPickupOnlyValuable() ? "§e贵重模式" : "§7全物品"),
            btn -> sendCmd("companion pickup valuable")).bounds(cx - 42, pkY + 18, 75, 18).build());
        addRenderableWidget(Button.builder(Component.literal("§b范围+"), btn -> sendCmd("companion pickup range 10"))
            .bounds(cx + 36, pkY + 18, 50, 18).build());
        addRenderableWidget(Button.builder(Component.literal("§7范围-"), btn -> sendCmd("companion pickup range 3"))
            .bounds(cx + 88, pkY + 18, 35, 18).build());

        // ===== 自主模式开关（放在拾取和技能之间，显眼位置）=====
        int autoY2 = pkY + 38;
        addRenderableWidget(Button.builder(
            Component.literal(autoMode ? "§a🤖 自主模式: 开启中" : "§7🤖 自主模式: 关闭"),
            btn -> {
                sendCmd(companion != null && companion.isAutonomousMode() ?
                    "companion autonomous off" : "companion autonomous on");
                statusMessage = companion != null && companion.isAutonomousMode() ?
                    "§7自主模式已关闭 - 同伴等待你的指令" : "§a自主模式已开启 - 同伴会自行决策";
                init();
            }
        ).bounds(cx - 120, autoY2, 240, 20).build());

        // ===== 采集详情入口 =====
        int gdY = autoY2 + 26;
        addRenderableWidget(Button.builder(Component.literal("§6⛏ 资源采集详情"), btn -> {
            this.minecraft.setScreen(new GatheringDetailScreen(this));
        }).bounds(cx - 70, gdY, 140, 22).build());

        // 快捷操作（精简为4个实用按钮）
        addRenderableWidget(Button.builder(Component.literal("§d⚔ 战斗"), btn -> sendCmd("companion skill learn fightZombie"))
            .bounds(cx - 120, gdY + 28, 55, 18).build());
        addRenderableWidget(Button.builder(Component.literal("§a⛏ 采集"), btn -> sendCmd("companion gather"))
            .bounds(cx - 60, gdY + 28, 55, 18).build());
        addRenderableWidget(Button.builder(Component.literal("§3💬 对话"), btn -> {
            this.minecraft.setScreen(new ChatScreen("/companion chat "));
        }).bounds(cx, gdY + 28, 55, 18).build());
        boolean chatOn = com.aiworkbench.companion.client.CompanionClientState.isChatMode();
        addRenderableWidget(Button.builder(Component.literal(chatOn ? "§bJ=聊天ON" : "§7J=聊天OFF"),
            btn -> { /* 仅指示，J键切换 */ }).bounds(cx + 60, gdY + 28, 55, 18).build());

        // ===== Close =====
        addRenderableWidget(Button.builder(Component.literal("§c关闭"), btn -> onClose())
            .bounds(cx - 30, gdY + 70, 60, 18).build());
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
        // Gradient background
        graphics.fill(0, 0, this.width, this.height, 0xC01a1a2e);
        graphics.fill(0, 0, this.width, this.height / 2, 0xC016213e);

        super.render(graphics, mouseX, mouseY, partialTick);

        // Draw companion health bar below stats if available
        if (companion != null) {
            int cx = this.width / 2;
            int barX = cx - 80;
            int barY = 35 - (int) scrollOffset;
            float health = companion.getHealth();
            float maxHealth = companion.getMaxHealth();
            float pct = Math.min(health / maxHealth, 1.0f);

            // Background
            graphics.fill(barX, barY, barX + 160, barY + 4, 0xFF555555);
            // Fill
            int fillW = (int) (160 * pct);
            int color = pct > 0.5f ? 0xFF00AA00 : (pct > 0.25f ? 0xFFFFAA00 : 0xFFFF5555);
            if (fillW > 0) {
                graphics.fill(barX, barY, barX + fillW, barY + 4, color);
            }
        }

        // Scrollbar
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
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
