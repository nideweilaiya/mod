package com.aiworkbench.companion.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Companion List Screen - 角色列表界面 / 同伴状态面板
 * 按 C 键打开，显示当前同伴状态和快捷操作
 */
public class CompanionListScreen extends Screen {

    private final Screen parent;

    // Companion status (queried from server)
    private String companionName = "未找到同伴";
    private String companionMode = "§a跟随中";
    private boolean hasCompanion = false;

    // Scroll
    private double scrollOffset = 0;
    private int viewHeight;
    private static final int CONTENT_HEIGHT = 280;

    // Companion data structure (kept for CompanionDetailScreen compatibility)
    public static class CompanionData {
        public final String id;
        public final String name;
        public final String description;
        public final String skinUrl;
        public final boolean isOnline;

        public CompanionData(String id, String name, String desc, String skinUrl, boolean online) {
            this.id = id;
            this.name = name;
            this.description = desc;
            this.skinUrl = skinUrl;
            this.isOnline = online;
        }
    }

    public CompanionListScreen(Screen parent) {
        super(Component.literal("AI Companion"));
        this.parent = parent;

        // Set initial name from player
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            this.companionName = mc.player.getName().getString() + "'s Companion";
        }

        queryCompanionStatus();
    }

    /**
     * Query companion status from server via command
     */
    private void queryCompanionStatus() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            // Send command to query status
            mc.getConnection().sendCommand("companion status");
        }
    }

    @Override
    protected void init() {
        clearWidgets();
        viewHeight = this.height - 100;

        int centerX = this.width / 2;

        // === 返回按钮 ===
        addRenderableWidget(Button.builder(
                Component.literal("§c✕"),
                btn -> onClose())
                .bounds(this.width - 40, 10, 30, 20)
                .build());

        // === 刷新按钮 ===
        addRenderableWidget(Button.builder(
                Component.literal("§e↻"),
                btn -> queryCompanionStatus())
                .bounds(this.width - 75, 10, 30, 20)
                .build());

        // === 同伴名称显示 ===
        int infoY = 50 - (int) scrollOffset;

        addRenderableWidget(Button.builder(
                Component.literal("§f同伴名称:"),
                btn -> {})
                .bounds(centerX - 80, infoY, 80, 16)
                .build());

        addRenderableWidget(Button.builder(
                Component.literal("§b" + companionName),
                btn -> {})
                .bounds(centerX, infoY, 80, 16)
                .build());

        // === 当前模式 ===
        int modeY = infoY + 30;

        addRenderableWidget(Button.builder(
                Component.literal("§f当前状态:"),
                btn -> {})
                .bounds(centerX - 80, modeY, 80, 16)
                .build());

        addRenderableWidget(Button.builder(
                Component.literal(companionMode),
                btn -> {})
                .bounds(centerX, modeY, 80, 16)
                .build());

        // === 分隔线 ===
        addRenderableWidget(Button.builder(
                Component.literal("§7─────────────────────"),
                btn -> {})
                .bounds(centerX - 80, modeY + 25, 160, 12)
                .build());

        // === 模式切换按钮 ===
        int btnY = modeY + 50;

        addRenderableWidget(Button.builder(
                Component.literal("§c⚔ 守护模式"),
                btn -> sendCommand("companion guard"))
                .bounds(centerX - 80, btnY, 155, 22)
                .build());

        addRenderableWidget(Button.builder(
                Component.literal("§b⛏ 挖掘模式"),
                btn -> sendCommand("companion mine"))
                .bounds(centerX - 80, btnY + 28, 75, 22)
                .build());

        addRenderableWidget(Button.builder(
                Component.literal("§6🪓 砍伐模式"),
                btn -> sendCommand("companion chop"))
                .bounds(centerX + 1, btnY + 28, 74, 22)
                .build());

        addRenderableWidget(Button.builder(
                Component.literal("§a✓ 跟随模式"),
                btn -> sendCommand("companion follow"))
                .bounds(centerX - 80, btnY + 56, 155, 22)
                .build());

        // === 背包和聊天按钮 ===
        int actionY = btnY + 90;

        addRenderableWidget(Button.builder(
                Component.literal("§e🎒 打开背包"),
                btn -> {
                    // Close this screen and open companion inventory via Container
                    onClose();
                    if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.connection != null) {
                        Minecraft.getInstance().player.connection.sendCommand("companion openinv");
                    }
                })
                .bounds(centerX - 80, actionY, 75, 22)
                .build());

        addRenderableWidget(Button.builder(
                Component.literal("§d💬 和同伴对话"),
                btn -> {
                    // Close this screen and open chat with command
                    onClose();
                    Minecraft.getInstance().setScreen(new ChatScreen("/companion chat "));
                })
                .bounds(centerX + 1, actionY, 74, 22)
                .build());

        // === 提示 ===
        addRenderableWidget(Button.builder(
                Component.literal("§7按 C 键关闭 | G 键打开设置"),
                btn -> {})
                .bounds(centerX - 90, actionY + 40, 180, 14)
                .build());

        // === 关闭按钮 ===
        addRenderableWidget(Button.builder(
                Component.literal("§c关闭面板"),
                btn -> onClose())
                .bounds(centerX - 40, actionY + 65, 80, 20)
                .build());
    }

    /**
     * Send command to server
     */
    private void sendCommand(String command) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            mc.getConnection().sendCommand(command);
            // Refresh status on client thread after a short delay
            Minecraft.getInstance().execute(() -> {
                try {
                    Thread.sleep(300);
                    queryCompanionStatus();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int scrollStep = 20;
        int maxScroll = Math.max(0, CONTENT_HEIGHT - viewHeight);

        double oldOffset = scrollOffset;
        scrollOffset -= verticalAmount * scrollStep;
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        // Only rebuild widgets if scroll actually changed
        if (oldOffset != scrollOffset) {
            init();
        }
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 渐变背景
        graphics.fill(0, 0, this.width, this.height / 2, 0xC0_16213e);
        graphics.fill(0, this.height / 2, this.width, this.height, 0xC0_1a1a2e);

        super.render(graphics, mouseX, mouseY, partialTick);

        // 绘制滚动条
        if (CONTENT_HEIGHT > viewHeight) {
            int scrollBarX = this.width - 15;
            int scrollBarHeight = Math.min(60, viewHeight / 3);
            int maxScroll = Math.max(1, CONTENT_HEIGHT - viewHeight);
            int thumbY = (int) (scrollOffset / maxScroll * (viewHeight - scrollBarHeight)) + 50;

            graphics.fill(scrollBarX, 50, scrollBarX + 6, 50 + viewHeight, 0x40_334155);
            graphics.fill(scrollBarX, thumbY, scrollBarX + 6, thumbY + scrollBarHeight, 0xFF_10B981);
        }

        // 绘制标题（不受滚动影响，始终固定在最顶部）
        int centerX = this.width / 2;
        graphics.drawString(this.font, Component.literal("§b§l⚙ 同伴状态面板"),
                centerX - 70, 15, 0xFFFFFF, true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    /**
     * Update companion name (can be called from other parts)
     */
    public void setCompanionName(String name) {
        this.companionName = name;
        init();
    }

    /**
     * Update companion mode (can be called from other parts)
     */
    public void setCompanionMode(String mode) {
        this.companionMode = mode;
        init();
    }

    /**
     * Update has companion status
     */
    public void setHasCompanion(boolean has) {
        this.hasCompanion = has;
        init();
    }
}
