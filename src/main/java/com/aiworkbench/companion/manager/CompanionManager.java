package com.aiworkbench.companion.manager;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 伴侣管理器
 * 管理每个玩家对应的同伴NPC
 */
public class CompanionManager {
    // 玩家UUID -> 伴侣实体UUID (使用ConcurrentHashMap保证线程安全)
    private final Map<UUID, UUID> playerToCompanionMap = new ConcurrentHashMap<>();
    // 伴侣实体UUID -> 玩家UUID (反向索引)
    private final Map<UUID, UUID> companionToPlayerMap = new ConcurrentHashMap<>();

    /**
     * 添加伴侣
     */
    public void addCompanion(UUID playerUUID, AutomatonEntity companion) {
        playerToCompanionMap.put(playerUUID, companion.getUUID());
        companionToPlayerMap.put(companion.getUUID(), playerUUID);
        AICompanionMod.LOGGER.info("Registered companion {} for player {}", companion.getName().getString(), playerUUID);
    }

    /**
     * 移除伴侣（从世界删除实体）
     */
    public void removeCompanion(UUID playerUUID) {
        UUID companionUUID = playerToCompanionMap.remove(playerUUID);
        if (companionUUID != null) {
            companionToPlayerMap.remove(companionUUID);
            AICompanionMod.LOGGER.info("Removing companion {} for player {}", companionUUID, playerUUID);
            // 使用O(1) UUID查找删除实体
            if (AICompanionMod.server != null) {
                for (ServerLevel level : AICompanionMod.server.getAllLevels()) {
                    Entity entity = level.getEntity(companionUUID);
                    if (entity instanceof AutomatonEntity) {
                        entity.discard();
                        AICompanionMod.LOGGER.info("Companion entity {} discarded from world", companionUUID);
                        break;
                    }
                }
            }
        }
    }

    /**
     * 通过伴侣实体UUID移除伴侣
     */
    public void removeCompanionById(UUID companionUUID) {
        UUID playerUUID = companionToPlayerMap.remove(companionUUID);
        if (playerUUID != null) {
            playerToCompanionMap.remove(playerUUID);
        }
        if (AICompanionMod.server != null) {
            for (ServerLevel level : AICompanionMod.server.getAllLevels()) {
                Entity entity = level.getEntity(companionUUID);
                if (entity instanceof AutomatonEntity) {
                    entity.discard();
                    AICompanionMod.LOGGER.info("Companion entity {} discarded from world", companionUUID);
                    break;
                }
            }
        }
    }

    /**
     * 通过玩家名称移除伴侣
     */
    public void removeCompanionByName(String playerName) {
        if (AICompanionMod.server == null) return;
        for (ServerPlayer player : AICompanionMod.server.getPlayerList().getPlayers()) {
            if (player.getName().getString().equals(playerName)) {
                removeCompanion(player.getUUID());
                return;
            }
        }
    }

    /**
     * 获取玩家的伴侣
     */
    public AutomatonEntity getCompanion(UUID playerUUID) {
        UUID companionUUID = playerToCompanionMap.get(playerUUID);
        if (companionUUID == null) return null;

        // 使用O(1) UUID查找
        if (AICompanionMod.server == null) {
            return null;
        }

        for (ServerLevel level : AICompanionMod.server.getAllLevels()) {
            Entity entity = level.getEntity(companionUUID);
            if (entity instanceof AutomatonEntity) {
                return (AutomatonEntity) entity;
            }
        }
        return null;
    }

    /**
     * 通过伴侣UUID获取伴侣实体
     */
    public AutomatonEntity getCompanionById(UUID companionUUID) {
        if (companionUUID == null || AICompanionMod.server == null) {
            return null;
        }

        for (ServerLevel level : AICompanionMod.server.getAllLevels()) {
            Entity entity = level.getEntity(companionUUID);
            if (entity instanceof AutomatonEntity) {
                return (AutomatonEntity) entity;
            }
        }
        return null;
    }

    /**
     * 检查玩家是否有伴侣
     */
    public boolean hasCompanion(UUID playerUUID) {
        return playerToCompanionMap.containsKey(playerUUID);
    }

    /**
     * 获取所有伴侣
     */
    public Map<UUID, UUID> getAllCompanions() {
        return new ConcurrentHashMap<>(playerToCompanionMap);
    }
}
