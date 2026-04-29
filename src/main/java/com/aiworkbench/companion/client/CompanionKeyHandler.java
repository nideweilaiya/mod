package com.aiworkbench.companion.client;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.client.gui.CompanionListScreen;
import com.aiworkbench.companion.client.gui.CompanionInventoryScreen;
import com.aiworkbench.companion.client.gui.CompanionSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.commands.Commands;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent.Key;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Handles keybinding for opening Companion GUI
 * Press 'C' key while playing to open companion list
 */
@Mod.EventBusSubscriber(modid = "aicompanion", value = Dist.CLIENT)
public class CompanionKeyHandler {
    public static final String CATEGORY = "key.categories.aicompanion";
    public static final String KEY_OPEN_GUI = "key.aicompanion.open_gui";
    public static final String KEY_OPEN_SETTINGS = "key.aicompanion.open_settings";
    public static final String KEY_OPEN_BACKPACK = "key.aicompanion.open_backpack";
    public static final String KEY_TELEPORT = "key.aicompanion.teleport";

    // Keys: C=list, G=settings, B=backpack, K=teleport
    public static final KeyMapping OPEN_LIST_KEY = new KeyMapping(
        KEY_OPEN_GUI,
        GLFW.GLFW_KEY_C,
        CATEGORY
    );

    public static final KeyMapping OPEN_SETTINGS_KEY = new KeyMapping(
        KEY_OPEN_SETTINGS,
        GLFW.GLFW_KEY_G,
        CATEGORY
    );

    public static final KeyMapping OPEN_BACKPACK_KEY = new KeyMapping(
        KEY_OPEN_BACKPACK,
        GLFW.GLFW_KEY_B,
        CATEGORY
    );

    public static final KeyMapping TELEPORT_KEY = new KeyMapping(
        KEY_TELEPORT,
        GLFW.GLFW_KEY_K,
        CATEGORY
    );

    // Cooldown tracking (client-side)
    private static final long TELEPORT_COOLDOWN_MS = 60000; // 1 minute
    private static long lastTeleportTime = 0;

    @Mod.EventBusSubscriber(modid = "aicompanion", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class KeyMappingRegistry {
        @SubscribeEvent
        public static void registerKeybindings(RegisterKeyMappingsEvent event) {
            event.register(OPEN_LIST_KEY);
            event.register(OPEN_SETTINGS_KEY);
            event.register(OPEN_BACKPACK_KEY);
            event.register(TELEPORT_KEY);
        }
    }

    // Input handling on Forge event bus (not MOD bus)
    public static void registerForgeEvents() {
        MinecraftForge.EVENT_BUS.register(new InputHandler());
    }

    public static class InputHandler {
        @SubscribeEvent
        public void onKeyInput(Key event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen != null) return;

            // C 键 - 打开角色列表
            if (OPEN_LIST_KEY.consumeClick()) {
                mc.setScreen(new CompanionListScreen(null));
            }

            // G 键 - 打开设置面板
            if (OPEN_SETTINGS_KEY.consumeClick()) {
                mc.setScreen(new CompanionSettingsScreen(null));
            }

            // B 键 - 直接打开背包
            if (OPEN_BACKPACK_KEY.consumeClick()) {
                mc.setScreen(new CompanionInventoryScreen());
            }

            // K 键 - 传送同伴（1分钟冷却）
            if (TELEPORT_KEY.consumeClick()) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastTeleportTime < TELEPORT_COOLDOWN_MS) {
                    long remainingSeconds = (TELEPORT_COOLDOWN_MS - (currentTime - lastTeleportTime)) / 1000;
                    mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("§c[Teleport] Cooldown: " + remainingSeconds + "s"),
                        true
                    );
                    return;
                }
                lastTeleportTime = currentTime;
                // Execute teleport command on server
                mc.player.connection.sendCommand("companion teleport");
                mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("§a[Teleport] Companion teleported to you!"),
                    true
                );
            }
        }
    }
}
