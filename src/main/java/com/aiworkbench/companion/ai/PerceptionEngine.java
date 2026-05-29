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
     * 生成结构化的感知数据（供 core.decision 层使用）。
     *
     * <p>与旧版 gatherPerception() 的区别：
     * <ul>
     *   <li>nearbyBlocks 从 List&lt;String&gt; 升级为 List&lt;NearbyBlock&gt;，附带坐标和距离</li>
     *   <li>nearbyEntities 从 List&lt;String&gt; 升级为 List&lt;NearbyEntity&gt;，附带 UUID 和位置</li>
     *   <li>新增 inventorySummary 和 threats 字段</li>
     * </ul>
     *
     * <p>旧版 gatherPerception() 保持不变，供现有调用方继续使用。</p>
     */
    public static com.aiworkbench.companion.core.perception.PerceptionData gatherStructured(AutomatonEntity companion) {
        com.aiworkbench.companion.core.perception.PerceptionData data =
            new com.aiworkbench.companion.core.perception.PerceptionData();

        if (!(companion.level() instanceof ServerLevel serverLevel)) {
            return data;
        }

        BlockPos pos = companion.blockPosition();
        data.scanTimestamp = System.currentTimeMillis();

        // ---- 自身状态 ----
        data.self = new com.aiworkbench.companion.core.perception.PerceptionData.SelfStatus(
            companion.getHealth(),
            companion.getHungerLevel(),
            pos,
            "[companion]"
        );

        // ---- 方块扫描（复用现有 O(n³) 合并扫描逻辑）----
        List<com.aiworkbench.companion.core.perception.PerceptionData.NearbyBlock> blocks = new ArrayList<>();
        for (int dx = -BLOCK_SCAN_RADIUS; dx <= BLOCK_SCAN_RADIUS; dx++) {
            for (int dy = -BLOCK_SCAN_RADIUS; dy <= BLOCK_SCAN_RADIUS; dy++) {
                for (int dz = -BLOCK_SCAN_RADIUS; dz <= BLOCK_SCAN_RADIUS; dz++) {
                    BlockPos checkPos = pos.offset(dx, dy, dz);
                    BlockState state = serverLevel.getBlockState(checkPos);
                    Block block = state.getBlock();
                    if (block == Blocks.AIR) continue;

                    String blockType = block.builtInRegistryHolder().key().location().getPath();
                    double distance = Math.sqrt(pos.distSqr(checkPos));
                    // 可达性：略过，由 MoveToAction 执行时精确检查
                    boolean isReachable = distance <= 6.0;

                    blocks.add(new com.aiworkbench.companion.core.perception.PerceptionData.NearbyBlock(
                        blockType, checkPos.immutable(), distance, isReachable
                    ));
                }
            }
        }
        // 按距离排序
        blocks.sort(java.util.Comparator.comparingDouble(b -> b.distance()));
        data.nearbyBlocks = blocks;

        // ---- 实体扫描 ----
        List<com.aiworkbench.companion.core.perception.PerceptionData.NearbyEntity> entities = new ArrayList<>();
        List<com.aiworkbench.companion.core.perception.PerceptionData.NearbyEntity> threats = new ArrayList<>();

        AABB entityBox = new AABB(pos).inflate(ENTITY_SCAN_RADIUS);
        for (Entity e : serverLevel.getEntities(null, entityBox)) {
            if (e == companion) continue;

            String entityType = e.getType().builtInRegistryHolder().key().location().getPath();
            double distance = Math.sqrt(pos.distSqr(e.blockPosition()));
            boolean isHostile = e instanceof Monster;

            var entry = new com.aiworkbench.companion.core.perception.PerceptionData.NearbyEntity(
                entityType, e.getUUID(), e.blockPosition(), distance, isHostile
            );
            entities.add(entry);
            if (isHostile && distance <= ENTITY_SCAN_RADIUS) {
                threats.add(entry);
            }
        }
        entities.sort(java.util.Comparator.comparingDouble(e -> e.distance()));
        threats.sort(java.util.Comparator.comparingDouble(t -> t.distance()));
        data.nearbyEntities = entities;
        data.threats = threats;

        // ---- 背包摘要 ----
        java.util.Map<String, Integer> invSummary = new java.util.LinkedHashMap<>();
        for (int i = 0; i < companion.getInventorySize(); i++) {
            net.minecraft.world.item.ItemStack stack = companion.getItem(i);
            if (!stack.isEmpty()) {
                String name = stack.getItem().builtInRegistryHolder().key().location().getPath();
                invSummary.merge(name, stack.getCount(), Integer::sum);
            }
        }
        data.inventorySummary = invSummary;

        // ---- 全树扫描：检测到原木时执行 BFS 获取完整砍伐计划 ----
        data.treeCutList = null;
        if (data.nearbyBlocks != null) {
            for (var b : data.nearbyBlocks) {
                String type = b.blockType();
                if (isLogName(type) && b.distance() <= 6.0) {
                    List<BlockPos> cutList = scanFullTree(serverLevel, b.pos(), pos);
                    if (!cutList.isEmpty()) {
                        data.treeCutList = cutList;
                        AICompanionMod.LOGGER.info("[Perception] Tree scan: {} logs from base {}", cutList.size(), b.pos());
                    }
                    break; // 只扫描找到的第一棵树
                }
            }
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

    // ==================== 全树扫描（BFS） ====================

    /** BFS 全树扫描：从 startLog 沿 6 方向遍历所有相连原木，穿透 1 层树叶。
     *  @return Y 升序→距离升序排序的原木位置列表 */
    private static List<BlockPos> scanFullTree(ServerLevel level, BlockPos startLog, BlockPos origin) {
        List<BlockPos> cutList = new ArrayList<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<BlockPos> visited = new java.util.HashSet<>();
        int maxBlocks = 200;
        int leavesFound = 0;

        queue.add(startLog);
        visited.add(startLog);

        while (!queue.isEmpty() && visited.size() < maxBlocks) {
            BlockPos current = queue.poll();
            cutList.add(current);
            for (var dir : net.minecraft.core.Direction.values()) {
                BlockPos neighbor = current.relative(dir);
                if (visited.contains(neighbor)) continue;
                BlockState state = level.getBlockState(neighbor);
                if (state.isAir()) continue;
                String name = state.getBlock().builtInRegistryHolder().key().location().getPath();
                if (isLogName(name)) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                } else if (isLeavesName(name)) {
                    // 穿透 1 层树叶看后面是否有原木
                    BlockPos behind = current.relative(dir, 2);
                    if (!visited.contains(behind)) {
                        BlockState behindState = level.getBlockState(behind);
                        String behindName = behindState.getBlock()
                            .builtInRegistryHolder().key().location().getPath();
                        if (isLogName(behindName)) {
                            visited.add(behind);
                            queue.add(behind);
                            leavesFound++;
                        }
                    }
                }
            }
        }

        // 排序：Y升序为主，同层按距离升序（同层平推效果）
        cutList.sort((a, b) -> {
            int yCmp = Integer.compare(a.getY(), b.getY());
            if (yCmp != 0) return yCmp;
            return Double.compare(origin.distSqr(a), origin.distSqr(b));
        });

        AICompanionMod.LOGGER.debug("[Perception] Full tree scan: {} logs ({} leaf-penetrated), base={}",
            cutList.size(), leavesFound, startLog);
        return cutList;
    }

    private static boolean isLogName(String name) {
        return name.contains("_log") || name.contains("_stem")
            || name.endsWith("_wood") || name.endsWith("_hyphae");
    }

    private static boolean isLeavesName(String name) {
        return name.contains("_leaves") || name.contains("_leaf");
    }
}
