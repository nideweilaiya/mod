package com.aiworkbench.companion.entity;

import com.aiworkbench.companion.AICompanionMod;
import com.google.common.collect.ImmutableSet;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.lang.reflect.Constructor;

/**
 * Entity type registration via DeferredRegister.
 *
 * <p>MC-047: Forge 49.2.7 运行时 EntityType.Builder.of() 不存在。
 * 这是 Forge 对 EntityType 的 patch 移除了 vanilla 的 of() 静态方法。
 * 改用反射调用 EntityType 构造函数创建实体类型。</p>
 */
public class EntityInit {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, AICompanionMod.MODID);

    public static final RegistryObject<EntityType<AutomatonEntity>> AUTOMATON =
        ENTITY_TYPES.register("automaton", () -> createAutomatonType());

    /** MC-047: 反射创建 EntityType，绕过不存在的 EntityType.Builder.of()。
     *  实际构造器签名（来自 debug.log 日志）：
     *  EntityType(EntityFactory, MobCategory, boolean×4, ImmutableSet,
     *             EntityDimensions, int trackingRange, int updateInterval, FeatureFlagSet) */
    @SuppressWarnings("unchecked")
    private static EntityType<AutomatonEntity> createAutomatonType() {
        try {
            Constructor<EntityType> ctor = EntityType.class.getDeclaredConstructor(
                EntityType.EntityFactory.class,
                MobCategory.class,
                boolean.class, boolean.class, boolean.class, boolean.class,
                ImmutableSet.class,
                EntityDimensions.class,
                int.class,       // clientTrackingRange
                int.class,       // updateInterval
                net.minecraft.world.flag.FeatureFlagSet.class
            );
            ctor.setAccessible(true);
            return (EntityType<AutomatonEntity>) ctor.newInstance(
                (EntityType.EntityFactory<AutomatonEntity>) (type, level) -> new AutomatonEntity(type, level),
                MobCategory.CREATURE,
                true,   // serialize
                true,   // summon
                false,  // fireImmune
                true,   // canSpawnFarFromPlayer
                ImmutableSet.of(),
                new EntityDimensions(0.6F, 1.8F, false), // MC-047: scalable() 在 49.2.7 不存在
                64,     // clientTrackingRange
                2,      // updateInterval
                net.minecraft.world.flag.FeatureFlags.VANILLA_SET
            );
        } catch (Exception e) {
            AICompanionMod.LOGGER.error("[EntityInit] Reflection entity creation failed", e);
            throw new RuntimeException("Entity type creation failed", e);
        }
    }

    public static void register(IEventBus eventBus) {
        ENTITY_TYPES.register(eventBus);
    }
}
