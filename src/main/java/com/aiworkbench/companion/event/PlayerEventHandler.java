package com.aiworkbench.companion.event;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.client.gui.CompanionInventoryScreen;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.manager.CompanionManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家事件处理器
 * 自动为每个加入的玩家生成同伴NPC
 */
public class PlayerEventHandler {

    // 记录已生成同伴的玩家，避免重复
    private final Set<UUID> spawnedPlayers = new HashSet<>();

    /**
     * 服务器启动时清理，避免重启后NPC不生成
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        spawnedPlayers.clear();
        AICompanionMod.LOGGER.info("Cleared spawnedPlayers tracking for new server session");
    }

    /**
     * 玩家登录时触发（单人游戏和服务器都适用）
     * 使用标志防止重复生成
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        UUID playerId = player.getUUID();

        // 防止重复生成
        if (spawnedPlayers.contains(playerId)) {
            return;
        }

        AICompanionMod.LOGGER.info("Player {} logged in, spawning companion NPC", player.getName().getString());

        // 检查是否已有同伴，且同伴还活着
        CompanionManager manager = AICompanionMod.companionManager;
        if (manager != null) {
            AutomatonEntity existing = manager.getCompanion(playerId);
            if (existing != null && existing.isAlive()) {
                AICompanionMod.LOGGER.info("Player {} already has a living companion {}, skipping spawn",
                    player.getName().getString(), existing.getUUID());
                spawnedPlayers.add(playerId);
                return;
            }
        }

        // 为玩家生成同伴NPC
        spawnCompanionForPlayer(player);

        // 标记该玩家已生成
        spawnedPlayers.add(playerId);
    }

    /**
     * 玩家重新加入世界时也触发（如传送、切维度、死亡重生）
     */
    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        UUID playerId = player.getUUID();

        AICompanionMod.LOGGER.info("Player {} respawned, handling companion", player.getName().getString());

        // 死亡重生时移除旧实体（切维度时不移除）
        // 通过检查实体位置是否在主世界来判断（简化的方式）
        CompanionManager manager = AICompanionMod.companionManager;
        if (manager != null) {
            AutomatonEntity existing = manager.getCompanion(playerId);
            if (existing != null && !existing.isAlive()) {
                manager.removeCompanion(playerId);
                AICompanionMod.LOGGER.info("Player {} died, removing dead companion", player.getName().getString());
            }
        }
        spawnedPlayers.remove(playerId);

