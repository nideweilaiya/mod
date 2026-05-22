package com.aiworkbench.companion.entity;

import com.aiworkbench.companion.AICompanionMod;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class EntityInit {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, AICompanionMod.MODID);

    public static final RegistryObject<EntityType<AutomatonEntity>> AUTOMATON =
        ENTITY_TYPES.register("automaton", () ->
            EntityType.Builder.of(AutomatonEntity::new, MobCategory.CREATURE)
                .sized(0.6F, 1.8F)
                .clientTrackingRange(64)
                .updateInterval(2)
                .build("automaton")
        );

    public static void register(IEventBus eventBus) {
        ENTITY_TYPES.register(eventBus);
    }
}
