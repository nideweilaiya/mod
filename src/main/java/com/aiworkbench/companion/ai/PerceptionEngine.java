package com.aiworkbench.companion.ai;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.core.perception.TreeStructure;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.task.Navigator;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
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
    private static final int TREE_LEAF_PENETRATION_DEPTH = 3;
    private static final int TREE_SEEK_RADIUS = 24;
    private static final int TREE_SEEK_Y_BELOW = 4;
    private static final int TREE_SEEK_Y_ABOVE = 10;
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

        // ---- 掉落物扫描 ----
        List<com.aiworkbench.companion.core.perception.PerceptionData.NearbyItem> nearbyItems = new ArrayList<>();
        for (Entity e : serverLevel.getEntities(null, entityBox)) {
            if (!(e instanceof ItemEntity item) || !item.isAlive() || item.isRemoved()) {
                continue;
            }
            ItemStack stack = item.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String itemType = stack.getItem().builtInRegistryHolder().key().location().getPath();
            double distance = Math.sqrt(pos.distSqr(item.blockPosition()));
            nearbyItems.add(new com.aiworkbench.companion.core.perception.PerceptionData.NearbyItem(
                itemType, stack.getCount(), item.blockPosition(), distance
            ));
        }
        nearbyItems.sort(java.util.Comparator.comparingDouble(i -> i.distance()));
        data.nearbyItems = nearbyItems;

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
        data.treeStructure = null;
        data.treeAnchorHint = null;
        if (data.nearbyBlocks != null) {
            TreeStructure bestStructure = null;
            BlockPos bestBase = null;
            java.util.HashSet<BlockPos> scannedLogs = new java.util.HashSet<>();
            for (var b : data.nearbyBlocks) {
                String type = b.blockType();
                if (!isLogName(type) || b.distance() > 6.0 || scannedLogs.contains(b.pos())) {
                    continue;
                }
                TreeStructure structure = scanFullTree(serverLevel, b.pos(), pos);
                if (structure == null || structure.isEmpty()) {
                    continue;
                }
                List<BlockPos> flattened = structure.flattenColumnsBottomUp();
                scannedLogs.addAll(flattened);
                if (bestStructure == null
                    || flattened.size() > bestStructure.flattenColumnsBottomUp().size()
                    || (flattened.size() == bestStructure.flattenColumnsBottomUp().size()
                        && pos.distSqr(flattened.get(0)) < pos.distSqr(bestStructure.flattenColumnsBottomUp().get(0)))) {
                    bestStructure = structure;
                    bestBase = b.pos();
                }
            }
            if (bestStructure != null && !bestStructure.isEmpty()) {
                data.treeStructure = bestStructure;
                data.treeCutList = bestStructure.flattenColumnsBottomUp();
                BlockPos structuralApproach = findTreeApproachHint(
                    serverLevel,
                    bestStructure.treeAnchor(),
                    pos
                );
                if (structuralApproach != null) {
                    data.treeAnchorHint = structuralApproach;
                }
                AICompanionMod.LOGGER.debug(
                    "[Perception] Tree scan: {} columns / {} logs from base {}",
                    bestStructure.trunkColumns().size(),
                    data.treeCutList.size(),
                    bestBase
                );
            }
        }

        boolean proactiveTreeSeekMode =
            "chop".equals(companion.getMode()) || "autonomous".equals(companion.getMode());
        if (proactiveTreeSeekMode && data.treeAnchorHint == null
            && (data.treeStructure == null || data.treeStructure.isEmpty())) {
            BlockPos anchorHint = findTreeAnchorHint(serverLevel, pos);
            if (anchorHint != null) {
                data.treeAnchorHint = anchorHint;
                AICompanionMod.LOGGER.debug("[Perception] Tree anchor hint detected at {}", anchorHint);
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

    public static List<BlockPos> scanTreeFrom(ServerLevel level, BlockPos startLog, BlockPos origin) {
        if (level == null || startLog == null || origin == null) {
            return List.of();
        }
        BlockState startState = level.getBlockState(startLog);
        if (!isTreeLog(startState)) {
            return List.of();
        }
        TreeStructure structure = scanFullTree(level, startLog, origin);
        return structure == null ? List.of() : structure.flattenColumnsBottomUp();
    }

    public static TreeStructure scanTreeStructureFrom(ServerLevel level, BlockPos startLog, BlockPos origin) {
        if (level == null || startLog == null || origin == null) {
            return null;
        }
        BlockState startState = level.getBlockState(startLog);
        if (!isTreeLog(startState)) {
            return null;
        }
        return scanFullTree(level, startLog, origin);
    }

    public static boolean isTreeLog(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        String name = state.getBlock().builtInRegistryHolder().key().location().getPath();
        return isLogName(name);
    }

    /** BFS 全树扫描：从 startLog 沿 6 方向遍历所有相连原木，穿透 1 层树叶。
     *  @return Y 升序→距离升序排序的原木位置列表 */
    private static TreeStructure scanFullTree(ServerLevel level, BlockPos startLog, BlockPos origin) {
        List<BlockPos> cutList = new ArrayList<>();
        Set<BlockPos> leafBlocks = new LinkedHashSet<>();
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
                    leafBlocks.add(neighbor.immutable());
                    BlockPos behind = findLogBehindLeaves(level, current, dir, visited);
                    if (behind != null) {
                        visited.add(behind);
                        queue.add(behind);
                        leavesFound++;
                    }
                }
            }
        }

        // 排序：Y升序为主，同层按距离升序（同层平推效果）
        TreeStructure structure = TreeStructure.fromLogs(startLog, cutList, leafBlocks, origin);

        AICompanionMod.LOGGER.debug("[Perception] Full tree scan: {} logs ({} leaf-penetrated), base={}",
            cutList.size(), leavesFound, startLog);
        return structure;
    }

    private static boolean isLogName(String name) {
        return name.contains("_log") || name.contains("_stem")
            || name.endsWith("_wood") || name.endsWith("_hyphae");
    }

    private static boolean isLeavesName(String name) {
        return name.contains("_leaves") || name.contains("_leaf");
    }

    private static BlockPos findTreeAnchorHint(ServerLevel level, BlockPos origin) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        Set<String> visitedColumns = new HashSet<>();

        for (int dx = -TREE_SEEK_RADIUS; dx <= TREE_SEEK_RADIUS; dx++) {
            for (int dz = -TREE_SEEK_RADIUS; dz <= TREE_SEEK_RADIUS; dz++) {
                double horizontalDistSq = dx * dx + dz * dz;
                if (horizontalDistSq > (double) TREE_SEEK_RADIUS * TREE_SEEK_RADIUS) {
                    continue;
                }
                for (int dy = -TREE_SEEK_Y_BELOW; dy <= TREE_SEEK_Y_ABOVE; dy++) {
                    BlockPos probe = origin.offset(dx, dy, dz);
                    if (!isTreeLog(level.getBlockState(probe))) {
                        continue;
                    }
                    BlockPos anchor = findBottomLog(level, probe);
                    String columnKey = anchor.getX() + ":" + anchor.getZ();
                    if (!visitedColumns.add(columnKey)) {
                        continue;
                    }
                    BlockPos approach = findTreeApproachHint(level, anchor, origin);
                    if (approach == null) {
                        continue;
                    }
                    double distSq = horizontalDistanceSq(origin, approach);
                    if (best == null || distSq < bestDistSq) {
                        best = approach.immutable();
                        bestDistSq = distSq;
                    }
                    break;
                }
            }
        }

        return best;
    }

    private static BlockPos findTreeApproachHint(ServerLevel level, BlockPos anchor, BlockPos origin) {
        BlockPos[] offsets = {
            anchor.north(),
            anchor.south(),
            anchor.east(),
            anchor.west()
        };

        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (BlockPos candidate : offsets) {
            BlockPos standable = Navigator.findWalkableGroundStatic(level, candidate, origin);
            if (standable == null) {
                continue;
            }
            double distSq = horizontalDistanceSq(origin, standable);
            if (best == null || distSq < bestDistSq) {
                best = standable.immutable();
                bestDistSq = distSq;
            }
        }
        return best;
    }

    private static BlockPos findBottomLog(ServerLevel level, BlockPos start) {
        BlockPos current = start.immutable();
        int airGapBudget = 1;
        while (current.getY() > level.getMinBuildHeight()) {
            BlockPos below = current.below();
            BlockState belowState = level.getBlockState(below);
            if (isTreeLog(belowState)) {
                current = below.immutable();
                continue;
            }
            if (belowState.isAir() && airGapBudget > 0) {
                airGapBudget--;
                current = below.immutable();
                continue;
            }
            if (level.getBlockState(current).isAir()) {
                return current.above().immutable();
            }
            return current.immutable();
        }
        return current.immutable();
    }

    private static double horizontalDistanceSq(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - b.getX();
        long dz = (long) a.getZ() - b.getZ();
        return (double) dx * dx + (double) dz * dz;
    }

    private static BlockPos findLogBehindLeaves(
        ServerLevel level,
        BlockPos current,
        net.minecraft.core.Direction dir,
        java.util.Set<BlockPos> visited
    ) {
        for (int depth = 2; depth <= TREE_LEAF_PENETRATION_DEPTH + 1; depth++) {
            BlockPos probe = current.relative(dir, depth);
            if (visited.contains(probe)) {
                continue;
            }
            BlockState probeState = level.getBlockState(probe);
            if (probeState.isAir()) {
                return null;
            }
            String probeName = probeState.getBlock().builtInRegistryHolder().key().location().getPath();
            if (isLogName(probeName)) {
                return probe;
            }
            if (!isLeavesName(probeName)) {
                return null;
            }
        }
        return null;
    }
}