        // 重新生成同伴（如果需要）
        spawnCompanionForPlayer(player);
        spawnedPlayers.add(playerId);
    }

    /**
     * 玩家右键点击同伴实体时触发 - 打开背包GUI
     */
    @SubscribeEvent
    public void onPlayerInteract(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getTarget() == null) return;

        // 检查是否点击了 AutomatonEntity（同伴）
        if (event.getTarget() instanceof AutomatonEntity companion) {
            // 检查是否是同伴的主人
            if (companion.getOwnerUUID() != null && companion.getOwnerUUID().equals(player.getUUID())) {
                AICompanionMod.LOGGER.info("Player {} right-clicked companion", player.getName().getString());

                // 发送消息提示玩家按C打开背包
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("按 C 键打开同伴背包"));

                // 标记事件已处理
                event.setCanceled(true);
            }
        }
    }

    /**
     * 玩家离开时不删除实体（让实体保持在世界中，保留NBT数据）
     */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        UUID playerId = player.getUUID();
        spawnedPlayers.remove(playerId);
        AICompanionMod.LOGGER.info("Player {} logged out, companion entity kept in world", player.getName().getString());

        // Notify Python client via TCP
        if (AICompanionMod.tcpServer != null) {
            AICompanionMod.tcpServer.onPlayerQuit(player.getName().getString());
        }
        // Notify Bridge Client
        if (AICompanionMod.bridgeClient != null && AICompanionMod.bridgeClient.isConnected()) {
            AICompanionMod.bridgeClient.sendCompanionRemoved(
                AICompanionMod.companionManager.getCompanion(playerId) != null
                    ? AICompanionMod.companionManager.getCompanion(playerId).getUUID().toString()
                    : ""
            );
        }
    }

    /**
     * 为玩家生成同伴NPC
     */
    private void spawnCompanionForPlayer(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        UUID playerId = player.getUUID();
        CompanionManager manager = AICompanionMod.companionManager;

        try {
            // 先在世界中查找是否已存在属于该玩家的伴侣实体（从NBT加载的）
            AutomatonEntity existingInWorld = findCompanionByOwner(level, playerId);
            if (existingInWorld != null && existingInWorld.isAlive()) {
                AICompanionMod.LOGGER.info("Player {} found existing companion {} in world, re-registering",
                    player.getName().getString(), existingInWorld.getUUID());
                if (manager != null) {
                    manager.addCompanion(playerId, existingInWorld);
                    AICompanionMod.LOGGER.info("Re-registered existing companion. Manager now has: {}",
                        manager.getCompanion(playerId) != null ? "VALID" : "NULL");
                }
                return;
            }

            // 检查manager中是否已有有效实体
            if (manager != null) {
                AutomatonEntity existing = manager.getCompanion(playerId);
                if (existing != null && existing.isAlive()) {
                    AICompanionMod.LOGGER.info("Player {} already has a valid companion {}, skipping spawn",
                        player.getName().getString(), existing.getUUID());
                    return;
                }
            }

            // 创建新实体
            AutomatonEntity companion = AutomatonEntity.create(
                level,
                "default_companion",
                player
            );

            AICompanionMod.LOGGER.info("Created companion entity with UUID: {}, position: {}",
                companion.getUUID(), companion.position());

            boolean success = level.addFreshEntity(companion);
            AICompanionMod.LOGGER.info("addFreshEntity result: {}, companion spawned at {}",
                success, companion.position());

            // Ensure health is set after entity is in world
            companion.setHealth(companion.getMaxHealth());
            AICompanionMod.LOGGER.info("Companion health set to {} (max: {})",
                companion.getHealth(), companion.getMaxHealth());

            if (manager != null) {
                manager.addCompanion(playerId, companion);
                // Verify registration
                AutomatonEntity verify = manager.getCompanion(playerId);
                AICompanionMod.LOGGER.info("After addCompanion, getCompanion returns: {}",
                    verify != null ? verify.getUUID().toString() : "NULL");
            } else {
                AICompanionMod.LOGGER.error("companionManager is NULL during spawn!");
            }

            // Notify Python client via TCP
            if (AICompanionMod.tcpServer != null) {
                AICompanionMod.tcpServer.onCompanionSpawned(
                    companion.getUUID().toString(),
                    player.getName().getString(),
                    companion.blockPosition(),
                    level
                );
            }
            // Notify Bridge Client
            if (AICompanionMod.bridgeClient != null && AICompanionMod.bridgeClient.isConnected()) {
                AICompanionMod.bridgeClient.sendCompanionSpawned(
                    companion.getUUID().toString(),
                    player.getName().getString(),
                    companion.blockPosition()
                );
            }
        } catch (Exception e) {
            AICompanionMod.LOGGER.error("Failed to spawn companion for {}: {}", player.getName().getString(), e.getMessage(), e);
        }
    }

    /**
     * 在世界查找属于指定玩家的伴侣实体
     */
    private AutomatonEntity findCompanionByOwner(ServerLevel level, UUID ownerUUID) {
        // Search the entire world for the owner's companion
        for (AutomatonEntity entity : level.getEntitiesOfClass(AutomatonEntity.class,
                new net.minecraft.world.phys.AABB(
                    Double.NEGATIVE_INFINITY, -64, Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY, 320, Double.POSITIVE_INFINITY))) {
            if (entity.getOwnerUUID() != null && entity.getOwnerUUID().equals(ownerUUID)) {
                AICompanionMod.LOGGER.info("findCompanionByOwner found companion {} at {} for owner {}",
                    entity.getUUID(), entity.position(), ownerUUID);
                return entity;
            }
        }
        return null;
    }
}
