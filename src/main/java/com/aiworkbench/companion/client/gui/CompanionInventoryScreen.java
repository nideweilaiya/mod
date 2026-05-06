package com.aiworkbench.companion.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Companion Inventory Screen - 同伴背包 + 玩家背包
 * 上半：同伴背包 + 装备栏
 * 下半：玩家背包 + 快捷栏
 * 左键点击拖动物品，右键拿取1个
 */
public class CompanionInventoryScreen extends Screen {
    private static final int SLOT_SIZE = 18;
    private static final int SLOT_SPACING = 4;
    private static final int COLS = 9;
    private static final int COMPANION_COLS = COLS;
    private static final int COMPANION_ROWS = 3;
    private static final int COMPANION_SLOTS = COMPANION_COLS * COMPANION_ROWS; // 27
    private static final int EQUIPMENT_SLOTS = 6; // mainhand, offhand, feet, legs, chest, head
    private static final int TOTAL_COMPANION_SLOTS = COMPANION_SLOTS + EQUIPMENT_SLOTS; // 33

    // Player inventory: 27 main + 9 hotbar
    private static final int PLAYER_INV_SLOTS = 36;

    private static final int GUI_WIDTH = COLS * (SLOT_SIZE + SLOT_SPACING) - SLOT_SPACING + 20;
    private static final int GUI_HEIGHT = 20 + 50 + 62 + 10 + 62 + 22 + 20; // title+equip+companion+gap+player+hotbar+pad
    private static final String[] EQUIP_NAMES = {"MainHand", "OffHand", "Feet", "Legs", "Chest", "Head"};
    private static final String[] EQUIP_CMDS = {"mainhand", "offhand", "feet", "legs", "chest", "head"};

    private static CompanionInventoryScreen INSTANCE;

    public static CompanionInventoryScreen instance() {
        return INSTANCE;
    }

    private int guiLeft;
    private int guiTop;

    // Companion items (27 storage + 6 equipment)
    private NonNullList<ItemStack> companionItems = NonNullList.withSize(TOTAL_COMPANION_SLOTS, ItemStack.EMPTY);

    // "Held" item - picked up from a slot, follows cursor
    private ItemStack heldItem = ItemStack.EMPTY;

    private int hoveredSlot = -1;
    private String hoveredSection = "";

    // First render flag - triggers inventory sync
    private boolean needsInventorySync = true;

    // Right-click repeat
    private int rightClickTimer = 0;
    private static final int RIGHT_CLICK_DELAY = 10;

    public CompanionInventoryScreen() {
        super(Component.literal("Companion Backpack"));
        INSTANCE = this;
    }

    public static boolean isOpen() {
        return INSTANCE != null && Minecraft.getInstance().screen == INSTANCE;
    }

    /**
     * Update inventory data from server sync messages
     */
    public void updateInventoryData(NonNullList<ItemStack> items) {
        this.companionItems = items;
    }

    @Override
    protected void init() {
        super.init();
        INSTANCE = this;
        guiLeft = (this.width - GUI_WIDTH) / 2;
        guiTop = (this.height - GUI_HEIGHT) / 2;
        clearWidgets();

        // Close button
        addRenderableWidget(Button.builder(
                Component.literal("✕"),
                btn -> onClose())
                .bounds(guiLeft + GUI_WIDTH - 25, guiTop + 5, 20, 20)
                .build());
    }

