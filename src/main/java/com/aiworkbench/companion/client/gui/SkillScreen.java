package com.aiworkbench.companion.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill Screen — 技能库图形界面
 * <p>
 * 显示所有预制技能，支持学习/执行/取消操作。
 * 使用聊天命令与服务器通信（沿用现有模式）。
 */
public class SkillScreen extends Screen {

    private final Screen parent;

    /** 客户端侧技能元数据（与 PresetSkillRegistry 同步） */
    private static final List<SkillEntry> SKILL_ENTRIES = List.of(
        new SkillEntry("collectWood",  "收集木材", "交互", "自动寻找并砍伐周围的树木", 3),
        new SkillEntry("mineStone",    "挖掘石头", "交互", "自动寻找并挖掘石头", 3),
        new SkillEntry("mineCoalOre",  "挖掘煤矿", "交互", "自动寻找并挖掘煤矿", 3),
        new SkillEntry("mineIronOre",  "挖掘铁矿", "交互", "自动寻找并挖掘铁矿", 3),
        new SkillEntry("fightZombie",  "战斗僵尸", "战斗", "自动攻击附近的僵尸", 1),
        new SkillEntry("collectDrops", "收集掉落", "交互", "拾取周围所有掉落物品", 1),
        new SkillEntry("craftStick",         "合成木棍", "合成", "消耗木板合成木棍", 1),
        new SkillEntry("craftWoodenPickaxe", "合成木镐", "合成", "消耗木板和木棍合成木镐", 1),
        new SkillEntry("craftFurnace",       "合成熔炉", "合成", "消耗圆石合成熔炉", 1),
        new SkillEntry("buildShelter",       "建造小屋", "交互", "用圆石建造3×3庇护所", 1),
        new SkillEntry("smeltIronIngot",     "冶炼铁锭", "交互", "自动熔炉将铁矿石→铁锭", 1),
        new SkillEntry("lookAtOwner",  "看向主人", "移动", "看向主人 2 秒（测试技能引擎）", 1),
        new SkillEntry("moveForward",  "向前移动", "移动", "向前移动 5 格（测试技能引擎）", 1)
    );

    private static final int ENTRY_HEIGHT = 32;
    private static final int LIST_WIDTH = 200;
    private static final int DETAIL_LEFT = 230;

    // 滚动
    private double scrollOffset = 0;
    private int viewHeight;

    // 选中技能
    private int selectedIndex = 0;

    // 从服务器查询的状态（延迟更新）
    private String statusText = "§7查询中...";
    private String currentSkillName = "";

    // 标记首次是否已发送查询
    private boolean queriedStatus = false;

    public SkillScreen(Screen parent) {
        super(Component.literal("技能库"));
        this.parent = parent;
    }

    public SkillScreen() {
        this(null);
    }

    // ==================== 技能元数据 ====================

    public record SkillEntry(String id, String title, String category, String description, int steps) {}

    // ==================== 初始化 ====================

    @Override
    protected void init() {
        clearWidgets();
        viewHeight = this.height - 60;

        int cx = this.width / 2;
        int listLeft = cx - 160;

        // 如果没有查询过状态，发命令查询
        if (!queriedStatus) {
            querySkillStatus();
            queriedStatus = true;
        }

        // === 顶部：标题 + 状态栏 ===
        int topY = 15;
        addRenderableWidget(Button.builder(
            Component.literal("§6§l⚡ 技能库"),
            btn -> {}).bounds(cx - 140, topY, 120, 16).build());

        // 当前技能执行状态（由服务器查询更新）
        String statusDisplay = currentSkillName.isEmpty()
            ? "§7状态: 空闲"
            : "§e▶ 执行中: " + currentSkillName;
        addRenderableWidget(Button.builder(
            Component.literal(statusDisplay),
            btn -> {}).bounds(cx - 20, topY, 160, 16).build());

        // 关闭按钮
        addRenderableWidget(Button.builder(
            Component.literal("§c✕"),
            btn -> onClose())
            .bounds(this.width - 40, 10, 30, 20).build());

        // 刷新按钮
        addRenderableWidget(Button.builder(
            Component.literal("§e↻"),
            btn -> {
                querySkillStatus();
                init();
            }).bounds(this.width - 75, 10, 30, 20).build());

        // === 技能列表（左侧） ===
        int listY = 48 - (int) scrollOffset;

        for (int i = 0; i < SKILL_ENTRIES.size(); i++) {
            SkillEntry entry = SKILL_ENTRIES.get(i);
            int y = listY + i * ENTRY_HEIGHT;
            if (y + ENTRY_HEIGHT < 40 || y > this.height - 20) continue; // 裁剪

            boolean selected = (i == selectedIndex);
            String bgColor = selected ? "§e" : "§7";
            String prefix = currentSkillName.equals(entry.id()) ? "§e▶ " : "  ";

            addRenderableWidget(Button.builder(
                Component.literal(prefix + bgColor + entry.title()),
                btn -> {
                    selectedIndex = SKILL_ENTRIES.indexOf(entry);
                    init();
                })
                .bounds(listLeft, y, LIST_WIDTH, 28).build());
        }

        // === 技能详情（右侧） ===
        if (selectedIndex >= 0 && selectedIndex < SKILL_ENTRIES.size()) {
            SkillEntry entry = SKILL_ENTRIES.get(selectedIndex);
            int detailY = 48;

            addRenderableWidget(Button.builder(
                Component.literal("§f§l" + entry.title()),
                btn -> {}).bounds(listLeft + DETAIL_LEFT, detailY, 160, 16).build());

            addRenderableWidget(Button.builder(
                Component.literal("§7ID: §f" + entry.id()),
                btn -> {}).bounds(listLeft + DETAIL_LEFT, detailY + 18, 160, 14).build());

            addRenderableWidget(Button.builder(
                Component.literal("§7类别: §b" + entry.category()),
                btn -> {}).bounds(listLeft + DETAIL_LEFT, detailY + 34, 160, 14).build());

            addRenderableWidget(Button.builder(
                Component.literal("§7步骤: §f" + entry.steps()),
                btn -> {}).bounds(listLeft + DETAIL_LEFT, detailY + 50, 160, 14).build());

            // 描述（可能较长，截断显示）
            String desc = entry.description();
            if (desc.length() > 28) desc = desc.substring(0, 26) + "..";
            addRenderableWidget(Button.builder(
                Component.literal("§7描述: §f" + desc),
                btn -> {}).bounds(listLeft + DETAIL_LEFT, detailY + 66, 200, 14).build());

            // === 操作按钮 ===
            int actionY = detailY + 90;

            // 学习并执行
            addRenderableWidget(Button.builder(
                Component.literal("§a▶ 学习并执行"),
                btn -> sendCmd("companion skill learn " + entry.id()))
                .bounds(listLeft + DETAIL_LEFT, actionY, 90, 20).build());

            // 查看详情
            addRenderableWidget(Button.builder(
                Component.literal("§bℹ 详情"),
                btn -> sendCmd("companion skill info " + entry.id()))
                .bounds(listLeft + DETAIL_LEFT + 95, actionY, 60, 20).build());
        }

        // === 底部操作栏 ===
        int bottomY = this.height - 40;

        addRenderableWidget(Button.builder(
            Component.literal("§c⏹ 取消当前技能"),
            btn -> sendCmd("companion skill cancel"))
            .bounds(cx - 80, bottomY, 90, 20).build());

        addRenderableWidget(Button.builder(
            Component.literal("§e📋 列出已学"),
            btn -> sendCmd("companion skill list"))
            .bounds(cx + 15, bottomY, 80, 20).build());

        // 状态信息
        if (!statusText.equals("§7查询中...")) {
            addRenderableWidget(Button.builder(
                Component.literal(statusText),
                btn -> {}).bounds(cx - 80, bottomY + 24, 200, 14).build());
        }

        // LLM 技能生成提示
        addRenderableWidget(Button.builder(
            Component.literal("§7💡 输入 §f/companion skill generate <描述> §7让 AI 生成新技能"),
            btn -> {}).bounds(cx - 140, bottomY + 42, 300, 12).build());
    }

