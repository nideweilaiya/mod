package com.aiworkbench.companion.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Companion Inventory Screen - 真正的背包GUI
 * 同伴背包27格显示在屏幕中央
 * 左键：拿取整格 | 右键：拿取1个
 */
public class CompanionInventoryScreen extends Screen {
    private static final int SLOT_SIZE = 18;
    private static final int SLOT_SPACING = 4;
    private static final int COMPANION_COLS = 9;
    private static final int COMPANION_ROWS = 3;
    private static final int COMPANION_SLOTS = COMPANION_COLS * COMPANION_ROWS; // 27 存储槽
    private static final int EQUIPMENT_SLOTS = 6; // mainhand, offhand, feet, legs, chest, head
    private static final int EQUIPMENT_ROW_HEIGHT = 28;
    private static final int TOTAL_SLOTS = COMPANION_SLOTS + EQUIPMENT_SLOTS; // 33

    private static final int GUI_WIDTH = COMPANION_COLS * (SLOT_SIZE + SLOT_SPACING) - SLOT_SPACING + 20;
    private static final int GUI_HEIGHT = COMPANION_ROWS * (SLOT_SIZE + SLOT_SPACING) - SLOT_SPACING + 60 + EQUIPMENT_ROW_HEIGHT;
    private static final String[] EQUIP_NAMES = {"MainHand", "OffHand", "Feet", "Legs", "Chest", "Head"};
    private static final String[] EQUIP_CMDS = {"mainhand", "offhand", "feet", "legs", "chest", "head"};

    private static CompanionInventoryScreen INSTANCE;

    public static CompanionInventoryScreen instance() {
        return INSTANCE;
    }

    private int guiLeft;
    private int guiTop;

