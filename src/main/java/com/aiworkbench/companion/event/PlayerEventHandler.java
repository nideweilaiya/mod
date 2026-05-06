package com.aiworkbench.companion.event;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.manager.CompanionManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
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

                // 手持食物治疗同伴
                net.minecraft.world.item.ItemStack heldItem = player.getItemInHand(event.getHand());
                if (!heldItem.isEmpty() && companion.getHealth() < companion.getMaxHealth()) {
                    int healAmount = getHealAmount(heldItem);
                    if (healAmount > 0) {
                        companion.heal(healAmount);
                        companion.playHealSound();
                        if (!player.isCreative()) {
                            heldItem.shrink(1);
                            // 蘑菇煲等需要返还碗
                            if (heldItem.getItem() == net.minecraft.world.item.Items.MUSHROOM_STEW ||
                                heldItem.getItem() == net.minecraft.world.item.Items.RABBIT_STEW ||
                                heldItem.getItem() == net.minecraft.world.item.Items.BEETROOT_SOUP ||
                                heldItem.getItem() == net.minecraft.world.item.Items.SUSPICIOUS_STEW) {
                                player.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOWL));
                            }
                        }
                        String msg = "§a+" + healAmount + " HP §7(" + (int)companion.getHealth() + "/" + (int)companion.getMaxHealth() + ")";
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg));
                        event.setCanceled(true);
                        return;
                    }
                }

                // 通知 Python 后端（两种通道）
                String companionId = companion.getUUID().toString();
                String playerName = player.getName().getString();

                if (AICompanionMod.tcpServer != null) {
                    AICompanionMod.tcpServer.onPlayerInteract(companionId, playerName, "right_click");
                }
                if (AICompanionMod.bridgeClient != null && AICompanionMod.bridgeClient.isConnected()) {
                    AICompanionMod.bridgeClient.sendPlayerInteract(companionId, playerName, "right_click");
                }

                // 打开同伴背包（通过聊天消息触发客户端打开GUI）
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[OPEN_INVENTORY]"));

                // 标记事件已处理
                event.setCanceled(true);
            }
        }
    }

    /**
     * 玩家被攻击时，同伴自动反击（无论当前模式）
     */
    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide) return;

        AutomatonEntity companion = AICompanionMod.companionManager != null
            ? AICompanionMod.companionManager.getCompanion(player.getUUID()) : null;
        if (companion == null || !companion.isAlive()) return;

        // Get the attacker
        net.minecraft.world.entity.Entity sourceEntity = event.getSource().getEntity();
        if (!(sourceEntity instanceof LivingEntity attacker)) return;
        if (attacker == companion) return; // Don't fight self

        // Make companion defend for 5 seconds (100 ticks)
        companion.setAutoDefendTarget(attacker, 100);
        companion.showDialogue("§c保护主人！", 40);
    }

    /**
     * 同伴击杀生物时获得经验值
     */
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide) return;

        // 检查击杀者是否是同伴
        net.minecraft.world.entity.Entity killer = event.getSource().getEntity();
        if (!(killer instanceof AutomatonEntity companion)) return;
        if (!companion.isAlive()) return;

        int xp = getXpValue(event.getEntity());
        if (xp > 0) {
            companion.grantXp(xp);
        }
    }

    /**
     * 玩家切换维度时，立即传送同伴到新维度
     */
    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        CompanionManager manager = AICompanionMod.companionManager;
        if (manager == null) return;

        AutomatonEntity companion = manager.getCompanion(player.getUUID());
        if (companion == null || !companion.isAlive()) return;

        // Companion is already in a different dimension from the player
        if (!companion.level().dimension().equals(player.level().dimension())) {
            ServerLevel newLevel = player.serverLevel();
            companion.teleportTo(newLevel, player.getX(), player.getY(), player.getZ(),
                java.util.Set.of(), player.getYRot(), player.getXRot());
            companion.setDeltaMovement(0, 0, 0);
            companion.fallDistance = 0;
            companion.showDialogue("§d跟主人穿越维度！", 80);
            AICompanionMod.LOGGER.info("[CrossDim] Companion teleported to player {} in new dimension {}",
                player.getName().getString(), newLevel.dimension().location());
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
     * 计算手持物品对同伴的治疗量
     * @return 治疗 HP 量，0 表示不能治疗
     */
    private int getHealAmount(net.minecraft.world.item.ItemStack stack) {
        net.minecraft.world.item.Item item = stack.getItem();
        // 金苹果类
        if (item == net.minecraft.world.item.Items.GOLDEN_APPLE) return 20;
        if (item == net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE) return 120;
        // 金胡萝卜 — 特殊食物，高营养
        if (item == net.minecraft.world.item.Items.GOLDEN_CARROT) return 12;
        // 其他食物按营养值*2
        net.minecraft.world.food.FoodProperties food = item.getFoodProperties(stack, null);
        if (food != null) {
            return food.getNutrition() * 2;
        }
        return 0;
    }

    /**
     * 在所有维度查找属于指定玩家的伴侣实体（跨维度搜索）
     */
    private AutomatonEntity findCompanionByOwner(ServerLevel level, UUID ownerUUID) {
        if (AICompanionMod.server == null) {
            // Fallback to single-dimension search
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
        // Search all dimensions
        for (ServerLevel sl : AICompanionMod.server.getAllLevels()) {
            for (AutomatonEntity entity : sl.getEntitiesOfClass(AutomatonEntity.class,
                    new net.minecraft.world.phys.AABB(
                        Double.NEGATIVE_INFINITY, -64, Double.NEGATIVE_INFINITY,
                        Double.POSITIVE_INFINITY, 320, Double.POSITIVE_INFINITY))) {
                if (entity.getOwnerUUID() != null && entity.getOwnerUUID().equals(ownerUUID)) {
                    AICompanionMod.LOGGER.info("findCompanionByOwner found companion {} at {} in dimension {} for owner {}",
                        entity.getUUID(), entity.position(), sl.dimension().location(), ownerUUID);
                    return entity;
                }
            }
        }
        return null;
    }

    /**
     * 根据生物类型计算经验值 — 使用注册名避免 mapping 差异
     */
    private int getXpValue(LivingEntity entity) {
        net.minecraft.resources.ResourceLocation id =
            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (id == null) return 0;
        String path = id.getPath();

        // Boss
        if ("ender_dragon".equals(path)) return 500;
        if ("wither".equals(path)) return 300;

        // Mini-boss / tough mobs
        if ("elder_guardian".equals(path)) return 100;
        if ("ravager".equals(path)) return 60;
        if ("evoker".equals(path)) return 50;
        if ("vindicator".equals(path)) return 30;
        if ("piglin_brute".equals(path)) return 25;
        if ("witch".equals(path)) return 20;

        // Standard hostile mobs
        if ("creeper".equals(path)) return 15;
        if ("zombie".equals(path)) return 10;
        if ("skeleton".equals(path)) return 10;
        if ("spider".equals(path)) return 10;
        if ("enderman".equals(path)) return 25;
        if ("blaze".equals(path)) return 20;
        if ("ghast".equals(path)) return 30;
        if ("magma_cube".equals(path)) return 10;
        if ("slime".equals(path)) return 5;
        if ("husk".equals(path)) return 12;
        if ("stray".equals(path)) return 12;
        if ("drowned".equals(path)) return 12;
        if ("phantom".equals(path)) return 20;
        if ("hoglin".equals(path)) return 20;
        if ("zoglin".equals(path)) return 15;
        if ("piglin".equals(path)) return 10;

        // Other Monster interface implementations get default
        if (entity instanceof net.minecraft.world.entity.monster.Monster) return 8;

        // Passive mobs grant no XP
        return 0;
    }
}