    private void requestInventorySync() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            mc.execute(() -> {
                if (Minecraft.getInstance().getConnection() != null) {
                    Minecraft.getInstance().getConnection().sendCommand("companion syncinventory");
                }
            });
        }
    }

    // ==================== Layout Helpers ====================

    private int getEquipmentStartY() {
        return guiTop + 35;
    }

    private int getCompanionStartY() {
        return getEquipmentStartY() + 28;
    }

    private int getPlayerStartY() {
        return getCompanionStartY() + 3 * (SLOT_SIZE + SLOT_SPACING) + 14;
    }

    private int getHotbarStartY() {
        return getPlayerStartY() + 3 * (SLOT_SIZE + SLOT_SPACING) + 4;
    }

    // ==================== Render ====================

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.font == null) {
            this.font = Minecraft.getInstance().font;
        }
        if (this.font == null) return;

        // First render: request sync
        if (needsInventorySync) {
            needsInventorySync = false;
            requestInventorySync();
        }

        // Ensure position calculated
        if (guiLeft == 0 && guiTop == 0) {
            guiLeft = (this.width - GUI_WIDTH) / 2;
            guiTop = (this.height - GUI_HEIGHT) / 2;
        }

        // Full screen overlay
        graphics.fill(0, 0, this.width, this.height, 0xC0101010);

        // Main panel background
        graphics.fill(guiLeft, guiTop, guiLeft + GUI_WIDTH, guiTop + GUI_HEIGHT, 0xDD1a1a2e);
        graphics.fill(guiLeft + 2, guiTop + 2, guiLeft + GUI_WIDTH - 2, guiTop + GUI_HEIGHT - 2, 0xAA2a2a4a);

        // Title
        String title = "§6§l同伴背包";
        graphics.drawString(this.font, title,
            guiLeft + (GUI_WIDTH - this.font.width(title)) / 2, guiTop + 12, 0xFFFFFF, false);

        // Reset hover
        hoveredSlot = -1;
        hoveredSection = "";

        // Draw equipment slots
        drawEquipmentSlots(graphics, mouseX, mouseY);

        // Draw companion storage
        drawCompanionSlots(graphics, mouseX, mouseY);

        // Draw player inventory + hotbar
        drawPlayerSlots(graphics, mouseX, mouseY);

        // Draw held item at cursor
        if (!heldItem.isEmpty()) {
            graphics.renderItem(heldItem, mouseX - 8, mouseY - 8);
            graphics.renderItemDecorations(this.font, heldItem, mouseX - 8, mouseY - 8);
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        // Tooltip for hovered slot
        if (hoveredSlot >= 0 && !hoveredSection.isEmpty()) {
            renderTooltip(graphics, mouseX, mouseY);
        }
    }

    private void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        List<Component> tooltip = new ArrayList<>();
        ItemStack stack = ItemStack.EMPTY;

        switch (hoveredSection) {
            case "companion" -> {
                if (hoveredSlot < COMPANION_SLOTS) {
                    stack = companionItems.get(hoveredSlot);
                } else {
                    int eqIdx = hoveredSlot - COMPANION_SLOTS;
                    if (eqIdx >= 0 && eqIdx < EQUIP_CMDS.length) {
                        stack = companionItems.get(hoveredSlot);
                        tooltip.add(Component.literal("§d[装备] " + EQUIP_NAMES[eqIdx]));
                        tooltip.add(Component.literal("§7左键卸下"));
                        if (heldItem.isEmpty()) {
                            if (!stack.isEmpty()) {
                                graphics.renderComponentTooltip(this.font,
                                    List.of(stack.getDisplayName(), Component.literal("§7左键卸下")),
                                    mouseX, mouseY);
                            }
                            return;
                        }
                    }
                }
            }
            case "player", "hotbar" -> {
                int idx = hoveredSlot;
                Player p = Minecraft.getInstance().player;
                if (p != null) {
                    if (hoveredSection.equals("hotbar")) idx += 27; // hotbar offset
                    stack = p.getInventory().getItem(idx);
                }
            }
        }

        if (!stack.isEmpty()) {
            tooltip.add(0, stack.getDisplayName());
            if (stack.getCount() > 1) {
                tooltip.add(Component.literal("§7x" + stack.getCount()));
            }
        }

        // Add interaction hint
        String hint = switch (hoveredSection) {
            case "companion" -> heldItem.isEmpty() ? "§e左键→拿取到手中" : "§e左键→放入/交换";
            case "equipment" -> "§e左键→卸下装备";
            case "player", "hotbar" -> heldItem.isEmpty() ? "§e左键→拿取" : "§e左键→放入";
            default -> "";
        };
        if (!hint.isEmpty()) {
            tooltip.add(Component.literal(hint));
        }

        if (!tooltip.isEmpty()) {
            graphics.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
        }
    }

    // ==================== Draw Equipment ====================

    private void drawEquipmentSlots(GuiGraphics graphics, int mouseX, int mouseY) {
        int eqCount = EQUIPMENT_SLOTS;
        int totalEqWidth = eqCount * (SLOT_SIZE + SLOT_SPACING) - SLOT_SPACING;
        int startX = guiLeft + (GUI_WIDTH - totalEqWidth) / 2;
        int startY = getEquipmentStartY();

        // Label
        graphics.drawString(this.font, "§7装备",
            guiLeft + (GUI_WIDTH - this.font.width("装备")) / 2,
            startY - 12, 0x888888, false);

        for (int i = 0; i < eqCount; i++) {
            int slotX = startX + i * (SLOT_SIZE + SLOT_SPACING);
            int slotY = startY;

            // Equipment slot background (purple)
            graphics.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, 0xFF4a3a6a);
            graphics.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, 0xFF2a2a4a);

            // Item (virtual index 27-32)
            int slotIndex = COMPANION_SLOTS + i;
            ItemStack stack = companionItems.get(slotIndex);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, slotX + 1, slotY + 1);
                graphics.renderItemDecorations(this.font, stack, slotX + 1, slotY + 1);
            }

            // Hover
            if (mouseX >= slotX && mouseX < slotX + SLOT_SIZE && mouseY >= slotY && mouseY < slotY + SLOT_SIZE) {
                graphics.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, 0x40AA00FF);
                hoveredSlot = slotIndex;
                hoveredSection = "equipment";
            }

            // Slot label
            graphics.drawString(this.font, EQUIP_NAMES[i],
                slotX + (SLOT_SIZE - this.font.width(EQUIP_NAMES[i])) / 2,
                slotY + SLOT_SIZE + 1, 0x666666, false);
        }
    }

    // ==================== Draw Companion Storage ====================

    private void drawCompanionSlots(GuiGraphics graphics, int mouseX, int mouseY) {
        int startX = guiLeft + 10;
        int startY = getCompanionStartY();

        // Section label
        int labelY = startY - 10;
        graphics.drawString(this.font, "§e同伴背包",
            guiLeft + 10, labelY, 0xFFFFFF, false);

        for (int i = 0; i < COMPANION_SLOTS; i++) {
            int slotX = startX + (i % COLS) * (SLOT_SIZE + SLOT_SPACING);
            int slotY = startY + (i / COLS) * (SLOT_SIZE + SLOT_SPACING);

            graphics.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, 0xFF3a3a5a);
            graphics.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, 0xFF2a2a4a);

            ItemStack stack = companionItems.get(i);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, slotX + 1, slotY + 1);
                graphics.renderItemDecorations(this.font, stack, slotX + 1, slotY + 1);
            }

            if (mouseX >= slotX && mouseX < slotX + SLOT_SIZE && mouseY >= slotY && mouseY < slotY + SLOT_SIZE) {
                graphics.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, 0x40FFFF00);
                hoveredSlot = i;
                hoveredSection = "companion";
            }
        }
    }

    // ==================== Draw Player Inventory ====================

    private void drawPlayerSlots(GuiGraphics graphics, int mouseX, int mouseY) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        // --- Player main inventory (27 slots, 9x3) ---
        int startX = guiLeft + 10;
        int startY = getPlayerStartY();

        graphics.drawString(this.font, "§7玩家背包",
            guiLeft + 10, startY - 10, 0xFFFFFF, false);

        for (int i = 9; i < 36; i++) { // slots 9-35 = main inventory
            int row = (i - 9) / 9;
            int col = (i - 9) % 9;
            int slotX = startX + col * (SLOT_SIZE + SLOT_SPACING);
            int slotY = startY + row * (SLOT_SIZE + SLOT_SPACING);

            // Different background shade
            graphics.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, 0xFF3a3a3a);
            graphics.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, 0xFF2a2a2a);

            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, slotX + 1, slotY + 1);
                graphics.renderItemDecorations(this.font, stack, slotX + 1, slotY + 1);
            }

            if (mouseX >= slotX && mouseX < slotX + SLOT_SIZE && mouseY >= slotY && mouseY < slotY + SLOT_SIZE) {
                graphics.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, 0x40FFFFFF);
                hoveredSlot = i;
                hoveredSection = "player";
            }
        }

        // --- Hotbar (9 slots) ---
        int hotbarY = getHotbarStartY();
        graphics.drawString(this.font, "§7快捷栏",
            guiLeft + 10, hotbarY - 10, 0xFFFFFF, false);

        for (int i = 0; i < 9; i++) {
            int slotX = startX + i * (SLOT_SIZE + SLOT_SPACING);
            int slotY = hotbarY;

            graphics.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, 0xFF4a4a3a);
            graphics.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, 0xFF3a3a2a);

            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, slotX + 1, slotY + 1);
                graphics.renderItemDecorations(this.font, stack, slotX + 1, slotY + 1);
            }

            if (mouseX >= slotX && mouseX < slotX + SLOT_SIZE && mouseY >= slotY && mouseY < slotY + SLOT_SIZE) {
                graphics.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, 0x40FFFFFF);
                hoveredSlot = i;
                hoveredSection = "hotbar";
            }
        }
    }

    // ==================== Slot Position Detection ====================

    private String getSlotAtPosition(int mouseX, int mouseY, int[] outSlot) {
        outSlot[0] = -1;

        // Check equipment slots
        int eqCount = EQUIPMENT_SLOTS;
        int totalEqWidth = eqCount * (SLOT_SIZE + SLOT_SPACING) - SLOT_SPACING;
        int eqStartX = guiLeft + (GUI_WIDTH - totalEqWidth) / 2;
        int eqStartY = getEquipmentStartY();
        for (int i = 0; i < eqCount; i++) {
            int sx = eqStartX + i * (SLOT_SIZE + SLOT_SPACING);
            int sy = eqStartY;
            if (mouseX >= sx && mouseX < sx + SLOT_SIZE && mouseY >= sy && mouseY < sy + SLOT_SIZE) {
                outSlot[0] = COMPANION_SLOTS + i;
                return "equipment";
            }
        }

        // Check companion storage
        int compStartX = guiLeft + 10;
        int compStartY = getCompanionStartY();
        for (int i = 0; i < COMPANION_SLOTS; i++) {
            int sx = compStartX + (i % COLS) * (SLOT_SIZE + SLOT_SPACING);
            int sy = compStartY + (i / COLS) * (SLOT_SIZE + SLOT_SPACING);
            if (mouseX >= sx && mouseX < sx + SLOT_SIZE && mouseY >= sy && mouseY < sy + SLOT_SIZE) {
                outSlot[0] = i;
                return "companion";
            }
        }

        // Check player main inventory (slots 9-35)
        int playerStartX = guiLeft + 10;
        int playerStartY = getPlayerStartY();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int sx = playerStartX + col * (SLOT_SIZE + SLOT_SPACING);
                int sy = playerStartY + row * (SLOT_SIZE + SLOT_SPACING);
                if (mouseX >= sx && mouseX < sx + SLOT_SIZE && mouseY >= sy && mouseY < sy + SLOT_SIZE) {
                    outSlot[0] = 9 + row * 9 + col; // actual player inventory index
                    return "player";
                }
            }
        }

        // Check hotbar (slots 0-8)
        int hotbarY = getHotbarStartY();
        for (int i = 0; i < 9; i++) {
            int sx = playerStartX + i * (SLOT_SIZE + SLOT_SPACING);
            int sy = hotbarY;
            if (mouseX >= sx && mouseX < sx + SLOT_SIZE && mouseY >= sy && mouseY < sy + SLOT_SIZE) {
                outSlot[0] = i;
                return "hotbar";
            }
        }

        return "";
    }

    // ==================== Mouse Interaction ====================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int[] slotRef = new int[1];
        String section = getSlotAtPosition((int) mouseX, (int) mouseY, slotRef);
        int slot = slotRef[0];
        Minecraft mc = Minecraft.getInstance();

        if (section.equals("companion") && slot >= 0 && slot < COMPANION_SLOTS) {
            // Companion storage slot
            ItemStack slotItem = companionItems.get(slot);

            if (button == 0) {
                // Left click
                if (heldItem.isEmpty()) {
                    // Take item from companion
                    if (!slotItem.isEmpty()) {
                        mc.getConnection().sendCommand("companion take " + slot);
                        heldItem = slotItem.copy();
                        companionItems.set(slot, ItemStack.EMPTY);
                    }
                } else {
                    // Put held item into companion:
                    // 1. Add heldItem to player inventory (it was taken from player earlier)
                    // 2. Send "companion put <slot>" (now scans entire player inventory)
                    // 3. Update GUI state
                    Player player = mc.player;
                    if (player != null) {
                        player.getInventory().add(heldItem.copy());
                    }
                    mc.getConnection().sendCommand("companion put " + slot);
                    companionItems.set(slot, heldItem.copy());
                    heldItem = ItemStack.EMPTY;
                    requestInventorySync();
                }
            } else if (button == 1) {
                // Right click - take 1 item
                if (!slotItem.isEmpty() && heldItem.isEmpty()) {
                    mc.getConnection().sendCommand("companion takeone " + slot);
                    heldItem = slotItem.split(1);
                    if (slotItem.isEmpty()) companionItems.set(slot, ItemStack.EMPTY);
                }
            }
            return true;

        } else if (section.equals("equipment") && slot >= COMPANION_SLOTS) {
            // Equipment slot
            int eqIdx = slot - COMPANION_SLOTS;
            if (eqIdx >= 0 && eqIdx < EQUIP_CMDS.length) {
                if (button == 0) {
                    // Unequip
                    mc.getConnection().sendCommand("companion unequip " + EQUIP_CMDS[eqIdx]);
                    companionItems.set(slot, ItemStack.EMPTY);
                } else if (button == 1 && !heldItem.isEmpty()) {
                    // Equip from hand
                    mc.getConnection().sendCommand("companion equip " + EQUIP_CMDS[eqIdx]);
                    heldItem = ItemStack.EMPTY;
                }
            }
            return true;

        } else if (section.equals("player") || section.equals("hotbar")) {
            // Player inventory slot
            Player player = mc.player;
            if (player == null) return true;

            if (button == 0) {
                if (heldItem.isEmpty()) {
                    // Take from player (works locally on client, server will sync)
                    ItemStack playerStack = player.getInventory().getItem(slot);
                    if (!playerStack.isEmpty()) {
                        heldItem = playerStack.copy();
                        player.getInventory().setItem(slot, ItemStack.EMPTY);
                    }
                } else {
                    // Put into player
                    ItemStack playerStack = player.getInventory().getItem(slot);
                    if (playerStack.isEmpty()) {
                        player.getInventory().setItem(slot, heldItem.copy());
                        heldItem = ItemStack.EMPTY;
                    } else if (ItemStack.isSameItemSameTags(playerStack, heldItem)) {
                        int space = playerStack.getMaxStackSize() - playerStack.getCount();
                        int toAdd = Math.min(space, heldItem.getCount());
                        if (toAdd > 0) {
                            playerStack.grow(toAdd);
                            heldItem.shrink(toAdd);
                            if (heldItem.isEmpty()) heldItem = ItemStack.EMPTY;
                        }
                    } else {
                        // Swap
                        ItemStack temp = playerStack.copy();
                        player.getInventory().setItem(slot, heldItem.copy());
                        heldItem = temp;
                    }
                }
            }
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        // If holding items, give them to player
        if (!heldItem.isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                mc.getConnection().sendCommand("companion giveall");
            }
            // Also put held items into player inventory locally
            Player player = mc.player;
            if (player != null) {
                player.getInventory().add(heldItem);
            }
            heldItem = ItemStack.EMPTY;
        }
        INSTANCE = null;
        needsInventorySync = true;
        super.onClose();
    }
}