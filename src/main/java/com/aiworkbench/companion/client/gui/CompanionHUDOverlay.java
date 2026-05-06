package com.aiworkbench.companion.client.gui;

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
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        if (!hudEnabled) return;

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

        int textX = panelX + PANEL_PADDING;
        int textY = panelY + PANEL_PADDING;

        // Line 1: Companion name + Level
        Component name = companion.getCustomName() != null
            ? companion.getCustomName()
            : Component.literal("Companion");
        int level = companion.getLevel();
        String levelStr = " Lv." + level;
        graphics.drawString(mc.font, name, textX, textY, 0xFFFFFF, true);
        graphics.drawString(mc.font, levelStr, textX + mc.font.width(name), textY,
            0xFFAA00, true); // Gold color for level

        // Line 2: Health bar
        textY += LINE_HEIGHT;
        float health = companion.getHealth();
        float maxHealth = companion.getMaxHealth();
        float healthPct = Math.min(health / maxHealth, 1.0f);

        // Health text
        String healthText = String.format("HP: %.0f/%.0f", health, maxHealth);
        graphics.drawString(mc.font, healthText, textX, textY, 0xFFFFFF, true);

        // Health bar background
        int barX = textX + mc.font.width(healthText) + 4;
        int barY = textY + 3;
        graphics.fill(barX, barY, barX + BAR_WIDTH, barY + BAR_HEIGHT, 0xFF555555);

        // Health bar fill (green > 50%, yellow 25-50%, red < 25%)
        int fillWidth = (int) (BAR_WIDTH * healthPct);
        int barColor;
        if (healthPct > 0.5f) {
            barColor = 0xFF00AA00; // green
        } else if (healthPct > 0.25f) {
            barColor = 0xFFFFAA00; // yellow
        } else {
            barColor = 0xFFFF5555; // red
        }
        if (fillWidth > 0) {
            graphics.fill(barX, barY, barX + fillWidth, barY + BAR_HEIGHT, barColor);
        }

        // XP bar (thin, below health bar)
        int xpBarY = textY + BAR_HEIGHT + 1;
        int xp = companion.getXp();
        int xpToNext = companion.getXpToNext();
        String xpText = "XP: " + xp + "/" + xpToNext;
        graphics.drawString(mc.font, xpText, textX, xpBarY - 1, 0xAAAAAA, true);
        int xpBarX = textX + mc.font.width(xpText) + 4;
        int xpBarY2 = xpBarY;
        graphics.fill(xpBarX, xpBarY2, xpBarX + BAR_WIDTH, xpBarY2 + XP_BAR_HEIGHT, 0xFF555555);
        int xpFillWidth = xpToNext > 0 ? (int) (BAR_WIDTH * Math.min((float) xp / xpToNext, 1.0f)) : 0;
        if (xpFillWidth > 0) {
            graphics.fill(xpBarX, xpBarY2, xpBarX + xpFillWidth, xpBarY2 + XP_BAR_HEIGHT, 0xFFAA00AA); // Purple
        }

        // Line 3: Working mode
        textY += LINE_HEIGHT + XP_BAR_HEIGHT;
        String mode = companion.getWorkingMode();
        String modeDisplay = switch (mode) {
            case "guard" -> "§cGuard";
            case "mine" -> "§bMine";
            case "chop" -> "§6Chop";
            case "patrol" -> "§7Patrol";
            default -> "§aFollow";
        };
        graphics.drawString(mc.font, "Mode: " + modeDisplay, textX, textY, 0xFFFFFF, true);

        // Line 4: Distance
        textY += LINE_HEIGHT;
        double dist = Math.sqrt(player.distanceToSqr(companion));
        String distColor;
        if (dist > 20) {
            distColor = "§c";
        } else if (dist > 10) {
            distColor = "§e";
        } else {
            distColor = "§a";
        }
        graphics.drawString(mc.font, "Dist: " + distColor + String.format("%.1f", dist) + "m",
            textX, textY, 0xFFFFFF, true);
    }
}
