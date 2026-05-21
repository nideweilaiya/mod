package com.aiworkbench.companion.client;

import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * Client-side cache mapping the player to their companion entity.
 * Finds the companion by iterating loaded AutomatonEntities and matching DATA_OWNER_UUID.
 * All entity data (health, position, name) is synced automatically by vanilla tracking.
 */
@OnlyIn(Dist.CLIENT)
public class CompanionClientState {
    @Nullable
    private static AutomatonEntity cachedCompanion = null;
    private static int cacheTick = -1;

    /**
     * Clear companion reference (e.g. on logout or companion death).
     */
    public static void clearCompanion() {
        cachedCompanion = null;
        cacheTick = -1;
    }

    /**
     * Get the companion entity for the current player.
     * Finds the companion by iterating all loaded AutomatonEntities and
     * matching their DATA_OWNER_UUID against the local player's UUID.
     * Returns null if no companion is found or the entity is dead.
     * Results are cached per tick to avoid repeated lookups.
     */
    @Nullable
    public static AutomatonEntity getCompanion() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;

        UUID playerUUID = mc.player.getUUID();
        int currentTick = mc.gui != null ? mc.gui.getGuiTicks() : 0;

        // Return cached value if still on the same tick
        if (cachedCompanion != null && cacheTick == currentTick) {
            if (cachedCompanion.isAlive()) {
                return cachedCompanion;
            }
            // Companion died, invalidate cache
            cachedCompanion = null;
            return null;
        }

        // Iterate all loaded AutomatonEntities to find one owned by this player
        AABB searchBox = new AABB(
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY
        );
        for (AutomatonEntity ae : mc.level.getEntitiesOfClass(AutomatonEntity.class, searchBox)) {
            if (ae.isAlive()) {
                Optional<UUID> ownerUUID = ae.getDataOwnerUUID();
                if (ownerUUID.isPresent() && ownerUUID.get().equals(playerUUID)) {
                    cachedCompanion = ae;
                    cacheTick = currentTick;
                    return ae;
                }
            }
        }

        // No companion found in loaded entities
        cachedCompanion = null;
        return null;
    }

    /**
     * Check if the companion is alive and tracked.
     */
    public static boolean hasCompanion() {
        return getCompanion() != null;
    }

    // ==================== 聊天模式（L键切换） ====================

    private static boolean chatMode = false;

    /**
     * 聊天模式是否开启。开启后玩家发送的所有聊天消息
     * 自动重定向为 /companion chat &lt;消息&gt;，无需手动输命令前缀。
     */
    public static boolean isChatMode() {
        return chatMode;
    }

    /**
     * 切换聊天模式开关状态。
     * @return 切换后的状态
     */
    public static boolean toggleChatMode() {
        chatMode = !chatMode;
        return chatMode;
    }

    /**
     * 直接设置聊天模式状态。
     */
    public static void setChatMode(boolean mode) {
        chatMode = mode;
    }
    /** Force clear cache so next getCompanion() re-scans the world. */
    public static void forceRefresh() {
        cachedCompanion = null;
        cacheTick = -1;
    }
}
