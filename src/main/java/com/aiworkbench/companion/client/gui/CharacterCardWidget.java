package com.aiworkbench.companion.client.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Character Card Widget - 角色卡片按钮
 * 点击后进入角色详情
 */
public class CharacterCardWidget {

    public final Button button;
    private final String characterName;
    private final String description;
    private final boolean isOnline;
    private final int cardWidth = 100;
    private final int cardHeight = 130;

    public CharacterCardWidget(int x, int y, String name, String desc, boolean online, Runnable onClick) {
        this.characterName = name;
        this.description = desc;
        this.isOnline = online;

        Component buttonText = Component.literal(buildCardText());
        this.button = Button.builder(buttonText, btn -> onClick.run())
                .bounds(x, y, cardWidth, cardHeight)
                .build();
    }

    private String buildCardText() {
        StringBuilder sb = new StringBuilder();
        sb.append(isOnline ? "§a● " : "§7○ ");
        sb.append(truncate(characterName, 10));
        sb.append("\n§7");
        sb.append(isOnline ? "在线" : "离线");
        if (description != null && !description.isEmpty()) {
            sb.append("\n§8");
            sb.append(truncate(description, 15));
        }
        return sb.toString();
    }

    public int getX() { return button.getX(); }
    public int getY() { return button.getY(); }
    public int getWidth() { return cardWidth; }
    public int getHeight() { return cardHeight; }
    public String getCharacterName() { return characterName; }
    public boolean isOnline() { return isOnline; }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 2) + "..";
    }
}
