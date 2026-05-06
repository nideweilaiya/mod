package com.aiworkbench.companion.ai;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * Perception Engine for Companion AI
 *
 * Provides real-time environmental awareness:
 * - Block scan: 8 block radius (mineable blocks, dangers)
 * - Entity scan: 16 block radius (players, mobs, animals)
 * - Danger detection: lava, fall, suffocation, hostile mobs
 * - Perception push: every 200ms
 */
public class PerceptionEngine {
    // Scan radii
    private static final int BLOCK_SCAN_RADIUS = 8;
    private static final int ENTITY_SCAN_RADIUS = 16;
    private static final int DANGER_SCAN_RADIUS = 6;

    // Danger thresholds
    private static final double LOW_HEALTH_THRESHOLD = 6.0; // 3 hearts
    private static final double FALL_DAMAGE_HEIGHT = 3.0;

    /**
     * Perception data for a companion
     */
    public static class PerceptionData {
        public String companionId;
        public BlockPos position;
        public long timestamp;
        public double health;

        // Scanned data
        public List<String> nearbyBlocks = new ArrayList<>();
        public List<String> nearbyEntities = new ArrayList<>();
        public List<String> resources = new ArrayList<>();

        // Danger flags
        public boolean dangerLava = false;
        public boolean dangerFire = false;
        public boolean dangerFall = false;
        public boolean dangerHostile = false;
        public boolean dangerSuffocation = false;
        public boolean dangerLowHealth = false;

        // Urgency level
        public String urgency = "normal"; // "low", "normal", "high", "critical"

    private static final Gson GSON = new GsonBuilder().create();

    /**
     * Represent the urgency level for JSON output
     */
    private static class DangerFlags {
        boolean lava;
        boolean fire;
        boolean fall;
        boolean hostile;
        boolean suffocation;
        boolean low_health;
    }

    /**
     * JSON structure for perception data
     */
    private static class PerceptionDataJson {
        String companion_id;
        int x, y, z;
        double health;
        String urgency;
        DangerFlags danger;
        java.util.List<String> blocks = new java.util.ArrayList<>();
        java.util.List<String> entities = new java.util.ArrayList<>();
        java.util.List<String> resources = new java.util.ArrayList<>();
    }

    /**
     * Serialize perception data to JSON using Gson.
     * Previous version used manual String concatenation which produced invalid JSON
     * when block names or entity names contained special characters.
     */
    public String toJson() {
        PerceptionDataJson json = new PerceptionDataJson();
        json.companion_id = this.companionId;
        if (this.position != null) {
            json.x = this.position.getX();
            json.y = this.position.getY();
            json.z = this.position.getZ();
        }
        json.health = this.health;
        json.urgency = this.urgency;

        DangerFlags df = new DangerFlags();
        df.lava = this.dangerLava;
        df.fire = this.dangerFire;
        df.fall = this.dangerFall;
        df.hostile = this.dangerHostile;
        df.suffocation = this.dangerSuffocation;
        df.low_health = this.dangerLowHealth;
        json.danger = df;

        json.blocks = this.nearbyBlocks;
        json.entities = this.nearbyEntities;
        json.resources = this.resources;

        return GSON.toJson(json);
    }
    }

