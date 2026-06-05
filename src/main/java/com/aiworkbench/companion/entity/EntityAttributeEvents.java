package com.aiworkbench.companion.entity;

import com.aiworkbench.companion.AICompanionMod;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Subscribes to EntityAttributeCreationEvent on the MOD bus.
 * This event fires AFTER entity types are registered via DeferredRegister,
 * during the MOD CONSTRUCT phase of mod loading.
 * This is the official Forge 1.20.4 mechanism for registering entity attributes.
 */
@Mod.EventBusSubscriber(modid = AICompanionMod.MODID, bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.MOD)
public class EntityAttributeEvents {

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        AICompanionMod.LOGGER.info("[Attributes] EntityAttributeCreationEvent fired - registering AutomatonEntity attributes");

        if (EntityInit.AUTOMATON.isPresent()) {
            AttributeSupplier supplier = AutomatonEntity.createAttributes().build();
            event.put(EntityInit.AUTOMATON.get(), supplier);
            AICompanionMod.LOGGER.info("[Attributes] SUCCESS - Attributes registered for aicompanion:automaton");
            AICompanionMod.LOGGER.info("[Attributes]   MAX_HEALTH: 20.0, ARMOR: 0.0, ARMOR_TOUGHNESS: 0.0, MOVEMENT_SPEED: 0.28 (task base, follow base = 0.10)");
        } else {
            AICompanionMod.LOGGER.error("[Attributes] AUTOMATON entity not present when EntityAttributeCreationEvent fired!");
        }
    }
}
