package com.aiworkbench.companion.ai;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
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

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"companion_id\":\"").append(companionId).append("\",");
            sb.append("\"x\":").append(position.getX()).append(",");
            sb.append("\"y\":").append(position.getY()).append(",");
            sb.append("\"z\":").append(position.getZ()).append(",");
            sb.append("\"health\":").append(health).append(",");
            sb.append("\"urgency\":\"").append(urgency).append("\",");
            sb.append("\"danger\":{");
            sb.append("\"lava\":").append(dangerLava).append(",");
            sb.append("\"fire\":").append(dangerFire).append(",");
            sb.append("\"fall\":").append(dangerFall).append(",");
            sb.append("\"hostile\":").append(dangerHostile).append(",");
            sb.append("\"suffocation\":").append(dangerSuffocation).append(",");
            sb.append("\"low_health\":").append(dangerLowHealth);
            sb.append("},");
            sb.append("\"blocks\":[");
            for (int i = 0; i < nearbyBlocks.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(nearbyBlocks.get(i)).append("\"");
            }
            sb.append("],");
            sb.append("\"entities\":[");
            for (int i = 0; i < nearbyEntities.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(nearbyEntities.get(i)).append("\"");
            }
            sb.append("],");
            sb.append("\"resources\":[");
            for (int i = 0; i < resources.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(resources.get(i)).append("\"");
            }
            sb.append("]");
            sb.append("}");
            return sb.toString();
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
     */
    public static PerceptionData gatherPerception(AutomatonEntity companion) {
        PerceptionData data = detectDangers(companion);
        Level level = companion.level();

        if (level instanceof ServerLevel) {
            ServerLevel serverLevel = (ServerLevel) level;
            BlockPos pos = companion.blockPosition();

            // Scan blocks
            List<String> blockScan = scanBlocks(serverLevel, pos, BLOCK_SCAN_RADIUS);
            data.nearbyBlocks = blockScan;

            // Extract resources
            List<String> resourceList = new ArrayList<>();
            for (String block : blockScan) {
                if (block.startsWith("resource:")) {
                    resourceList.add(block.substring(9));
                }
            }
            data.resources = resourceList;

            // Scan entities
            data.nearbyEntities = scanEntities(serverLevel, pos, ENTITY_SCAN_RADIUS);
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
