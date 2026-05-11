package com.aiworkbench.companion.client.gui;

import com.aiworkbench.companion.client.CompanionClientState;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 右键同伴弹出的快捷操作菜单 — 无需指令即可操作同伴。
 * <p>
 * 布局: 5个按钮垂直排列，点击后关闭菜单并执行操作。
 */
public class CompanionQuickMenuScreen extends Screen {
    private final Screen parent;

    public CompanionQuickMenuScreen(Screen parent) {
        super(Component.literal("同伴操作"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        int cx = this.width / 2;
        int cy = this.height / 2 - 60;

        AutomatonEntity c = CompanionClientState.getCompanion();
        boolean gathering = c != null && c.isGatherModeEnabled();
        boolean guarding = c != null && c.isGuardModeEnabled();

        // 标题
        addRenderableWidget(Button.builder(Component.literal("§6§l同伴操作"), btn -> {})
            .bounds(cx - 50, cy, 100, 16).build());

        // 采集模式
        addRenderableWidget(Button.builder(
            Component.literal(gathering ? "§6⛏ 采集中（点击停止）" : "§7⛏ 开始采集"),
            btn -> {
                sendCmd("companion gather");
                this.onClose();
            }).bounds(cx - 70, cy + 22, 140, 22).build());

        // 守护模式
        addRenderableWidget(Button.builder(
            Component.literal(guarding ? "§c🛡 守护中（点击停止）" : "§7🛡 守护模式"),
            btn -> {
                sendCmd("companion guard");
                this.onClose();
            }).bounds(cx - 70, cy + 48, 140, 22).build());

        // 背包
        addRenderableWidget(Button.builder(Component.literal("§e🎒 打开背包"), btn -> {
            sendCmd("companion openinv");
            this.onClose();
        }).bounds(cx - 70, cy + 74, 140, 22).build());

        // 传送
        addRenderableWidget(Button.builder(Component.literal("§b📍 传送到我"), btn -> {
            sendCmd("companion teleport");
            this.onClose();
        }).bounds(cx - 70, cy + 100, 140, 22).build());

        // 设置
        addRenderableWidget(Button.builder(Component.literal("§d⚙ 同伴设置"), btn -> {
            this.minecraft.setScreen(new CompanionSettingsScreen(parent));
        }).bounds(cx - 70, cy + 126, 140, 22).build());

        // 取消
        addRenderableWidget(Button.builder(Component.literal("§7关闭"), btn -> onClose())
            .bounds(cx - 30, cy + 158, 60, 18).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xC0101525);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    private void sendCmd(String cmd) {
        if (minecraft != null && minecraft.getConnection() != null)
            minecraft.getConnection().sendCommand(cmd);
    }
}
