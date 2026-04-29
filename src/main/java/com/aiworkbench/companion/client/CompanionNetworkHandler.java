package com.aiworkbench.companion.client;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.client.gui.CompanionInventoryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

import net.minecraft.core.NonNullList;

/**
 * Client-side event subscriber for handling inventory sync messages.
 * Listens to chat messages with [INV_START], [INV_SLOT:...], [INV_END] format.
 */
@EventBusSubscriber(modid = AICompanionMod.MODID, bus = EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class CompanionNetworkHandler {

    private static boolean receivingInventory = false;
    private static NonNullList<ItemStack> pendingInventory = NonNullList.withSize(27, ItemStack.EMPTY);

    @SubscribeEvent
    public static void onClientChatReceived(ClientChatReceivedEvent event) {
        Component message = event.getMessage();
        String text = message.getString();

        if (text.startsWith("[INV_START]")) {
            // Start receiving inventory
            receivingInventory = true;
            pendingInventory = NonNullList.withSize(27, ItemStack.EMPTY);
            event.setMessage(net.minecraft.network.chat.Component.literal("")); // Hide the message
            return;
        }

        if (text.startsWith("[INV_END]")) {
            // Finished receiving inventory
            receivingInventory = false;
            // Update the GUI if open
            if (CompanionInventoryScreen.instance() != null) {
                CompanionInventoryScreen.instance().updateInventoryData(pendingInventory);
            }
            event.setMessage(net.minecraft.network.chat.Component.literal("")); // Hide the message
            return;
        }

        if (receivingInventory && text.startsWith("[INV_SLOT:")) {
            // Parse slot data: [INV_SLOT:slot:id:count:tag]
            // Note: itemId (id) may contain colons, e.g. "minecraft:dirt"
            event.setMessage(net.minecraft.network.chat.Component.literal("")); // Hide the message
            try {
                String data = text.substring("[INV_SLOT:".length(), text.length() - 1);
                String[] parts = data.split(":");
                if (parts.length >= 4) {
                    int slot = Integer.parseInt(parts[0]);
                    // itemId is parts[1] + ":" + parts[2] (namespace:path)
                    String itemId = parts[1] + ":" + parts[2];
                    int count = Integer.parseInt(parts[3]);

                    // Try to reconstruct ItemStack from BuiltInRegistries
                    Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
                    if (item != null && item != Items.AIR) {
                        ItemStack stack = new ItemStack(item, count);
                        if (parts.length > 4 && !parts[4].isEmpty()) {
                            // Parse NBT tag if present
                            try {
                                // Reconstruct tag from remaining parts (may contain colons)
                                String tagStr = String.join(":", java.util.Arrays.copyOfRange(parts, 4, parts.length));
                                CompoundTag tag = parseTagString(tagStr);
                                if (tag != null) {
                                    stack.setTag(tag);
                                }
                            } catch (Exception e) {
                                // Ignore tag parse errors
                            }
                        }
                        if (slot >= 0 && slot < 27) {
                            pendingInventory.set(slot, stack);
                        }
                    }
                }
            } catch (Exception e) {
                AICompanionMod.LOGGER.warn("Failed to parse inventory slot: " + text);
            }
        }
    }

    /**
     * Parse a simplified NBT tag string back to CompoundTag
     * Note: This is a simplified parser for basic tags
     */
    private static CompoundTag parseTagString(String tagStr) {
        // Simple NBT format: {key:value,key2:value2}
        if (tagStr == null || tagStr.isEmpty() || !tagStr.startsWith("{")) {
            return null;
        }
        try {
            CompoundTag tag = new CompoundTag();
            // Remove surrounding braces
            String content = tagStr.substring(1, tagStr.length() - 1);
            // Split by comma (but be careful with nested structures)
            String[] pairs = content.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":");
                if (kv.length == 2) {
                    String key = kv[0].trim();
                    String value = kv[1].trim();
                    // Try to parse as different types
                    if (value.startsWith("{")) {
                        // Nested compound - recursively parse
                        CompoundTag nested = parseTagString(value);
                        if (nested != null) {
                            tag.put(key, nested);
                        }
                    } else if (value.equals("true") || value.equals("false")) {
                        tag.putBoolean(key, Boolean.parseBoolean(value));
                    } else if (value.contains(".")) {
                        try {
                            tag.putDouble(key, Double.parseDouble(value));
                        } catch (NumberFormatException e) {
                            tag.putString(key, value);
                        }
                    } else {
                        try {
                            tag.putInt(key, Integer.parseInt(value));
                        } catch (NumberFormatException e) {
                            tag.putString(key, value);
                        }
                    }
                }
            }
            return tag;
        } catch (Exception e) {
            return null;
        }
    }
}
