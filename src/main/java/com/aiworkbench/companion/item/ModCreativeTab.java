package com.aiworkbench.companion.item;

import com.aiworkbench.companion.AICompanionMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Creative mode tab registration for the mod.
 */
public class ModCreativeTab {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AICompanionMod.MODID);

    public static final RegistryObject<CreativeModeTab> COMPANION_TAB =
        CREATIVE_TABS.register("companion_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.aicompanion"))
            .icon(() -> new ItemStack(ItemInit.COMPANION_CORE.get()))
            .displayItems((params, output) -> {
                output.accept(ItemInit.COMPANION_CORE.get());
            })
            .build());

    public static void register(IEventBus eventBus) {
        CREATIVE_TABS.register(eventBus);
    }
}
