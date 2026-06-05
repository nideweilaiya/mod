package com.aiworkbench.companion.client.gui;

import com.aiworkbench.companion.client.CompanionClientState;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 资源采集详情页面 — 从 G 面板跳转而来，显示采集状态、统计、过滤器和优先采集设置。
 * <p>
 * 操作按钮通过 sendCommand 发送到服务端执行。
 */
public class GatheringDetailScreen extends Screen {
    private final Screen parent;
    private String statusMessage = "";

    public GatheringDetailScreen(Screen parent) {
        super(Component.literal("资源采集"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        AutomatonEntity companion = CompanionClientState.getCompanion();
        int cx = this.width / 2;

        // ===== 标题 =====
        int y = 20;
        addRenderableWidget(Button.builder(Component.literal("§6§l⛏ 资源采集控制"), btn -> {})
                .bounds(cx - 80, y, 160, 18).build());

        // ===== 状态显示 =====
        y += 24;
        boolean gathering = companion != null && companion.isGatherModeEnabled();
        String filter = companion != null ? companion.getGatherFilter() : "all";
        String filterDesc = switch (filter) {
            case "ores" -> "仅矿石";
            case "wood" -> "仅木材";
            default -> "全部资源";
        };

        addRenderableWidget(Button.builder(
                Component.literal(gathering ? "§a● 采集中" : "§7● 空闲"), btn -> {})
                .bounds(cx - 120, y, 80, 18).build());
        addRenderableWidget(Button.builder(
                Component.literal("§7过滤: §e" + filterDesc), btn -> {})
                .bounds(cx - 30, y, 80, 18).build());

        // ===== 过滤模式按钮 =====
        y += 28;
        addRenderableWidget(Button.builder(Component.literal("§7全部"), btn -> {
            sendCmd("companion gather all");
            statusMessage = "§e过滤: 全部资源";
            init();
        }).bounds(cx - 120, y, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("§b仅矿石"), btn -> {
            sendCmd("companion gather ores");
            statusMessage = "§e过滤: 仅矿石";
            init();
        }).bounds(cx - 60, y, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("§6仅木材"), btn -> {
            sendCmd("companion gather wood");
            statusMessage = "§e过滤: 仅木材";
            init();
        }).bounds(cx, y, 55, 20).build());

        // ===== 优先采集 =====
        y += 28;
        addRenderableWidget(Button.builder(Component.literal("§e优先采集:"), btn -> {})
                .bounds(cx - 120, y, 70, 16).build());

        y += 20;
        addPriorityButton("§b钻石", "diamond", cx - 120, y, 50);
        addPriorityButton("§a绿宝石", "emerald", cx - 66, y, 50);
        addPriorityButton("§e金", "gold", cx - 12, y, 35);
        addPriorityButton("§7铁", "iron", cx + 27, y, 35);
        addPriorityButton("§8煤", "coal", cx + 66, y, 35);

        y += 22;
        addPriorityButton("§4红石", "redstone", cx - 120, y, 50);
        addPriorityButton("§9青金石", "lapis", cx - 66, y, 50);
        addPriorityButton("§6铜", "copper", cx - 12, y, 35);
        addPriorityButton("§6原木", "log", cx + 27, y, 55);

        // ===== 开始/停止 =====
        y += 32;
        addRenderableWidget(Button.builder(
                Component.literal(gathering ? "§c■ 停止采集" : "§a▶ 开始采集"), btn -> {
            sendCmd("companion gather");
            statusMessage = gathering ? "§7采集已停止" : "§a开始采集...";
            init();
        }).bounds(cx - 50, y, 100, 22).build());

        // ===== 统计信息 =====
        y += 30;
        addRenderableWidget(Button.builder(
                Component.literal("§7提示：按V键可快速切换采集模式"), btn -> {})
                .bounds(cx - 110, y, 220, 16).build());

        // ===== 状态消息 =====
        if (!statusMessage.isEmpty()) {
            y += 22;
            addRenderableWidget(Button.builder(Component.literal(statusMessage), btn -> {
                statusMessage = "";
                init();
            }).bounds(cx - 80, y, 160, 16).build());
        }

        // ===== 返回按钮 =====
        y += 30;
        addRenderableWidget(Button.builder(Component.literal("§7← 返回"), btn -> onClose())
                .bounds(cx - 30, y, 60, 20).build());
    }

    private void addPriorityButton(String label, String resource, int x, int y, int width) {
        addRenderableWidget(Button.builder(Component.literal(label), btn -> {
            sendCmd("companion gather priority " + resource);
            statusMessage = "§e优先采集: " + resource;
            init();
        }).bounds(x, y, width, 18).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xC01a1a2e);
        graphics.fill(0, 0, this.width, this.height / 2, 0xC016213e);
        super.render(graphics, mouseX, mouseY, partialTick);

        // 标题文字
        graphics.drawCenteredString(this.font, "⛏ 智能资源采集", this.width / 2, 8, 0xFFFFFF);
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

    private void sendCmd(String cmd) {
        if (this.minecraft != null && this.minecraft.getConnection() != null) {
            this.minecraft.getConnection().sendCommand(cmd);
        }
    }
}
