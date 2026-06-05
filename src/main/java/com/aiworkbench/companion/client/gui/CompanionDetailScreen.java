package com.aiworkbench.companion.client.gui;

import com.aiworkbench.companion.AICompanionMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Companion Detail Screen - 角色详情界面
 * 参考 Player2NPC 的 CharacterDetailScreen 设计
 *
 * 功能：
 * - 显示角色大头像
 * - 显示角色名称、描述、AI模型、状态
 * - 提供召唤/跟随/换肤/对话等操作按钮
 */
public class CompanionDetailScreen extends Screen {

    private final Screen parent;
    private final CompanionListScreen.CompanionData companion;

    public CompanionDetailScreen(Screen parent, CompanionListScreen.CompanionData companion) {
        super(Component.literal("角色详情"));
        this.parent = parent;
        this.companion = companion;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int bottomY = this.height - 40;

        // 召唤按钮
        addRenderableWidget(Button.builder(Component.literal("§a召唤"), btn -> {
            sendCommand("summon " + companion.id);
            displayStatus("正在召唤 " + companion.name + "...");
        }).bounds(centerX - 220, bottomY, 100, 20).build());

        // 跟随按钮
        addRenderableWidget(Button.builder(Component.literal("§e跟随"), btn -> {
            sendCommand("follow " + companion.id);
            displayStatus(companion.name + " 开始跟随你");
        }).bounds(centerX - 105, bottomY, 100, 20).build());

        // 换肤按钮
        addRenderableWidget(Button.builder(Component.literal("§b换肤"), btn -> {
            sendCommand("skin random " + companion.id);
            displayStatus("正在更换 " + companion.name + " 的皮肤...");
        }).bounds(centerX + 10, bottomY, 100, 20).build());

        // 对话按钮
        addRenderableWidget(Button.builder(Component.literal("§d对话"), btn -> {
            sendCommand("chat " + companion.id);
            displayStatus("打开与 " + companion.name + " 的对话...");
        }).bounds(centerX + 115, bottomY, 100, 20).build());

        // 返回按钮
        addRenderableWidget(Button.builder(Component.literal("§c返回"), btn -> {
            onClose();
        }).bounds(centerX - 50, bottomY + 30, 100, 20).build());
    }

    private void sendCommand(String cmd) {
        if (this.minecraft != null && this.minecraft.getConnection() != null) {
            this.minecraft.getConnection().sendCommand("companion " + cmd);
        }
    }

    private void displayStatus(String message) {
        AICompanionMod.LOGGER.info("[DetailScreen] " + message);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 渐变背景
        graphics.fill(0, 0, this.width, this.height / 2, 0xC0_16213e);
        graphics.fill(0, this.height / 2, this.width, this.height, 0xC0_1a1a2e);

        int centerX = this.width / 2;
        var font = Minecraft.getInstance().font;

        // 角色名称 (使用普通 drawCenteredString，带阴影效果)
        graphics.drawCenteredString(font, companion.name, centerX, 20, 0xFFFFFF);

        // 状态标签
        String status = companion.isOnline ? "● 在线" : "○ 离线";
        int statusColor = companion.isOnline ? 0xFF_10B981 : 0xFF_6B7280;
        graphics.drawCenteredString(font, status, centerX, 40, statusColor);

        // 头像区域 (简化版)
        int headSize = 96;
        int headX = centerX - headSize / 2;
        int headY = 60;

        // 头像背景框
        graphics.fill(headX - 2, headY - 2, headX + headSize + 2, headY + headSize + 2, 0xFF_3B82F6);
        graphics.fill(headX, headY, headX + headSize, headY + headSize, 0xFF_1a1a2e);

        // 头像首字母
        String initial = companion.name.length() > 0 ? companion.name.substring(0, 1).toUpperCase() : "?";
        graphics.drawCenteredString(font, initial, centerX, headY + 30, 0xFF_10B981);

        // 描述文本
        int textY = headY + headSize + 20;
        if (companion.description != null && !companion.description.isEmpty()) {
            String[] lines = wrapText(companion.description, 200);
            for (String line : lines) {
                graphics.drawCenteredString(font, line, centerX, textY, 0xFF_9CA3AF);
                textY += font.lineHeight + 2;
            }
        }

        // AI 模型信息 (模拟)
        textY += 15;
        graphics.drawCenteredString(font, "§7AI 模型: §fqwen3.5:4b", centerX, textY, 0xFF_9CA3AF);

        // 关系值 (模拟)
        textY += font.lineHeight + 5;
        graphics.drawCenteredString(font, "§7关系值: §a85", centerX, textY, 0xFF_9CA3AF);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private String[] wrapText(String text, int maxWidth) {
        int charWidth = 4;
        int maxChars = maxWidth / charWidth;
        int len = text.length();

        if (len <= maxChars) {
            return new String[]{text};
        }

        java.util.List<String> lines = new java.util.ArrayList<>();
        int start = 0;
        while (start < len) {
            int end = Math.min(start + maxChars, len);
            if (end < len) {
                int lastSpace = text.lastIndexOf(' ', end);
                if (lastSpace > start) {
                    end = lastSpace;
                }
            }
            lines.add(text.substring(start, end));
            start = end;
            while (start < len && text.charAt(start) == ' ') start++;
        }

        return lines.toArray(new String[0]);
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
}
