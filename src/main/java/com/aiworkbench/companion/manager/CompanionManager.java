package com.aiworkbench.companion.manager;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 伴侣管理器 —— 管理每个玩家的同伴小队（最多3个）。
 * <p>
 * 招募规则：第一个同伴初始即可拥有；后续同伴需当前所有同伴满级才能解锁。
 */
public class CompanionManager {
    /** 最大同伴数量 */
    public static final int MAX_COMPANIONS = 3;

    /** 玩家UUID → 同伴UUID列表 */
    private final Map<UUID, List<UUID>> playerToCompanionsMap = new ConcurrentHashMap<>();
    /** 同伴UUID → 玩家UUID（反向索引） */
    private final Map<UUID, UUID> companionToPlayerMap = new ConcurrentHashMap<>();
    /** 玩家UUID → 当前选中的同伴UUID */
    private final Map<UUID, UUID> activeCompanionMap = new ConcurrentHashMap<>();

    // ==================== 招募 ====================

    /**
     * 检查玩家是否可以招募新同伴。
     * 第一个同伴：随时可招募。
     * 后续同伴：需要所有已有同伴都满级。
     */
    public boolean canRecruitNewCompanion(UUID playerUUID) {
        List<UUID> squad = playerToCompanionsMap.get(playerUUID);
        if (squad == null || squad.isEmpty()) return true; // 第一个同伴
        if (squad.size() >= MAX_COMPANIONS) return false;
        // 所有已有同伴必须满级
        for (UUID id : squad) {
            AutomatonEntity c = getCompanionById(id);
            if (c == null || !c.isMaxLevel()) return false;
        }
        return true;
    }

    /** 注册同伴 */
    public void addCompanion(UUID playerUUID, AutomatonEntity companion) {
        List<UUID> squad = playerToCompanionsMap.computeIfAbsent(playerUUID, k -> new ArrayList<>());
        squad.add(companion.getUUID());
        companionToPlayerMap.put(companion.getUUID(), playerUUID);
        // 第一个同伴自动设为当前选中
        activeCompanionMap.putIfAbsent(playerUUID, companion.getUUID());
        AICompanionMod.LOGGER.info("[Squad] Added {} ({}) for player {} — squad size: {}",
            companion.getName().getString(), companion.getRole().chineseName, playerUUID, squad.size());
    }

    // ==================== 移除 ====================

    public void removeCompanion(UUID playerUUID) {
        List<UUID> squad = playerToCompanionsMap.remove(playerUUID);
        if (squad != null) {
            for (UUID id : squad) {
                companionToPlayerMap.remove(id);
                discardEntity(id);
            }
            activeCompanionMap.remove(playerUUID);
        }
    }

    public void removeCompanionById(UUID companionUUID) {
        UUID playerUUID = companionToPlayerMap.remove(companionUUID);
        if (playerUUID != null) {
            List<UUID> squad = playerToCompanionsMap.get(playerUUID);
            if (squad != null) {
                squad.remove(companionUUID);
                if (squad.isEmpty()) playerToCompanionsMap.remove(playerUUID);
            }
            if (companionUUID.equals(activeCompanionMap.get(playerUUID))) {
                // 切到下一个同伴
                activeCompanionMap.remove(playerUUID);
                if (squad != null && !squad.isEmpty()) {
                    activeCompanionMap.put(playerUUID, squad.get(0));
                }
            }
            discardEntity(companionUUID);
        }
    }

    public void removeCompanionByName(String playerName) {
        if (AICompanionMod.server == null) return;
        for (ServerPlayer player : AICompanionMod.server.getPlayerList().getPlayers()) {
            if (player.getName().getString().equals(playerName)) {
                removeCompanion(player.getUUID());
                return;
            }
        }
    }

    private void discardEntity(UUID companionUUID) {
        if (AICompanionMod.aiManager != null) AICompanionMod.aiManager.removeAI(companionUUID);
        if (AICompanionMod.server != null) {
            for (ServerLevel level : AICompanionMod.server.getAllLevels()) {
                Entity entity = level.getEntity(companionUUID);
                if (entity instanceof AutomatonEntity) {
                    entity.discard();
                    break;
                }
            }
        }
    }

    // ==================== 查询 ====================

    /** 获取玩家当前选中的同伴（兼容旧API） */
    public AutomatonEntity getCompanion(UUID playerUUID) {
        UUID activeId = activeCompanionMap.get(playerUUID);
        if (activeId != null) {
            AutomatonEntity c = getCompanionById(activeId);
            if (c != null && c.isAlive()) return c;
        }
        // fallback: 返回第一个存活的同伴
        List<UUID> squad = playerToCompanionsMap.get(playerUUID);
        if (squad != null) {
            for (UUID id : squad) {
                AutomatonEntity c = getCompanionById(id);
                if (c != null && c.isAlive()) {
                    activeCompanionMap.put(playerUUID, id);
                    return c;
                }
            }
        }
        return null;
    }

    public AutomatonEntity getCompanionById(UUID companionUUID) {
        if (companionUUID == null || AICompanionMod.server == null) return null;
        for (ServerLevel level : AICompanionMod.server.getAllLevels()) {
            Entity entity = level.getEntity(companionUUID);
            if (entity instanceof AutomatonEntity) return (AutomatonEntity) entity;
        }
        return null;
    }

    /** 获取玩家的小队UUID列表 */
    public List<UUID> getSquadIds(UUID playerUUID) {
        List<UUID> squad = playerToCompanionsMap.get(playerUUID);
        return squad != null ? new ArrayList<>(squad) : Collections.emptyList();
    }

    /** 获取玩家的小队实体列表 */
    public List<AutomatonEntity> getSquad(UUID playerUUID) {
        List<AutomatonEntity> result = new ArrayList<>();
        List<UUID> ids = playerToCompanionsMap.get(playerUUID);
        if (ids != null) {
            for (UUID id : ids) {
                AutomatonEntity c = getCompanionById(id);
                if (c != null && c.isAlive()) result.add(c);
            }
        }
        return result;
    }

    public boolean hasCompanion(UUID playerUUID) {
        List<UUID> squad = playerToCompanionsMap.get(playerUUID);
        return squad != null && !squad.isEmpty();
    }

    public int squadSize(UUID playerUUID) {
        List<UUID> squad = playerToCompanionsMap.get(playerUUID);
        return squad != null ? squad.size() : 0;
    }

    /** 切换到指定同伴 */
    public boolean setActiveCompanion(UUID playerUUID, UUID companionUUID) {
        if (companionToPlayerMap.get(companionUUID) != null
            && companionToPlayerMap.get(companionUUID).equals(playerUUID)) {
            activeCompanionMap.put(playerUUID, companionUUID);
            return true;
        }
        return false;
    }

    /** 切换到下一个同伴 */
    public AutomatonEntity cycleActiveCompanion(UUID playerUUID) {
        List<UUID> squad = playerToCompanionsMap.get(playerUUID);
        if (squad == null || squad.isEmpty()) return null;
        UUID current = activeCompanionMap.get(playerUUID);
        int idx = current != null ? squad.indexOf(current) : -1;
        idx = (idx + 1) % squad.size();
        UUID next = squad.get(idx);
        activeCompanionMap.put(playerUUID, next);
        return getCompanionById(next);
    }

    public Map<UUID, UUID> getAllCompanions() {
        Map<UUID, UUID> result = new ConcurrentHashMap<>();
        for (var entry : playerToCompanionsMap.entrySet()) {
            for (UUID id : entry.getValue()) {
                result.put(entry.getKey(), id);
            }
        }
        return result;
    }
}