    /**
     * Scan blocks around a position
     */
    public static List<String> scanBlocks(ServerLevel level, BlockPos center, int radius) {
        List<String> blocks = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    net.minecraft.world.level.block.Block block = state.getBlock();

                    // Skip air
                    if (block == Blocks.AIR) {
                        continue;
                    }

                    String blockName = block.builtInRegistryHolder().key().location().toString();

                    // Check if dangerous
                    if (isDangerous(block)) {
                        blocks.add("danger:" + blockName);
                    }

                    // Check if resource
                    if (isResource(block)) {
                        blocks.add("resource:" + blockName);
                    }
                }
            }
        }
        return blocks;
    }

    private static boolean isDangerous(net.minecraft.world.level.block.Block block) {
        return block == Blocks.LAVA ||
               block == Blocks.MAGMA_BLOCK ||
               block == Blocks.FIRE ||
               block == Blocks.CAMPFIRE ||
               block == Blocks.SOUL_CAMPFIRE ||
               block == Blocks.CACTUS ||
               block == Blocks.SWEET_BERRY_BUSH ||
               block == Blocks.WITHER_ROSE ||
               block == Blocks.POWDER_SNOW;
    }

    private static boolean isResource(net.minecraft.world.level.block.Block block) {
        return block == Blocks.COAL_ORE ||
               block == Blocks.IRON_ORE ||
               block == Blocks.COPPER_ORE ||
               block == Blocks.GOLD_ORE ||
               block == Blocks.DIAMOND_ORE ||
               block == Blocks.EMERALD_ORE ||
               block == Blocks.LAPIS_ORE ||
               block == Blocks.REDSTONE_ORE ||
               block == Blocks.DEEPSLATE_COAL_ORE ||
               block == Blocks.DEEPSLATE_IRON_ORE ||
               block == Blocks.DEEPSLATE_COPPER_ORE ||
               block == Blocks.DEEPSLATE_GOLD_ORE ||
               block == Blocks.DEEPSLATE_DIAMOND_ORE ||
               block == Blocks.DEEPSLATE_EMERALD_ORE ||
               block == Blocks.DEEPSLATE_LAPIS_ORE ||
               block == Blocks.DEEPSLATE_REDSTONE_ORE ||
               block == Blocks.OAK_LOG ||
               block == Blocks.SPRUCE_LOG ||
               block == Blocks.BIRCH_LOG ||
               block == Blocks.JUNGLE_LOG ||
               block == Blocks.DARK_OAK_LOG ||
               block == Blocks.ACACIA_LOG ||
               block == Blocks.COBBLESTONE ||
               block == Blocks.STONE ||
               block == Blocks.DIRT ||
               block == Blocks.GRAVEL ||
               block == Blocks.SAND;
    }

    /**
     * Scan entities around a position
     */
    public static List<String> scanEntities(ServerLevel level, BlockPos center, int radius) {
        List<String> entities = new ArrayList<>();
        AABB searchBox = new AABB(center).inflate(radius);
        List<Entity> nearbyEntities = level.getEntities(null, searchBox);

        for (Entity entity : nearbyEntities) {
            String entityName = entity.getType().builtInRegistryHolder().key().location().toString();

            if (entity instanceof Monster) {
                entities.add("hostile:" + entityName);
            } else if (entity instanceof Animal) {
                entities.add("animal:" + entityName);
            } else if (entity instanceof LivingEntity) {
                entities.add("living:" + entityName);
            } else {
                entities.add("entity:" + entityName);
            }
        }

        return entities;
    }

    /**
     * Detect dangers around a position
     */
    public static PerceptionData detectDangers(AutomatonEntity companion) {
        PerceptionData data = new PerceptionData();
        Level level = companion.level();

        if (!(level instanceof ServerLevel)) {
            return data;
        }

        ServerLevel serverLevel = (ServerLevel) level;
        BlockPos pos = companion.blockPosition();

        data.companionId = companion.getUUID().toString();
        data.position = pos;
        data.timestamp = System.currentTimeMillis();
        data.health = companion.getHealth();

        // Check lava
        for (int dx = -DANGER_SCAN_RADIUS; dx <= DANGER_SCAN_RADIUS; dx++) {
            for (int dy = -DANGER_SCAN_RADIUS; dy <= DANGER_SCAN_RADIUS; dy++) {
                for (int dz = -DANGER_SCAN_RADIUS; dz <= DANGER_SCAN_RADIUS; dz++) {
                    BlockPos checkPos = pos.offset(dx, dy, dz);
                    BlockState state = serverLevel.getBlockState(checkPos);

                    if (state.getBlock() == Blocks.LAVA || state.getBlock() == Blocks.MAGMA_BLOCK) {
                        data.dangerLava = true;
                    }
                    if (state.getBlock() == Blocks.FIRE || state.getBlock() == Blocks.CAMPFIRE || state.getBlock() == Blocks.SOUL_CAMPFIRE) {
                        data.dangerFire = true;
                    }
                }
            }
        }

        // Check hostile mobs
        AABB hostileBox = new AABB(pos).inflate(ENTITY_SCAN_RADIUS);
        List<Mob> hostileMobs = serverLevel.getEntitiesOfClass(Mob.class, hostileBox,
            mob -> mob instanceof Monster && mob.distanceToSqr(companion) <= ENTITY_SCAN_RADIUS * ENTITY_SCAN_RADIUS);
        data.dangerHostile = !hostileMobs.isEmpty();

        // Check suffocation (inside solid block - using bounding box check)
        BlockPos below = pos.below();
        BlockState belowState = serverLevel.getBlockState(below);
        boolean belowIsAir = belowState.isAir();
        if (!belowIsAir && companion.fallDistance == 0 && companion.onGround()) {
            // If not moving and surrounded by solid, might be suffocating
            BlockState aboveState = serverLevel.getBlockState(pos);
            if (!aboveState.isAir()) {
                data.dangerSuffocation = true;
            }
        }

        // Check fall damage
        if (companion.fallDistance > FALL_DAMAGE_HEIGHT && belowIsAir) {
            data.dangerFall = true;
        }

        // Check low health
        if (data.health <= LOW_HEALTH_THRESHOLD) {
            data.dangerLowHealth = true;
        }

        // Determine urgency
        if (data.dangerLava || data.dangerFall || data.dangerLowHealth) {
            data.urgency = "critical";
        } else if (data.dangerHostile || data.dangerSuffocation) {
            data.urgency = "high";
        } else if (data.dangerFire) {
            data.urgency = "normal";
        } else {
            data.urgency = "low";
        }

        return data;
    }

    /**
     * Gather full perception data for a companion
     *
     * 【O(n³) 扫描合并优化】
     * 原本 detectDangers() 和 scanBlocks() 各自独立扫描方块区域，
     * detectDangers 扫 DANGER_SCAN_RADIUS(6) = 13³ = 2197 个位置，
     * scanBlocks 扫 BLOCK_SCAN_RADIUS(8) = 17³ = 4913 个位置。
     * 合并后一次扫描 4913 位置，节省 2197 次冗余迭代。
     *
     * detectDangers() 保留不动（兼容外部调用），
     * 但 gatherPerception 不再调用它，直接内联方块扫描。
     */
    public static PerceptionData gatherPerception(AutomatonEntity companion) {
        PerceptionData data = new PerceptionData();
        Level level = companion.level();

        if (!(level instanceof ServerLevel serverLevel)) {
            return data;
        }

        BlockPos pos = companion.blockPosition();
        data.companionId = companion.getUUID().toString();
        data.position = pos;
        data.timestamp = System.currentTimeMillis();
        data.health = companion.getHealth();

        // ===== 单次方块扫描（合并 detectDangers 的方块检测 + scanBlocks）=====
        List<String> blockScan = new ArrayList<>();
        List<String> resourceList = new ArrayList<>();

        for (int dx = -BLOCK_SCAN_RADIUS; dx <= BLOCK_SCAN_RADIUS; dx++) {
            for (int dy = -BLOCK_SCAN_RADIUS; dy <= BLOCK_SCAN_RADIUS; dy++) {
                for (int dz = -BLOCK_SCAN_RADIUS; dz <= BLOCK_SCAN_RADIUS; dz++) {
                    BlockPos checkPos = pos.offset(dx, dy, dz);
                    BlockState state = serverLevel.getBlockState(checkPos);
                    Block block = state.getBlock();

                    if (block == Blocks.AIR) continue;

                    String blockName = block.builtInRegistryHolder().key().location().toString();

                    // 危险方块检测（内联自 detectDangers，按 DANGER_SCAN_RADIUS 过滤）
                    if (Math.abs(dx) <= DANGER_SCAN_RADIUS
                        && Math.abs(dy) <= DANGER_SCAN_RADIUS
                        && Math.abs(dz) <= DANGER_SCAN_RADIUS) {
                        if (block == Blocks.LAVA || block == Blocks.MAGMA_BLOCK) {
                            data.dangerLava = true;
                        }
                        if (block == Blocks.FIRE || block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE) {
                            data.dangerFire = true;
                        }
                    }

                    // 方块分类（同 scanBlocks 逻辑）
                    if (isDangerous(block)) {
                        blockScan.add("danger:" + blockName);
                    } else if (isResource(block)) {
                        blockScan.add("resource:" + blockName);
                        resourceList.add(blockName);
                    }
                }
            }
        }

        data.nearbyBlocks = blockScan;
        data.resources = resourceList;

        // ===== 实体扫描（不变） =====
        data.nearbyEntities = scanEntities(serverLevel, pos, ENTITY_SCAN_RADIUS);

        // ===== 非方块危险检测（原 detectDangers 的实体/坠落/窒息/血量部分）=====
        // 敌对生物
        AABB hostileBox = new AABB(pos).inflate(ENTITY_SCAN_RADIUS);
        List<Mob> hostileMobs = serverLevel.getEntitiesOfClass(Mob.class, hostileBox,
            mob -> mob instanceof Monster
                && mob.distanceToSqr(companion) <= ENTITY_SCAN_RADIUS * ENTITY_SCAN_RADIUS);
        data.dangerHostile = !hostileMobs.isEmpty();

        // 窒息
        BlockPos below = pos.below();
        BlockState belowState = serverLevel.getBlockState(below);
        boolean belowIsAir = belowState.isAir();
        if (!belowIsAir && companion.fallDistance == 0 && companion.onGround()) {
            if (!serverLevel.getBlockState(pos).isAir()) {
                data.dangerSuffocation = true;
            }
        }

        // 坠落
        if (companion.fallDistance > FALL_DAMAGE_HEIGHT && belowIsAir) {
            data.dangerFall = true;
        }

        // 低血量
        if (data.health <= LOW_HEALTH_THRESHOLD) {
            data.dangerLowHealth = true;
        }

        // ===== 紧急度判定（同 detectDangers 逻辑）=====
        if (data.dangerLava || data.dangerFall || data.dangerLowHealth) {
            data.urgency = "critical";
        } else if (data.dangerHostile || data.dangerSuffocation) {
            data.urgency = "high";
        } else if (data.dangerFire) {
            data.urgency = "normal";
        } else {
            data.urgency = "low";
        }

        return data;
    }

    /**
     * Check if entity is threatening owner (companion's owner UUID)
     */
    public static boolean isThreateningOwner(AutomatonEntity companion) {
        UUID ownerUUID = companion.getOwnerUUID();
        if (ownerUUID == null) return false;

        Level level = companion.level();
        if (!(level instanceof ServerLevel)) return false;

        ServerLevel serverLevel = (ServerLevel) level;
        BlockPos companionPos = companion.blockPosition();

        // Find owner position from players
        for (ServerLevel level2 : serverLevel.getServer().getAllLevels()) {
            for (net.minecraft.server.level.ServerPlayer player : level2.getServer().getPlayerList().getPlayers()) {
                if (player.getUUID().equals(ownerUUID)) {
                    // Owner found, check for hostile mobs near them
                    BlockPos ownerPos = player.blockPosition();
                    double threatRange = 16.0;
                    AABB threatBox = new AABB(ownerPos).inflate(threatRange);
                    List<Mob> nearbyHostiles = serverLevel.getEntitiesOfClass(Mob.class, threatBox,
                        mob -> mob instanceof Monster && mob.distanceToSqr(player) <= threatRange * threatRange);
                    return !nearbyHostiles.isEmpty();
                }
            }
        }

        return false;
    }
}
