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
    private static final int PANEL_WIDTH = 140;
    private static final int PANEL_PADDING = 6;
    private static final int LINE_HEIGHT = 12;
    private static final int TOP_MARGIN = 10;
    private static final int RIGHT_MARGIN = 10;
    private static final int BAR_WIDTH = 80;
    private static final int BAR_HEIGHT = 6;

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

        // Calculate panel dimensions
        int panelHeight = 4 * LINE_HEIGHT + PANEL_PADDING * 2; // name, health, mode, distance

        // Panel starts from right side
        int panelX = screenWidth - RIGHT_MARGIN - PANEL_WIDTH;
        int panelY = TOP_MARGIN;

        // Draw semi-transparent background
        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, 0x80000000);

        int textX = panelX + PANEL_PADDING;
        int textY = panelY + PANEL_PADDING;

        // Line 1: Companion name
        Component name = companion.getCustomName() != null
            ? companion.getCustomName()
            : Component.literal("Companion");
        graphics.drawString(mc.font, name, textX, textY, 0xFFFFFF, true);

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

        // Line 3: Working mode
        textY += LINE_HEIGHT;
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
