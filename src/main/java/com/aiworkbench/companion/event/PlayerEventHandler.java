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
     * EntityJoinLevelEvent 在单人游戏玩家加入时不触发，改用 PlayerLoggedInEvent
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        UUID playerId = player.getUUID();

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

        // 同伴已死亡或不存在，需要重新生成
        if (spawnedPlayers.contains(playerId)) {
            AICompanionMod.LOGGER.info("Player {} had a companion but it died, respawning", player.getName().getString());
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

        // 先移除旧的同伴实体
        CompanionManager manager = AICompanionMod.companionManager;
        if (manager != null) {
            manager.removeCompanion(playerId);
        }
        spawnedPlayers.remove(playerId);

        // 重新生成同伴
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

            boolean success = level.addFreshEntity(companion);
            AICompanionMod.LOGGER.info("addFreshEntity result: {}, companion spawned", success);

            if (manager != null) {
                manager.addCompanion(playerId, companion);
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
        for (AutomatonEntity entity : level.getEntitiesOfClass(AutomatonEntity.class,
                net.minecraft.world.phys.AABB.ofSize(net.minecraft.core.BlockPos.containing(0, 64, 0).getCenter(), 200, 384, 200))) {
            if (entity.getOwnerUUID() != null && entity.getOwnerUUID().equals(ownerUUID)) {
                return entity;
            }
        }
        return null;
    }
}
