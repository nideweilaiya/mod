package com.aiworkbench.companion.client;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.client.render.RenderAutomaton;
import com.aiworkbench.companion.entity.EntityInit;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import javax.annotation.Nullable;

/**
 * AI Companion 客户端入口
 * 处理客户端渲染
 */
@Mod.EventBusSubscriber(modid = AICompanionMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class AICompanionClient {
    // 当前选中的NPC
    @Nullable
    private static Entity selectedEntity = null;

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        AICompanionMod.LOGGER.info("EntityRenderersEvent.RegisterRenderers fired - checking AUTOMATON availability");

        // Guard: if entity not registered yet, log error (shouldn't happen if commonSetup ran correctly)
        if (!EntityInit.AUTOMATON.isPresent()) {
            AICompanionMod.LOGGER.error("[Client] AUTOMATON entity not registered! Renderer cannot be registered.");
            return;
        }

        AICompanionMod.LOGGER.info("Registering AutomatonEntity renderer for {}", EntityInit.AUTOMATON.getId());
        event.registerEntityRenderer(EntityInit.AUTOMATON.get(), RenderAutomaton::new);
        AICompanionMod.LOGGER.info("AutomatonEntity renderer registered successfully");
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        AICompanionMod.LOGGER.info("AI Companion Client setup complete");
        // K键功能将在后续版本实现
    }

    /**
     * 设置选中的实体
     */
    public static void setSelectedEntity(@Nullable Entity entity) {
        selectedEntity = entity;
    }

    /**
     * 获取选中的实体
     */
    @Nullable
    public static Entity getSelectedEntity() {
        return selectedEntity;
    }
}