    // ==================== 服务器通信 ====================

    /**
     * 查询当前技能执行状态。
     * 发送 /companion status 并解析返回的聊天消息。
     */
    private void querySkillStatus() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;

        // 因为无法直接解析聊天消息，改为发送一个带标记的命令
        mc.getConnection().sendCommand("companion skill list");
        // 重置状态文本，由 CompanionNetworkHandler 更新
        statusText = "§7已发送查询";
    }

    /**
     * 从外部更新当前执行中的技能名（由 CompanionNetworkHandler 调用）。
     */
    public void setCurrentSkill(String skillName) {
        this.currentSkillName = skillName != null ? skillName : "";
        if (this.currentSkillName.isEmpty()) {
            this.statusText = "§7空闲中";
        } else {
            this.statusText = "§e执行技能: " + this.currentSkillName;
        }
    }

    /**
     * 更新状态文本（由 CompanionNetworkHandler 调用）。
     */
    public void setStatusText(String text) {
        this.statusText = text;
    }

    private void sendCmd(String cmd) {
        if (this.minecraft != null && this.minecraft.getConnection() != null) {
            this.minecraft.getConnection().sendCommand(cmd);
        }
        // 关闭屏幕让玩家看到命令结果
        onClose();
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 渐变背景
        graphics.fill(0, 0, this.width, this.height / 2, 0xC0_0f1729);
        graphics.fill(0, this.height / 2, this.width, this.height, 0xC0_1e293b);

        super.render(graphics, mouseX, mouseY, partialTick);

        // 分割线（列表与详情之间）
        int cx = this.width / 2;
        int lineX = cx - 160 + LIST_WIDTH + 15;
        graphics.fill(lineX, 45, lineX + 1, this.height - 50, 0x40_64748b);

        // 列表标题
        int listLeft = cx - 160;
        graphics.drawString(this.font, "§7可用技能", listLeft, 40, 0x94A3B8, false);

        // 详情标题
        graphics.drawString(this.font, "§7技能详情", listLeft + DETAIL_LEFT, 40, 0x94A3B8, false);

        // 滚动条
        int totalContent = SKILL_ENTRIES.size() * ENTRY_HEIGHT;
        if (totalContent > viewHeight) {
            int sx = cx - 160 + LIST_WIDTH + 8;
            int maxScroll = Math.max(1, totalContent - viewHeight);
            int thumbH = Math.max(30, viewHeight * viewHeight / totalContent);
            int thumbY = (int) (scrollOffset / maxScroll * (viewHeight - thumbH)) + 45;
            graphics.fill(sx, 45, sx + 4, 45 + viewHeight, 0x40_334155);
            graphics.fill(sx, thumbY, sx + 4, thumbY + thumbH, 0xFF_10B981);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int totalContent = SKILL_ENTRIES.size() * ENTRY_HEIGHT;
        int maxScroll = Math.max(0, totalContent - viewHeight);
        scrollOffset -= verticalAmount * 20;
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        init();
        return true;
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
