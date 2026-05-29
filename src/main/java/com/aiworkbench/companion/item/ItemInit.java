package com.aiworkbench.companion.item;

import com.aiworkbench.companion.AICompanionMod;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Item registration via DeferredRegister.
 */
public class ItemInit {
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, AICompanionMod.MODID);

    public static final RegistryObject<Item> COMPANION_CORE =
        ITEMS.register("companion_core", () ->
            new CompanionCoreItem(new Item.Properties())); // MC-047: Forge 49.2.7 无 stacksTo(int)

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
