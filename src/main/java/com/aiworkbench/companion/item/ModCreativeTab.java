package com.aiworkbench.companion.item;

import net.minecraftforge.eventbus.api.IEventBus;

/**
 * Creative mode tab registration — 永久禁用。
 *
 * <p>MC-047: Forge 49.2.7 运行时 Registries 类中不存在 CREATIVE_MODE_TAB 字段。
 * 这是 MC 1.20.5 新增的 API，49.2.7 底层 MC 版本不包含此字段。
 * 创造标签为非必要功能（物品可通过 /give @p aicompanion:companion_core 获取），
 * 待升级到 1.20.5+ 后恢复。</p>
 */
public class ModCreativeTab {
    /** MC-047: 创造标签注册在 49.2.7 中不可用，永久跳过。 */
    public static void register(IEventBus eventBus) {
        // no-op: CREATIVE_MODE_TAB registry key does not exist in Forge 49.2.7 runtime
    }
}