    // 物品数据（27 存储槽 + 6 设备槽）
    private NonNullList<ItemStack> companionItems = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY);

    // 玩家手中物品
    private ItemStack heldItem = ItemStack.EMPTY;

    // 热点
    private int hoveredSlot = -1;

    // 首次渲染标志
    private boolean needsInventorySync = true;

    // 右键拿取计数
    private int rightClickTimer = 0;
    private static final int RIGHT_CLICK_DELAY = 10; // ticks before auto-repeat

    public CompanionInventoryScreen() {
        super(Component.literal("Companion Backpack"));
        INSTANCE = this;
    }

    public static boolean isOpen() {
        return INSTANCE != null && Minecraft.getInstance().screen == INSTANCE;
    }

    public static void updateInventory(NonNullList<ItemStack> items) {
        if (INSTANCE != null) {
            INSTANCE.companionItems = items;
            INSTANCE.init();
        }
    }

    /**
     * Update inventory data from network packet (called by CompanionInventorySyncPacket)
     */
    public void updateInventoryData(NonNullList<ItemStack> items) {
        this.companionItems = items;
        // Trigger re-render by marking dirty - the render method will use updated data
    }

    @Override
    protected void init() {
        super.init();
        INSTANCE = this;

        // 计算居中位置
        guiLeft = (this.width - GUI_WIDTH) / 2;
        guiTop = (this.height - GUI_HEIGHT) / 2;

        clearWidgets();

        // 关闭按钮
        addRenderableWidget(Button.builder(
                Component.literal("X"),
                btn -> onClose())
                .bounds(guiLeft + GUI_WIDTH - 25, guiTop + 5, 20, 20)
                .build());

        // 取出全部按钮
        addRenderableWidget(Button.builder(
                Component.literal("Take All"),
                btn -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.getConnection() != null) {
                        mc.getConnection().sendCommand("companion giveall");
                    }
                    onClose();
                })
                .bounds(guiLeft + 10, guiTop + 5, 60, 18)
                .build());

        // 不要在这里发送请求！改为在 render 时延迟发送
        // 这样可以确保 init 完成后再开始渲染
    }

    private void requestInventorySync() {
        // 延迟发送请求，确保渲染已完成初始化
        // 使用 Minecraft 的运行队列来延迟执行
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            // 使用 execute 方法延迟到下一tick执行
            mc.execute(() -> {
                if (Minecraft.getInstance().getConnection() != null) {
                    Minecraft.getInstance().getConnection().sendCommand("companion syncinventory");
                }
            });
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // CRITICAL: 必须先确保 font 已初始化，然后再调用 super.render()
        // 因为父类 Screen.render() 内部会访问 font 字段
        if (this.font == null) {
            this.font = Minecraft.getInstance().font;
        }
        if (this.font == null) {
            // 如果还是 null，跳过渲染避免崩溃
            return;
        }

        // 首次渲染时请求数据同步
        if (needsInventorySync) {
            needsInventorySync = false;
            requestInventorySync();
        }

        // 计算位置（如果还没初始化）
        if (guiLeft == 0 && guiTop == 0) {
            guiLeft = (this.width - GUI_WIDTH) / 2;
            guiTop = (this.height - GUI_HEIGHT) / 2;
        }

        // 半透明背景
        graphics.fill(0, 0, this.width, this.height, 0xC0101010);

        // GUI背景面板
        graphics.fill(guiLeft, guiTop, guiLeft + GUI_WIDTH, guiTop + GUI_HEIGHT, 0xDD1a1a2e);
        graphics.fill(guiLeft + 2, guiTop + 2, guiLeft + GUI_WIDTH - 2, guiTop + GUI_HEIGHT - 2, 0xAA2a2a4a);

        // 标题
        String title = "Companion Backpack";
        graphics.drawString(this.font, title, guiLeft + (GUI_WIDTH - this.font.width(title)) / 2, guiTop + 18, 0xFFFFFF, true);

        // 重置热点
        hoveredSlot = -1;

        // 绘制设备槽（装备行）
        drawEquipmentSlots(graphics, mouseX, mouseY);

        // 绘制同伴背包格子
        drawCompanionSlots(graphics, mouseX, mouseY);

        // 绘制手中物品
        if (!heldItem.isEmpty()) {
            graphics.renderItem(heldItem, mouseX - 8, mouseY - 8);
            graphics.renderItemDecorations(this.font, heldItem, mouseX - 8, mouseY - 8);
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        // 绘制提示
        if (hoveredSlot >= 0 && hoveredSlot < TOTAL_SLOTS) {
            ItemStack stack = companionItems.get(hoveredSlot);
            if (!stack.isEmpty()) {
                List<Component> tooltip = new ArrayList<>();
                tooltip.add(stack.getDisplayName());
                if (stack.getCount() > 1) {
                    tooltip.add(Component.literal("x" + stack.getCount()));
                }
                if (hoveredSlot < COMPANION_SLOTS) {
                    // 普通存储槽
                    tooltip.add(Component.literal("Left: Take all"));
                    tooltip.add(Component.literal("Right: Take 1"));
                } else {
                    // 设备槽
                    int eqIdx = hoveredSlot - COMPANION_SLOTS;
                    if (eqIdx >= 0 && eqIdx < EQUIP_CMDS.length) {
                        tooltip.add(Component.literal("Equipped: " + EQUIP_NAMES[eqIdx]));
                        tooltip.add(Component.literal("Click to unequip"));
                    }
                }
                graphics.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
            }
        }
    }

    private void drawEquipmentSlots(GuiGraphics graphics, int mouseX, int mouseY) {
        int eqCount = EQUIPMENT_SLOTS;
        int totalEqWidth = eqCount * (SLOT_SIZE + SLOT_SPACING) - SLOT_SPACING;
        int startX = guiLeft + (GUI_WIDTH - totalEqWidth) / 2;
        int startY = guiTop + 35;

        // 标签
        String label = "Equipment";
        if (this.font != null) {
            graphics.drawString(this.font, label,
                guiLeft + (GUI_WIDTH - this.font.width(label)) / 2,
                startY - 12, 0x888888, true);
        }

        for (int i = 0; i < eqCount; i++) {
            int slotX = startX + i * (SLOT_SIZE + SLOT_SPACING);
            int slotY = startY;

            // 设备槽背景（紫色边框区分）
            graphics.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, 0xFF4a3a6a);
            graphics.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, 0xFF2a2a4a);

            // 设备槽物品（虚拟索引 27-32）
            int slotIndex = COMPANION_SLOTS + i;
            ItemStack stack = companionItems.get(slotIndex);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, slotX + 1, slotY + 1);
                graphics.renderItemDecorations(this.font, stack, slotX + 1, slotY + 1);
            }

            // 悬停高亮
            if (mouseX >= slotX && mouseX < slotX + SLOT_SIZE && mouseY >= slotY && mouseY < slotY + SLOT_SIZE) {
                graphics.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, 0x40AA00FF);
                hoveredSlot = slotIndex;
            }

            // 槽位名称标签
            if (this.font != null) {
                String name = EQUIP_NAMES[i];
                int nameX = slotX + (SLOT_SIZE - this.font.width(name)) / 2;
                graphics.drawString(this.font, name, nameX, slotY + SLOT_SIZE + 1, 0x666666, true);
            }
        }
    }

    private void drawCompanionSlots(GuiGraphics graphics, int mouseX, int mouseY) {
        int startX = guiLeft + 10;
        int startY = guiTop + 35 + EQUIPMENT_ROW_HEIGHT;

        for (int i = 0; i < COMPANION_SLOTS; i++) {
            int slotX = startX + (i % COMPANION_COLS) * (SLOT_SIZE + SLOT_SPACING);
            int slotY = startY + (i / COMPANION_COLS) * (SLOT_SIZE + SLOT_SPACING);

            // 格子背景
            graphics.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, 0xFF3a3a5a);
            graphics.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, 0xFF2a2a4a);

            // 物品
            ItemStack stack = companionItems.get(i);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, slotX + 1, slotY + 1);
                graphics.renderItemDecorations(this.font, stack, slotX + 1, slotY + 1);
            }

            // 检测热点
            if (mouseX >= slotX && mouseX < slotX + SLOT_SIZE && mouseY >= slotY && mouseY < slotY + SLOT_SIZE) {
                hoveredSlot = i;
                // 高亮
                graphics.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, 0x40FFFF00);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int slot = getSlotAtPosition((int) mouseX, (int) mouseY);

        if (slot >= 0 && slot < COMPANION_SLOTS) {
            ItemStack slotItem = companionItems.get(slot);

            if (button == 0) {
                // 左键：拿取整格物品
                if (heldItem.isEmpty()) {
                    // 手为空，直接拿整格
                    if (!slotItem.isEmpty()) {
                        heldItem = slotItem.copy();
                        companionItems.set(slot, ItemStack.EMPTY);
                        sendSlotUpdate(slot, ItemStack.EMPTY);
                    }
                } else {
                    // 手中有物品，交换
                    if (slotItem.isEmpty()) {
                        companionItems.set(slot, heldItem.copy());
                        heldItem = ItemStack.EMPTY;
                        sendSlotUpdate(slot, companionItems.get(slot));
                    } else {
                        // 两边都有物品，交换
                        ItemStack temp = slotItem.copy();
                        companionItems.set(slot, heldItem.copy());
                        heldItem = temp;
                        sendSlotUpdate(slot, companionItems.get(slot));
                    }
                }
            } else if (button == 1) {
                // 右键：拿取1个
                if (heldItem.isEmpty()) {
                    // 手为空，拿1个
                    if (!slotItem.isEmpty()) {
                        heldItem = slotItem.split(1);
                        if (slotItem.isEmpty()) {
                            companionItems.set(slot, ItemStack.EMPTY);
                        }
                        sendSlotUpdate(slot, companionItems.get(slot));
                    }
                } else {
                    // 手中有物品，放1个
                    if (slotItem.isEmpty()) {
                        // 空格，放1个
                        heldItem.shrink(1);
                        ItemStack toPut = heldItem.copy();
                        toPut.setCount(1);
                        companionItems.set(slot, toPut);
                        if (heldItem.getCount() <= 0) {
                            heldItem = ItemStack.EMPTY;
                        }
                        sendSlotUpdate(slot, companionItems.get(slot));
                    } else if (slotItem.getItem() == heldItem.getItem() && slotItem.getCount() < slotItem.getMaxStackSize()) {
                        // 相同物品，堆叠1个
                        slotItem.grow(1);
                        heldItem.shrink(1);
                        if (heldItem.getCount() <= 0) {
                            heldItem = ItemStack.EMPTY;
                        }
                        sendSlotUpdate(slot, companionItems.get(slot));
                    }
                }
            }
            return true;
        } else if (slot >= COMPANION_SLOTS && slot < TOTAL_SLOTS) {
            // 设备槽点击（27-32）
            int eqIdx = slot - COMPANION_SLOTS;
            if (eqIdx >= 0 && eqIdx < EQUIP_CMDS.length) {
                String cmd = EQUIP_CMDS[eqIdx];
                Minecraft mc = Minecraft.getInstance();
                if (mc.getConnection() != null) {
                    if (button == 0) {
                        // 左键：卸下装备
                        mc.getConnection().sendCommand("companion unequip " + cmd);
                        companionItems.set(slot, ItemStack.EMPTY);
                    } else if (button == 1 && !heldItem.isEmpty()) {
                        // 右键且有手持物品：尝试装备
                        mc.getConnection().sendCommand("companion equip " + cmd);
                        heldItem = ItemStack.EMPTY;
                    }
                }
            }
            return true;
        } else {
            // 点击空白区域
            if (button == 0 && !heldItem.isEmpty()) {
                // 左键点击空白，放下手中物品（给玩家）
                Minecraft mc = Minecraft.getInstance();
                if (mc.getConnection() != null) {
                    mc.getConnection().sendCommand("companion giveall");
                }
                heldItem = ItemStack.EMPTY;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // 右键拖拽：连续拿取
        if (button == 1 && hoveredSlot >= 0 && hoveredSlot < COMPANION_SLOTS) {
            rightClickTimer++;
            if (rightClickTimer >= RIGHT_CLICK_DELAY) {
                rightClickTimer = RIGHT_CLICK_DELAY - 2; // 加快后续拿取速度
                ItemStack slotItem = companionItems.get(hoveredSlot);
                if (!slotItem.isEmpty() && heldItem.isEmpty()) {
                    heldItem = slotItem.split(1);
                    if (slotItem.isEmpty()) {
                        companionItems.set(hoveredSlot, ItemStack.EMPTY);
                    }
                    sendSlotUpdate(hoveredSlot, companionItems.get(hoveredSlot));
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        rightClickTimer = 0;
        return false;
    }

    private int getSlotAtPosition(int mouseX, int mouseY) {
        // 先检查设备行（位于网格上方）
        int eqCount = EQUIPMENT_SLOTS;
        int totalEqWidth = eqCount * (SLOT_SIZE + SLOT_SPACING) - SLOT_SPACING;
        int eqStartX = guiLeft + (GUI_WIDTH - totalEqWidth) / 2;
        int eqStartY = guiTop + 35;

        for (int i = 0; i < eqCount; i++) {
            int slotX = eqStartX + i * (SLOT_SIZE + SLOT_SPACING);
            int slotY = eqStartY;
            if (mouseX >= slotX && mouseX < slotX + SLOT_SIZE && mouseY >= slotY && mouseY < slotY + SLOT_SIZE) {
                return COMPANION_SLOTS + i; // 27-32
            }
        }

        // 再检查网格（向下偏移了设备行高度）
        int startX = guiLeft + 10;
        int startY = guiTop + 35 + EQUIPMENT_ROW_HEIGHT;

        for (int i = 0; i < COMPANION_SLOTS; i++) {
            int slotX = startX + (i % COMPANION_COLS) * (SLOT_SIZE + SLOT_SPACING);
            int slotY = startY + (i / COMPANION_COLS) * (SLOT_SIZE + SLOT_SPACING);

            if (mouseX >= slotX && mouseX < slotX + SLOT_SIZE && mouseY >= slotY && mouseY < slotY + SLOT_SIZE) {
                return i;
            }
        }
        return -1;
    }

    private void sendSlotUpdate(int slot, ItemStack newStack) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;

        if (newStack.isEmpty()) {
            mc.getConnection().sendCommand("companion take " + slot);
        } else {
            // Request full sync after item change
            requestInventorySync();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        // 如果手中有物品，尝试放入背包或给玩家
        if (!heldItem.isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                mc.getConnection().sendCommand("companion giveall");
            }
        }
        INSTANCE = null;
        needsInventorySync = true; // 重置同步标志
        super.onClose();
    }
}