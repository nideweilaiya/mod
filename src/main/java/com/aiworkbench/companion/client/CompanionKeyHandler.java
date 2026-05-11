package com.aiworkbench.companion.client;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.client.gui.CompanionHUDOverlay;
import com.aiworkbench.companion.client.gui.CompanionListScreen;
import com.aiworkbench.companion.client.gui.CompanionSettingsScreen;
import com.aiworkbench.companion.client.gui.SkillScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatEvent;
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
    public static final String KEY_FOLLOW_TOGGLE = "key.aicompanion.follow_toggle";
    public static final String KEY_FOLLOW_CANCEL = "key.aicompanion.follow_cancel";
    public static final String KEY_TOGGLE_HUD = "key.aicompanion.toggle_hud";
    public static final String KEY_CONTROL_MENU = "key.aicompanion.control_menu";
    public static final String KEY_SKILL_SCREEN = "key.aicompanion.skill_screen";
    public static final String KEY_CHAT_MODE = "key.aicompanion.chat_mode";

    // Keys: C=list, G=settings, B=backpack, K=teleport, V=follow toggle, ESC=cancel task, H=HUD, N=menu, P=skills, J=chat mode
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

    // V键 - 切换跟随/任务模式
    public static final KeyMapping FOLLOW_TOGGLE_KEY = new KeyMapping(
        KEY_FOLLOW_TOGGLE,
        GLFW.GLFW_KEY_V,
        CATEGORY
    );

    // Z键 - 取消当前任务，返回跟随模式
    public static final KeyMapping FOLLOW_CANCEL_KEY = new KeyMapping(
        KEY_FOLLOW_CANCEL,
        GLFW.GLFW_KEY_Z,
        CATEGORY
    );

    // N键 - 打开同伴快捷控制菜单
    public static final KeyMapping CONTROL_MENU_KEY = new KeyMapping(
        KEY_CONTROL_MENU,
        GLFW.GLFW_KEY_N,
        CATEGORY
    );

    // H键 - 切换HUD显示/隐藏
    public static final KeyMapping TOGGLE_HUD_KEY = new KeyMapping(
        KEY_TOGGLE_HUD,
        GLFW.GLFW_KEY_H,
        CATEGORY
    );

    // P键 - 打开技能库
    public static final KeyMapping SKILL_SCREEN_KEY = new KeyMapping(
        KEY_SKILL_SCREEN,
        GLFW.GLFW_KEY_P,
        CATEGORY
    );

    // J键 - 切换聊天模式（L被原版成就占用）
    public static final KeyMapping CHAT_MODE_KEY = new KeyMapping(
        KEY_CHAT_MODE,
        GLFW.GLFW_KEY_J,
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
            event.register(FOLLOW_TOGGLE_KEY);
            event.register(FOLLOW_CANCEL_KEY);
            event.register(TOGGLE_HUD_KEY);
            event.register(CONTROL_MENU_KEY);
            event.register(SKILL_SCREEN_KEY);
            event.register(CHAT_MODE_KEY);
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

            // B 键 - 打开同伴背包 Container
            if (OPEN_BACKPACK_KEY.consumeClick()) {
                mc.player.connection.sendCommand("companion openinv");
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

            // F 键 - 切换跟随/任务模式
            if (FOLLOW_TOGGLE_KEY.consumeClick()) {
                mc.player.connection.sendCommand("companion followtoggle");
                AICompanionMod.LOGGER.info("[KeyHandler] V key pressed: toggling follow mode");
            }

            // ESC 键 - 取消当前任务，返回跟随模式
            if (FOLLOW_CANCEL_KEY.consumeClick()) {
                mc.player.connection.sendCommand("companion follow");
                AICompanionMod.LOGGER.info("[KeyHandler] ESC key pressed: returning to follow mode");
            }

            // H 键 - 切换HUD显示/隐藏
            if (TOGGLE_HUD_KEY.consumeClick()) {
                CompanionHUDOverlay.toggleHUD();
                mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                        CompanionHUDOverlay.isHUDEnabled() ? "§aCompanion HUD: ON" : "§7Companion HUD: OFF"
                    ),
                    true
                );
            }

            // N 键 - 打开同伴快捷控制菜单
            if (CONTROL_MENU_KEY.consumeClick()) {
                mc.player.connection.sendCommand("companion menu");
                AICompanionMod.LOGGER.info("[KeyHandler] N key pressed: opening control menu");
            }

            // P 键 - 打开技能库
            if (SKILL_SCREEN_KEY.consumeClick()) {
                mc.setScreen(new SkillScreen(null));
                AICompanionMod.LOGGER.info("[KeyHandler] P key pressed: opening skill screen");
            }

            // J 键 - 切换聊天模式
            if (CHAT_MODE_KEY.consumeClick()) {
                boolean now = CompanionClientState.toggleChatMode();
                if (now) {
                    mc.player.displayClientMessage(
                        Component.literal("§b[聊天模式] §f已开启 — 打字直接与同伴对话，/ 开头发普通指令"),
                        true
                    );
                    AICompanionMod.LOGGER.info("[KeyHandler] Chat mode ON");
                } else {
                    mc.player.displayClientMessage(
                        Component.literal("§7[聊天模式] 已关闭"),
                        true
                    );
                    AICompanionMod.LOGGER.info("[KeyHandler] Chat mode OFF");
                }
            }
        }

        /**
         * 拦截客户端聊天消息。聊天模式开启时，普通消息自动转为 /companion chat 命令，
         * 以 / 开头的命令不受影响。
         */
        @SubscribeEvent
        public void onClientChat(ClientChatEvent event) {
            if (!CompanionClientState.isChatMode()) return;

            String message = event.getMessage();
            if (message == null || message.isEmpty()) return;

            // 以 / 开头的命令放行，不拦截
            if (message.startsWith("/")) return;

            // 取消原聊天消息发送
            event.setCanceled(true);

            // 转为 /companion chat <消息> 发送到服务端
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.connection != null) {
                mc.player.connection.sendCommand("companion chat " + message);
            }
        }
    }
}
