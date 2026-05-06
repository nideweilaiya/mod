package com.aiworkbench.companion.network;

import com.aiworkbench.companion.AICompanionMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;

import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.core.NonNullList;

/**
 * Network packet for synchronizing companion inventory from server to client.
 * Uses Minecraft's built-in network system via ServerPlayNetHandler.
 */
public class CompanionInventorySyncPacket {
    // Channel name
    private static final ResourceLocation CHANNEL_NAME = new ResourceLocation(AICompanionMod.MODID, "inventory_sync");
    private static final String PROTOCOL_VERSION = "1";

    // Network direction: 0 = clientbound, 1 = serverbound
    private static final int ID = 0;

    private UUID companionUUID;
    private ListTag itemsTag;

    public CompanionInventorySyncPacket() {}

    public CompanionInventorySyncPacket(UUID companionUUID, ListTag itemsTag) {
        this.companionUUID = companionUUID;
        this.itemsTag = itemsTag;
    }

    public UUID getCompanionUUID() {
        return companionUUID;
    }

    public ListTag getItemsTag() {
        return itemsTag;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(companionUUID);
        buf.writeNbt(itemsTag);
    }

    public static CompanionInventorySyncPacket decode(FriendlyByteBuf buf) {
        UUID uuid = buf.readUUID();
        CompoundTag rootTag = buf.readNbt();
        ListTag tag = new ListTag();
        if (rootTag != null && rootTag.contains("Items", 9)) { // 9 = LIST type
            tag = rootTag.getList("Items", 10); // 10 = COMPOUND type
        }
        return new CompanionInventorySyncPacket(uuid, tag);
    }

    public static NonNullList<ItemStack> reconstructInventory(ListTag itemsTag) {
        NonNullList<ItemStack> items = NonNullList.withSize(33, ItemStack.EMPTY);
        for (int i = 0; i < itemsTag.size(); i++) {
            CompoundTag itemTag = itemsTag.getCompound(i);
            int slot = itemTag.getInt("Slot");
            if (slot >= 0 && slot < 33) {
                items.set(slot, ItemStack.of(itemTag));
            }
        }
        return items;
    }
}
