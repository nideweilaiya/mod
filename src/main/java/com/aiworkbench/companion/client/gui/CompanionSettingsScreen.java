package com.aiworkbench.companion.client.gui;

import com.aiworkbench.companion.CompanionConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.awt.Desktop;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Companion Settings GUI - Scrollable with mouse wheel support
 */
public class CompanionSettingsScreen extends Screen {
    private final Screen parent;
    private List<String> availableSkins = new ArrayList<>();
    private int selectedSkinIndex = 0;
    private String currentModel = "llama3.2:latest";
    private String statusMessage = "选择选项后点击应用按钮";

    // Scroll offset for mouse wheel scrolling
    private double scrollOffset = 0;
    private int totalContentHeight = 340; // Will be calculated based on content
    private int viewHeight; // Calculated dynamically based on screen size

    private static final String[] AI_MODELS = {
        "llama3.2:latest",
        "llama3.1:8b",
        "qwen3.5:4b"
    };

    public CompanionSettingsScreen(Screen parent) {
        super(Component.literal("同伴设置"));
        this.parent = parent;
        if (Minecraft.getInstance().player != null) {
            currentModel = CompanionConfig.getModel(Minecraft.getInstance().player.getUUID());
        }
    }

    @Override
    protected void init() {
        clearWidgets();

        // Calculate view height dynamically - leave 80px at bottom for close button
        viewHeight = this.height - 80;
        totalContentHeight = 420;

        int centerX = this.width / 2;

        // Title
        addRenderableWidget(Button.builder(Component.literal("§b§l同伴设置面板"), btn -> {})
            .bounds(centerX - 60, 15 - (int) scrollOffset, 120, 20).build());

        // ===== 皮肤 Section =====
        int skinY = 50 - (int) scrollOffset;

        addRenderableWidget(Button.builder(Component.literal("§e皮肤选择"), btn -> {})
            .bounds(centerX - 80, skinY, 160, 16).build());

        loadAvailableSkins();

        addRenderableWidget(Button.builder(Component.literal("§a<"), btn -> {
            if (!availableSkins.isEmpty()) {
                selectedSkinIndex = (selectedSkinIndex - 1 + availableSkins.size()) % availableSkins.size();
                init(); // Rebuild to update display
            }
        }).bounds(centerX - 100, skinY + 22, 30, 16).build());

        String skinName = availableSkins.isEmpty() ? "无" : availableSkins.get(selectedSkinIndex);
        addRenderableWidget(Button.builder(Component.literal("§f" + skinName), btn -> {})
            .bounds(centerX - 65, skinY + 22, 80, 16).build());

        addRenderableWidget(Button.builder(Component.literal(">"), btn -> {
            if (!availableSkins.isEmpty()) {
                selectedSkinIndex = (selectedSkinIndex + 1) % availableSkins.size();
                init(); // Rebuild to update display
            }
        }).bounds(centerX + 20, skinY + 22, 30, 16).build());

        addRenderableWidget(Button.builder(Component.literal("§b应用皮肤"), btn -> {
            if (!availableSkins.isEmpty()) {
                String skin = availableSkins.get(selectedSkinIndex);
                Minecraft mc = Minecraft.getInstance();
                if (mc.getConnection() != null) {
                    mc.getConnection().sendCommand("companion skin " + skin);
                    statusMessage = "§a已应用皮肤: " + skin;
                } else {
                    statusMessage = "§c无法执行命令";
                }
            } else {
                statusMessage = "§c没有可用皮肤";
            }
        }).bounds(centerX - 80, skinY + 44, 100, 18).build());

        addRenderableWidget(Button.builder(Component.literal("§e打开皮肤文件夹"), btn -> {
            Minecraft mc = Minecraft.getInstance();
            File skinsDir = new File(mc.gameDirectory.getPath(), "aicompanion/skins");
            skinsDir.mkdirs();
            try {
                Desktop.getDesktop().open(skinsDir);
            } catch (Exception e) {
                mc.player.displayClientMessage(Component.literal("§c无法打开文件夹"), false);
            }
        }).bounds(centerX + 30, skinY + 44, 100, 18).build());

        // ===== AI模型 Section =====
        int modelY = skinY + 80;

        addRenderableWidget(Button.builder(Component.literal("§eAI模型选择"), btn -> {})
            .bounds(centerX - 80, modelY, 160, 16).build());

        addRenderableWidget(Button.builder(Component.literal("§a<"), btn -> {
            int currentIdx = getCurrentModelIndex();
            currentIdx = (currentIdx - 1 + AI_MODELS.length) % AI_MODELS.length;
            currentModel = AI_MODELS[currentIdx];
            init(); // Rebuild to update display
        }).bounds(centerX - 100, modelY + 22, 30, 16).build());

        String displayModel = currentModel.length() > 12 ? currentModel.substring(0, 10) + ".." : currentModel;
        addRenderableWidget(Button.builder(Component.literal("§f" + displayModel), btn -> {})
            .bounds(centerX - 65, modelY + 22, 80, 16).build());

        addRenderableWidget(Button.builder(Component.literal(">"), btn -> {
            int currentIdx = getCurrentModelIndex();
            currentIdx = (currentIdx + 1) % AI_MODELS.length;
            currentModel = AI_MODELS[currentIdx];
            init(); // Rebuild to update display
        }).bounds(centerX + 20, modelY + 22, 30, 16).build());

        addRenderableWidget(Button.builder(Component.literal("§d应用模型"), btn -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                mc.getConnection().sendCommand("companion model " + currentModel);
                statusMessage = "§a已设置模型: " + currentModel;
            } else {
                statusMessage = "§c无法执行命令";
            }
        }).bounds(centerX - 80, modelY + 44, 100, 18).build());

        // ===== 命令快捷按钮 =====
        int cmdY = modelY + 85;

        addRenderableWidget(Button.builder(Component.literal("§6----- 快捷命令 -----"), btn -> {})
            .bounds(centerX - 80, cmdY, 160, 16).build());

        addRenderableWidget(Button.builder(Component.literal("§e① 和同伴对话"), btn -> {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new ChatScreen("/companion chat "));
        }).bounds(centerX - 100, cmdY + 20, 200, 14).build());

        addRenderableWidget(Button.builder(Component.literal("§e② 查看皮肤列表"), btn -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                mc.getConnection().sendCommand("companion list");
            }
        }).bounds(centerX - 100, cmdY + 36, 200, 14).build());

        addRenderableWidget(Button.builder(Component.literal("§e③ 恢复默认皮肤"), btn -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                mc.getConnection().sendCommand("companion default");
            }
        }).bounds(centerX - 100, cmdY + 52, 200, 14).build());

        addRenderableWidget(Button.builder(Component.literal("§e④ 查看当前模型"), btn -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                mc.getConnection().sendCommand("companion model");
            }
        }).bounds(centerX - 100, cmdY + 68, 200, 14).build());

        // ===== 模式切换 Section =====
        int modeY = cmdY + 95;

        addRenderableWidget(Button.builder(Component.literal("§6----- 伙伴模式 -----"), btn -> {})
            .bounds(centerX - 80, modeY, 160, 16).build());

        addRenderableWidget(Button.builder(Component.literal("§c⚔ 守护模式"), btn -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                mc.getConnection().sendCommand("companion guard");
                statusMessage = "§a已切换守护模式";
            }
        }).bounds(centerX - 100, modeY + 20, 95, 20).build());

        addRenderableWidget(Button.builder(Component.literal("§b⛏ 挖掘模式"), btn -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                mc.getConnection().sendCommand("companion mine");
                statusMessage = "§a已切换挖掘模式";
            }
        }).bounds(centerX + 5, modeY + 20, 95, 20).build());

        addRenderableWidget(Button.builder(Component.literal("§6🪓 砍伐模式"), btn -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                mc.getConnection().sendCommand("companion chop");
                statusMessage = "§a已切换砍伐模式";
            }
        }).bounds(centerX - 100, modeY + 44, 95, 20).build());

        addRenderableWidget(Button.builder(Component.literal("§e🎒 打开背包"), btn -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                mc.getConnection().sendCommand("companion inventory");
                statusMessage = "§a已打开背包";
            }
        }).bounds(centerX + 5, modeY + 44, 95, 20).build());

        addRenderableWidget(Button.builder(Component.literal(statusMessage), btn -> {})
            .bounds(centerX - 90, cmdY + 86, 200, 16).build());

        addRenderableWidget(Button.builder(Component.literal("§c关闭"), btn -> {
            this.onClose();
        }).bounds(centerX - 30, modeY + 80, 60, 18).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Scroll with mouse wheel
        int scrollStep = 20;
        int maxScroll = Math.max(0, totalContentHeight - viewHeight);

        scrollOffset -= verticalAmount * scrollStep;
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        // Rebuild widgets with new scroll position
        init();
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Gradient background
        graphics.fill(0, 0, this.width, this.height, 0xC0_1a1a2e);
        graphics.fill(0, 0, this.width, this.height / 2, 0xC0_16213e);

        super.render(graphics, mouseX, mouseY, partialTick);

        // Draw scroll hint if content is scrollable
        if (totalContentHeight > viewHeight) {
            int scrollBarX = this.width - 15;
            int scrollBarHeight = 60;
            int maxScroll = Math.max(1, totalContentHeight - viewHeight);
            int thumbY = (int) (scrollOffset / maxScroll * (viewHeight - scrollBarHeight)) + 50;

            graphics.fill(scrollBarX, 50, scrollBarX + 6, 50 + viewHeight, 0x80_334155);
            graphics.fill(scrollBarX, thumbY, scrollBarX + 6, thumbY + scrollBarHeight, 0xFF_10B981);
        }
    }

    private void loadAvailableSkins() {
        availableSkins.clear();
        File skinsDir = new File(getSkinsPath());
        if (skinsDir.exists() && skinsDir.isDirectory()) {
            File[] files = skinsDir.listFiles((dir, name) -> name.endsWith(".png"));
            if (files != null) {
                for (File f : files) {
                    availableSkins.add(f.getName().replace(".png", ""));
                }
            }
        }
        if (availableSkins.isEmpty()) {
            availableSkins.add("default");
        }
    }

    private String getSkinsPath() {
        return Minecraft.getInstance().gameDirectory.getPath() + "/aicompanion/skins";
    }

    private int getCurrentModelIndex() {
        for (int i = 0; i < AI_MODELS.length; i++) {
            if (AI_MODELS[i].equals(currentModel)) return i;
        }
        return 0;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}