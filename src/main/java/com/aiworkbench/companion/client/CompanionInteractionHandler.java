package com.aiworkbench.companion.client;

import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端同伴交互处理器 — 右键同伴弹出快捷操作菜单。
 * <p>
 * 在客户端构造时注册到 Forge 事件总线。
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class CompanionInteractionHandler {

    @SubscribeEvent
    public static void onRightClickEntity(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getTarget() instanceof AutomatonEntity companion) {
            // 只在客户端处理
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            // 检查是否是同伴的主人
            if (companion.getOwnerUUID() == null || !companion.getOwnerUUID().equals(mc.player.getUUID()))
                return;

            // Shift+右键 → 交给服务端打开背包
            if (mc.player.isShiftKeyDown()) return;

            // 手持食物 → 交给服务端处理治疗
            if (!mc.player.getItemInHand(event.getHand()).isEmpty()) return;

            // 普通右键 → 打开快捷菜单
            mc.setScreen(new com.aiworkbench.companion.client.gui.CompanionQuickMenuScreen(null));
            event.setCanceled(true);
        }
    }
}
