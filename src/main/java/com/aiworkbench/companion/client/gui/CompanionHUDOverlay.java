package com.aiworkbench.companion.client.gui;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.client.CompanionClientState;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Companion HUD overlay - shows companion status in the top-right corner.
 * Displays: name, health bar, working mode, distance.
 * Toggle with H key (see CompanionKeyHandler).
 */
@OnlyIn(Dist.CLIENT)
public class CompanionHUDOverlay {

    private static boolean hudEnabled = true;

    // Layout constants
    private static final int PANEL_WIDTH = 150;
    private static final int PANEL_PADDING = 6;
    private static final int LINE_HEIGHT = 12;
    private static final int TOP_MARGIN = 10;
    private static final int RIGHT_MARGIN = 10;
    private static final int BAR_WIDTH = 80;
    private static final int BAR_HEIGHT = 6;
    private static final int XP_BAR_HEIGHT = 4;

    public static void toggleHUD() {
        hudEnabled = !hudEnabled;
    }

    public static boolean isHUDEnabled() {
        return hudEnabled;
    }

    /**
     * Render the HUD overlay. Called from Forge's RegisterGuiOverlaysEvent.
     */
    public static void render(GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.options.hideGui) return;
            if (!hudEnabled) return;
            if (mc.font == null) return;

            // ====== 聊天模式指示器（顶部居中，无关是否有同伴） ======
            if (CompanionClientState.isChatMode()) {
                int topCenterX = screenWidth / 2;
                String chatModeText = "§b💬 聊天模式 — 消息将发送给同伴";
                int textW = mc.font.width(chatModeText);
                int bgW = textW + 16;
                int bgX = topCenterX - bgW / 2;
                int bgY = 2;
                graphics.fill(bgX, bgY, bgX + bgW, bgY + 14, 0x90000000);
                graphics.fill(bgX, bgY, bgX + bgW, bgY + 1, 0xFF10B981);
                graphics.drawString(mc.font, chatModeText, bgX + 8, bgY + 3, 0xFFFFFF, false);
            }

            // ====== 同伴面板（右上角） ======
            AutomatonEntity companion = CompanionClientState.getCompanion();
            if (companion == null) return;

            Player player = mc.player;

            // Calculate panel dimensions: name+level, health, xp bar, mode, distance
            int panelHeight = 5 * LINE_HEIGHT + PANEL_PADDING * 2 + XP_BAR_HEIGHT;

            // Panel starts from right side
            int panelX = screenWidth - RIGHT_MARGIN - PANEL_WIDTH;
            int panelY = TOP_MARGIN;

            // Draw semi-transparent background
            graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, 0x80000000);

            // Gold border for visual clarity
            graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + 1, 0xFF10B981);
            graphics.fill(panelX, panelY + panelHeight - 1, panelX + PANEL_WIDTH, panelY + panelHeight, 0xFF10B981);
            graphics.fill(panelX, panelY, panelX + 1, panelY + panelHeight, 0xFF10B981);
            graphics.fill(panelX + PANEL_WIDTH - 1, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, 0xFF10B981);

            int textX = panelX + PANEL_PADDING;
            int textY = panelY + PANEL_PADDING;

            // Line 1: Companion name + Level
            String name = companion.getCustomName() != null
                ? companion.getCustomName().getString()
                : "Companion";
            int level = companion.getLevel();
            graphics.drawString(mc.font, name, textX, textY, 0xFFFFFF, false);
            graphics.drawString(mc.font, " Lv." + level,
                textX + mc.font.width(name), textY, 0xFFAA00, false); // Gold

            // Line 2: Health text + bar
            textY += LINE_HEIGHT;
            float health = companion.getHealth();
            float maxHealth = companion.getMaxHealth();
            float healthPct = Math.min(health / maxHealth, 1.0f);

            String healthText = "HP: " + (int)health + "/" + (int)maxHealth;
            graphics.drawString(mc.font, healthText, textX, textY, 0xFFFFFF, false);

            // Health bar
            int barX = textX + mc.font.width(healthText) + 4;
            int barY = textY + 3;
            graphics.fill(barX, barY, barX + BAR_WIDTH, barY + BAR_HEIGHT, 0xFF555555);
            int fillWidth = (int) (BAR_WIDTH * healthPct);
            int barColor = healthPct > 0.5f ? 0xFF00AA00 : (healthPct > 0.25f ? 0xFFFFAA00 : 0xFFFF5555);
            if (fillWidth > 0) {
                graphics.fill(barX, barY, barX + fillWidth, barY + BAR_HEIGHT, barColor);
            }

            // XP bar
            int xpBarY = textY + BAR_HEIGHT + 1;
            int xp = companion.getXp();
            int xpToNext = companion.getXpToNext();
            String xpText = "XP: " + xp + "/" + xpToNext;
            graphics.drawString(mc.font, xpText, textX, xpBarY - 1, 0xAAAAAA, false);
            int xpBarX = textX + mc.font.width(xpText) + 4;
            graphics.fill(xpBarX, xpBarY, xpBarX + BAR_WIDTH, xpBarY + XP_BAR_HEIGHT, 0xFF555555);
            int xpFillWidth = xpToNext > 0 ? (int) (BAR_WIDTH * Math.min((float) xp / xpToNext, 1.0f)) : 0;
            if (xpFillWidth > 0) {
                graphics.fill(xpBarX, xpBarY, xpBarX + xpFillWidth, xpBarY + XP_BAR_HEIGHT, 0xFFAA00AA);
            }

            // Line 3: Working mode
            textY += LINE_HEIGHT + XP_BAR_HEIGHT;
            String mode = companion.getWorkingMode();
            String modeDisplay = switch (mode) {
                case "guard" -> "§c🛡 守护";
                case "gather" -> "§6⛏ 采集";
                case "farm" -> "§a🌾 种植";
                case "patrol" -> "§7巡逻";
                default -> "§b👤 跟随";
            };
            graphics.drawString(mc.font, modeDisplay, textX, textY, 0xFFFFFF, false);

            // Line 4: Current action (what is companion targeting)
            textY += LINE_HEIGHT;
            String action = companion.getActionText();
            if (action != null && !action.isEmpty()) {
                graphics.drawString(mc.font, "§7→ " + action, textX, textY, 0xAAAAAA, false);
            } else {
                graphics.drawString(mc.font, "§7→ 待命中", textX, textY, 0x666666, false);
            }

            // Line 5: Distance
            textY += LINE_HEIGHT;
            double dist = Math.sqrt(player.distanceToSqr(companion));
            String distColor = dist > 20 ? "§c" : (dist > 10 ? "§e" : "§a");
            graphics.drawString(mc.font, "§7距离: " + distColor + String.format("%.1f", dist) + "m",
                textX, textY, 0xFFFFFF, false);

            // Line 6: Task queue status (if non-empty)
            if (!companion.getTaskQueue().isEmpty()) {
                textY += LINE_HEIGHT;
                int qSize = companion.getTaskQueue().size();
                graphics.drawString(mc.font, "§7📋 队列: §b" + qSize + "个任务", textX, textY, 0xFFFFFF, false);
            }
        } catch (Exception e) {
            AICompanionMod.LOGGER.error("[HUD] Render error: " + e.getMessage());
        }
    }
}
