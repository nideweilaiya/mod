package com.aiworkbench.companion.client.gui;

import com.aiworkbench.companion.inventory.CompanionContainer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 同伴背包界面 — 基于 AbstractContainerScreen 的标准实现。
 * <p>
 * 布局：
 *   装备栏（6格）+ 同伴背包（9×3）+ 玩家背包（9×3）+ 快捷栏（9）
 */
public class CompanionContainerScreen extends AbstractContainerScreen<CompanionContainer> {

    private static final int TEX_WIDTH = 194;
    private static final int TEX_HEIGHT = 222;

    public CompanionContainerScreen(CompanionContainer container, Inventory playerInv, Component title) {
        super(container, playerInv, title);
        this.imageWidth = TEX_WIDTH;
        this.imageHeight = TEX_HEIGHT;
        this.inventoryLabelY = this.imageHeight - 94; // 玩家背包标签位置
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        // 主背景
        graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xC0101010);
        graphics.fill(x + 2, y + 2, x + imageWidth - 2, y + imageHeight - 2, 0xDD1A1A2E);

        // 装备栏标签
        graphics.drawString(this.font, Component.literal("装备"),
            x + (imageWidth - this.font.width("装备")) / 2, y + 6, 0xAAAAAA, false);

        // 同伴背包标签
        int compInvLabelY = y + 42;
        graphics.drawString(this.font, Component.literal("同伴背包"),
            x + 8, compInvLabelY, 0xFFD700, false);

        // 槽位背景
        int slotSize = 16;
        // 装备槽背景 (6个)
        for (int i = 0; i < 6; i++) {
            int sx = x + 8 + (9 * 18 - 6 * 18) / 2 + i * 18 + 1;
            int sy = y + 18 + 1;
            graphics.fill(sx, sy, sx + slotSize, sy + slotSize, 0xFF3A3A5A);
        }
        // 同伴背包槽位
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int sx = x + 8 + col * 18 + 1;
                int sy = y + 54 + row * 18 + 1;
                graphics.fill(sx, sy, sx + slotSize, sy + slotSize, 0xFF2A2A4A);
            }
        }
        // 玩家背包槽位
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int sx = x + 8 + col * 18 + 1;
                int sy = y + 122 + row * 18 + 1;
                graphics.fill(sx, sy, sx + slotSize, sy + slotSize, 0xFF2A2A2A);
            }
        }
        // 快捷栏槽位
        for (int col = 0; col < 9; col++) {
            int sx = x + 8 + col * 18 + 1;
            int sy = y + 180 + 1;
            graphics.fill(sx, sy, sx + slotSize, sy + slotSize, 0xFF3A3A2A);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 在 renderBg 中已绘制标签，此处不额外绘制以避免与 inventoryLabelY 冲突
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
