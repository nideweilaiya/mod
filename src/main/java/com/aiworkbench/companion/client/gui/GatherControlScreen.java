package com.aiworkbench.companion.client.gui;

import com.aiworkbench.companion.client.CompanionClientState;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Set;

/**
 * 采集快捷控制面板 — 右键同伴→采集设置 打开
 *
 * 无需记忆命令，按钮一键切换采集类型/目标/优先级。
 */
public class GatherControlScreen extends Screen {
    private final Screen parent;

    public GatherControlScreen(Screen parent) {
        super(Component.literal("采集设置"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        int cx = this.width / 2;
        AutomatonEntity c = CompanionClientState.getCompanion();

        String filter = c != null ? c.getGatherFilter() : "all";
        String target = c != null ? c.getGatherTargetBlock() : "";
        Set<String> priorities = c != null ? c.getGatherPriorityResources() : Set.of();
        boolean gathering = c != null && c.isGatherModeEnabled();

        int btnW = 140, btnH = 18, wideW = 200;
        int gap = 2, sectionGap = 6;
        int y = Math.max(this.height / 2 - 80, 15);

        // 标题
        addRenderableWidget(Button.builder(Component.literal("§6§l⛏ 采集设置"), btn -> {})
            .bounds(cx - 55, y, 110, 14).build());
        y += 16;

        // ── 采集开关 ──
        addRenderableWidget(Button.builder(
            Component.literal(gathering ? "§a⛏ 采集中（点击关闭）" : "§7⛏ 开始采集"),
            btn -> { sendCmd("companion gather"); this.init(); }
        ).bounds(cx - wideW/2, y, wideW, btnH).build());
        y += btnH + sectionGap;

        // ── 类型过滤 ──
        addRenderableWidget(Button.builder(Component.literal("§7───── 类型过滤 ─────"), btn -> {})
            .bounds(cx - 65, y, 130, 12).build());
        y += 14;

        // 三个过滤按钮并排
        int rowY = y;
        addRenderableWidget(Button.builder(
            Component.literal("all".equals(filter) ? "§a■ 全部" : "§7□ 全部"),
            btn -> { sendCmd("companion gather all"); this.init(); }
        ).bounds(cx - 105, rowY, 65, btnH).build());

        addRenderableWidget(Button.builder(
            Component.literal("ores".equals(filter) ? "§6■ 矿石" : "§7□ 矿石"),
            btn -> { sendCmd("companion gather ores"); this.init(); }
        ).bounds(cx - 35, rowY, 65, btnH).build());

        addRenderableWidget(Button.builder(
            Component.literal("wood".equals(filter) ? "§2■ 木材" : "§7□ 木材"),
            btn -> { sendCmd("companion gather wood"); this.init(); }
        ).bounds(cx + 35, rowY, 65, btnH).build());
        y += btnH + sectionGap;

        // ── 优先采集 ──
        addRenderableWidget(Button.builder(Component.literal("§7───── 优先采集 ─────"), btn -> {})
            .bounds(cx - 65, y, 130, 12).build());
        y += 14;

        // 预设优先级快捷按钮
        addRenderableWidget(Button.builder(
            Component.literal("§b💎 钻石/残骸优先"),
            btn -> { sendCmd("companion gather priority diamond,ancient_debris"); this.init(); }
        ).bounds(cx - wideW/2, y, wideW, btnH).build());
        y += btnH + gap;

        addRenderableWidget(Button.builder(
            Component.literal("§6🔩 铁/金/铜优先"),
            btn -> { sendCmd("companion gather priority iron,gold,copper"); this.init(); }
        ).bounds(cx - wideW/2, y, wideW, btnH).build());
        y += btnH + gap;

        addRenderableWidget(Button.builder(
            Component.literal("§7🪨 煤/石优先"),
            btn -> { sendCmd("companion gather priority coal,stone"); this.init(); }
        ).bounds(cx - wideW/2, y, wideW, btnH).build());
        y += btnH + gap;

        // 清除优先级
        addRenderableWidget(Button.builder(
            Component.literal("§c✕ 清除优先级"),
            btn -> { sendCmd("companion gather priority "); this.init(); }
        ).bounds(cx - wideW/2, y, wideW, btnH).build());
        y += btnH + sectionGap;

        // ── 指定目标 ──
        addRenderableWidget(Button.builder(Component.literal("§7───── 指定目标 ─────"), btn -> {})
            .bounds(cx - 65, y, 130, 12).build());
        y += 14;

        boolean hasTarget = target != null && !target.isEmpty();
        String targetDisplay = hasTarget ? "§e🎯 目标: " + target.replace("minecraft:", "") : "§7未指定（采集全部）";
        addRenderableWidget(Button.builder(Component.literal(targetDisplay), btn -> {})
            .bounds(cx - wideW/2, y, wideW, btnH).build());
        y += btnH + gap;

        if (hasTarget) {
            addRenderableWidget(Button.builder(
                Component.literal("§c✕ 清除目标"),
                btn -> { sendCmd("companion gather target "); this.init(); }
            ).bounds(cx - wideW/2, y, wideW, btnH).build());
            y += btnH + gap;
        }

        // 快捷目标按钮
        int halfW = (wideW - gap) / 2;
        addRenderableWidget(Button.builder(
            Component.literal("§b💎 钻石矿"),
            btn -> { sendCmd("companion gather target minecraft:diamond_ore"); this.init(); }
        ).bounds(cx - wideW/2, y, halfW, btnH).build());

        addRenderableWidget(Button.builder(
            Component.literal("§6🔩 铁矿"),
            btn -> { sendCmd("companion gather target minecraft:iron_ore"); this.init(); }
        ).bounds(cx - wideW/2 + halfW + gap, y, halfW, btnH).build());
        y += btnH + gap;

        addRenderableWidget(Button.builder(
            Component.literal("§e✨ 远古残骸"),
            btn -> { sendCmd("companion gather target minecraft:ancient_debris"); this.init(); }
        ).bounds(cx - wideW/2, y, halfW, btnH).build());

        addRenderableWidget(Button.builder(
            Component.literal("§2🌲 橡木"),
            btn -> { sendCmd("companion gather target minecraft:oak_log"); this.init(); }
        ).bounds(cx - wideW/2 + halfW + gap, y, halfW, btnH).build());
        y += btnH + sectionGap + 4;

        // 优先级状态
        if (!priorities.isEmpty()) {
            addRenderableWidget(Button.builder(
                Component.literal("§e⭐ 当前优先: " + String.join(", ", priorities)), btn -> {})
                .bounds(cx - wideW/2, y, wideW, btnH).build());
            y += btnH + gap;
        }

        // 返回
        addRenderableWidget(Button.builder(Component.literal("§7↩ 返回"), btn -> onClose())
            .bounds(cx - 35, y + 2, 70, 16).build());
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
