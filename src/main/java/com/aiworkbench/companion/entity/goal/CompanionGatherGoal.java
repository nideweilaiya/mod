package com.aiworkbench.companion.entity.goal;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.Skill;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.*;

enum GatherFilter {
    ALL, ORES_ONLY, WOOD_ONLY;
    static GatherFilter fromString(String s) {
        return switch (s) { case "ores" -> ORES_ONLY; case "wood" -> WOOD_ONLY; default -> ALL; };
    }
}

/**
 * 智能资源采集Goal v4 — 化简为三阶段：找资源→走路→挖掘。
 *
 * 核心原则：
 * 1. 只找真正的资源（矿石/原木），不把障碍物当目标
 * 2. 一次只锁定一个目标，不完成不切换
 * 3. 到了位置但够不到→把挡路的泥土/石头挖掉
 * 4. LLM 只看到资源列表，不被障碍物数据污染
 * 5. 整树砍伐：原木破坏后优先找6方向相连原木
 */
public class CompanionGatherGoal extends Goal {
    private final AutomatonEntity companion;
    private final double speed;

    private BlockPos target;         // 当前目标
    private boolean isBreaking;
    private float progress;
    private int mineTicks;

    private static final float BASE_BREAK = 30f;
    private static final double REACH_SQ = 2.5 * 2.5; // 玩家挖掘距离
    private static final double MINING_REACH = 2.5;   // 射线检测距离
    private static final int SCAN = 8;

    // 卡住
    private BlockPos lastPos;
    private int stuckTicks;
    private static final int STUCK_MAX = 30;
    private static final double STUCK_SQ = 2.25;

    // 黑名单
    private final Set<BlockPos> blacklist = new HashSet<>();
    private int blacklistTicks;
    private static final int BLACKLIST_DURATION = 200;

    // 整树
    private boolean treeMode;

    // 障碍物追踪
    private BlockPos pendingResource; // 被挡住的目标资源
    private int barrierAttempts;

    // 搭路/导航增强
    private int buildCooldown;
    private int totalBuilds; // 本次采集累计搭路次数
    private static final int BUILD_INTERVAL = 15; // 0.75秒放一个方块
    private static final int MAX_TOTAL_BUILDS = 100; // 单次采集最多搭100个方块

    // 统计 + 自动升级
    private int broken, collected;
    private int brokenSinceCheck;
    private long startedAt;

    // ===== 方块优先级 =====
    private static final Map<String, Integer> PRI = new LinkedHashMap<>();
    static {
        p("ancient_debris", 100);
        p("diamond_ore", 90);      p("deepslate_diamond_ore", 90);
        p("emerald_ore", 80);      p("deepslate_emerald_ore", 80);
        p("nether_gold_ore", 55);
        p("gold_ore", 50);         p("deepslate_gold_ore", 50);
        p("iron_ore", 40);         p("deepslate_iron_ore", 40);
        p("lapis_ore", 25);        p("deepslate_lapis_ore", 25);
        p("redstone_ore", 20);     p("deepslate_redstone_ore", 20);
        p("nether_quartz_ore", 15);
        p("coal_ore", 10);         p("deepslate_coal_ore", 10);
        p("copper_ore", 5);        p("deepslate_copper_ore", 5);
        p("_log", 8);  p("_stem", 8);  p("_wood", 6);  p("_hyphae", 6);
        p("_leaves", 1);
        // 障碍物（base score 0，不会被正常选中）
        p("dirt", 0);  p("grass_block", 0);  p("gravel", 0);  p("sand", 0);
        p("stone", 0);  p("cobblestone", 0);  p("sandstone", 0);
        p("netherrack", 0);  p("deepslate", 0);
    }
    private static void p(String k, int v) { PRI.put(k, v); }

    public CompanionGatherGoal(AutomatonEntity companion, double speed, float range) {
        this.companion = companion;
        this.speed = speed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public boolean canUse() {
        if (companion.isSkillActive() || !companion.isGatherModeEnabled()) return false;
        if (companion.isGuardModeEnabled()) return false; // 守护模式优先
        if (isBreaking) return true;
        // 附近有敌人→切守护
        if (hostilesNearby()) { companion.setGuardModeEnabled(true); return false; }
        target = scan();
        return target != null;
    }
    @Override public boolean canContinueToUse() {
        if (!companion.isGatherModeEnabled() || companion.isSkillActive()) return false;
        if (companion.isGuardModeEnabled()) return false;
        // 采集过程中遇敌→暂停采集，切守护（记住战前模式）
        if (hostilesNearby()) {
            companion.savePreCombatState();
            companion.setGuardModeEnabled(true);
            companion.setGatherModeEnabled(false);
            companion.showDialogue("§c敌人！切换战斗", 40);
            return false;
        }
        return target != null || (isBreaking && scan() != null);
    }

    private boolean hostilesNearby() {
        return !companion.level().getEntities(companion,
            companion.getBoundingBox().inflate(10),
            e -> e instanceof net.minecraft.world.entity.monster.Monster && e.isAlive()
        ).isEmpty();
    }
    @Override public void start() {
        isBreaking = false; progress = 0f; mineTicks = 0;
        broken = 0; collected = 0; startedAt = System.currentTimeMillis();
        lastPos = companion.blockPosition(); stuckTicks = 0;
        blacklist.clear(); blacklistTicks = 0;
        treeMode = false; pendingResource = null; barrierAttempts = 0;
        buildCooldown = 0; totalBuilds = 0;
        AICompanionMod.LOGGER.info("[GatherGoal] Started");
    }
    @Override public void stop() {
        companion.getNavigation().stop();
        // 清除方块破裂动画
        if (target != null && companion.level() instanceof ServerLevel sl)
            sl.destroyBlockProgress(companion.getId(), target, -1);
        isBreaking = false; target = null;
        treeMode = false; pendingResource = null;
    }

    @Override
    public void tick() {
        // 黑名单倒计时
        if (blacklistTicks > 0) blacklistTicks--;
        else if (!blacklist.isEmpty()) { blacklist.clear(); }

        // 卡住检测（正在挖掘时不算卡住）
        BlockPos now = companion.blockPosition();
        if (!isBreaking && lastPos != null && now.distSqr(lastPos) < STUCK_SQ) stuckTicks++;
        else { stuckTicks = 0; lastPos = now; }
        // 卡住→尝试搭路，除非达到总上限
        if (stuckTicks > STUCK_MAX && target != null) {
            if (totalBuilds < MAX_TOTAL_BUILDS && findBuildBlock() >= 0 && tryBuildToward(target)) {
                stuckTicks = 0;
                return;
            }
            // 无法搭路或达到上限→放弃此目标
            if (totalBuilds >= MAX_TOTAL_BUILDS)
                AICompanionMod.LOGGER.info("[GatherGoal] Build limit reached ({}), blacklisting", totalBuilds);
            else
                AICompanionMod.LOGGER.info("[GatherGoal] No build material, blacklisting {}", target);
            blacklist.add(target.immutable()); blacklistTicks = BLACKLIST_DURATION;
            clearTarget();
            stuckTicks = 0;
        }

        // 搭路冷却倒计时
        if (buildCooldown > 0) buildCooldown--;

        // === 目标管理 ===
        if (target == null || !valid(target)) {
            target = scan();
            clearTarget();
            if (target == null) return;
        }

        // === 走路/挖掘 ===
        // Update action display
        if (target != null && !companion.level().getBlockState(target).isAir()) {
            String name = companion.level().getBlockState(target).getBlock()
                .builtInRegistryHolder().key().location().getPath();
            companion.setActionText("⛏ " + name.replace("_", " "));
        }

        if (target != null && !companion.level().getBlockState(target).isAir()) {
            String name = companion.level().getBlockState(target).getBlock()
                .builtInRegistryHolder().key().location().getPath();
            companion.setActionText("⛏ " + name.replace("_", " "));
        }

        Vec3 center = Vec3.atCenterOf(target);
        double distSq = companion.distanceToSqr(center.x, center.y, center.z);
        companion.getLookControl().setLookAt(center.x, center.y, center.z);

        if (distSq > REACH_SQ) {
            // 走路
            companion.getNavigation().moveTo(
                target.getX(), target.getY(), target.getZ(), speed);
            isBreaking = false; progress = 0f; mineTicks = 0;
        } else {
            // 接近目标→尝试用SkillEngine执行单方块采集（概念验证）
            companion.getNavigation().stop();

            if (!companion.getSkillEngine().isActive()) {
                String blockName = companion.level().getBlockState(target).getBlock()
                    .builtInRegistryHolder().key().location().getPath();
                Skill skill = companion.planTask("mine " + blockName);
                if (skill != null) {
                    companion.getSkillEngine().startSkill(skill, companion);
                    AICompanionMod.LOGGER.info("[GatherGoal] Delegated to SkillEngine: {}", skill.getName());
                    return; // SkillEngine接管，GatherGoal暂停
                }
            }

            var hit = canSeeAndReach(target);
            if (hit == null || !hit.getBlockPos().equals(target)) {
                // 射线被阻挡→hit 指向的就是障碍物
                BlockPos barrier = (hit != null && !hit.getBlockPos().equals(target))
                    ? hit.getBlockPos() : findBarrierTo(target);
                if (barrier != null && !blacklist.contains(barrier)) {
                    AICompanionMod.LOGGER.info("[GatherGoal] Clearing barrier {} for {}", barrier,
                        pendingResource != null ? pendingResource : target);
                    if (pendingResource == null) pendingResource = target;
                    target = barrier;
                    barrierAttempts = 0;
                    isBreaking = false; progress = 0f; mineTicks = 0;
                    return;
                }
                // 找不到障碍物或障碍物在黑名单→放弃当前目标（转去 pendingResource）
                if (barrierAttempts > 10) {
                    if (pendingResource != null && !pendingResource.equals(target)) {
                        // 回到原始目标再试一次
                        target = pendingResource;
                        pendingResource = null;
                        barrierAttempts = 0;
                        isBreaking = false; progress = 0f; mineTicks = 0;
                        return;
                    }
                    AICompanionMod.LOGGER.info("[GatherGoal] Cannot reach {}, blacklisting", target);
                    blacklist.add(target.immutable()); blacklistTicks = BLACKLIST_DURATION;
                    pendingResource = null; barrierAttempts = 0;
                    target = scan(); clearTarget();
                    return;
                }
                barrierAttempts++;
                return;
            }

            // 切换工具
            switchTool();

            // Check tool durability - if low, try to swap
            ItemStack currentTool = companion.getEquippedTool();
            if (currentTool.getMaxDamage() > 0 && currentTool.getMaxDamage() - currentTool.getDamageValue() <= 5) {
                if (!trySwapTool()) {
                    companion.tryAcquireTool();
                }
            }

            BlockState state = companion.level().getBlockState(target);
            if (state.isAir()) { target = null; return; }

            float hardness = state.getBlock().defaultDestroyTime();
            if (hardness < 0) {
                blacklist.add(target.immutable());
                blacklistTicks = BLACKLIST_DURATION;
                target = null;
                return;
            }
            float ts = companion.getEffectiveDigSpeed(state);
            progress += ts / (hardness * 30f); // 原版玩家公式
            isBreaking = true;

            // 挥动手臂（每4tick，和裂纹动画同频，匹配原版玩家频率）
            if (mineTicks % 4 == 0) {
                companion.animateSwing();
            }

            // 方块破裂动画（裂纹特效，和玩家挖掘时一样）
            if (companion.level() instanceof ServerLevel sl && mineTicks % 4 == 0) {
                int crackStage = (int)(progress * 10);
                if (crackStage > 9) crackStage = 9;
                sl.destroyBlockProgress(companion.getId(), target, crackStage);
            }

            if (progress >= 1.0f) {
                String name = state.getBlock().builtInRegistryHolder().key().location().getPath();
                boolean wasLog = isWood(name);
                boolean wasBarrier = isBarrier(name);

                // 清除方块破裂动画
                if (companion.level() instanceof ServerLevel sl)
                    sl.destroyBlockProgress(companion.getId(), target, -1);

                breakBlock(target);
                progress = 0f; mineTicks = 0; isBreaking = false;
                broken++;
                brokenSinceCheck++;

                // 每破坏N个方块，检查升级+冶炼
                if (brokenSinceCheck >= 5) {
                    brokenSinceCheck = 0;
                    AutoUpgrader.trySmeltIfNeeded(companion);
                    AutoUpgrader.tryUpgrade(companion);
                }

                // 障碍物清完→回到原来的资源目标，继续破障或到达
                if (wasBarrier && pendingResource != null) {
                    target = pendingResource;
                    // pendingResource 保持不空——如果还够不到会再次触发破障
                    barrierAttempts = 0;
                    stuckTicks = 0;
                    return;
                }

                // 真正资源被破坏→清空 pendingResource
                pendingResource = null;

                // 整树
                if (wasLog) {
                    treeMode = true;
                    BlockPos conn = findConnectedLog(target);
                    if (conn != null) { target = conn; stuckTicks = 0; return; }
                }
                treeMode = false;
                target = scan();
                stuckTicks = 0;
            } else { mineTicks++; }
        }
    }

    private void clearTarget() {
        isBreaking = false; progress = 0f; mineTicks = 0;
        pendingResource = null; barrierAttempts = 0;
    }

    // ===== 搭路/导航增强 =====

    /**
     * 尝试向目标方向搭建路径。
     * 支持三种模式：搭桥（水平）、叠高（上方）、楼梯（斜上方）。
     * @return true 如果成功放置了一个方块
     */
    private boolean tryBuildToward(BlockPos target) {
        if (buildCooldown > 0) return false;

        // 找建筑方块
        int slot = findBuildBlock();
        if (slot < 0) return false;

        Level level = companion.level();
        BlockPos pos = companion.blockPosition();
        int dx = Integer.compare(target.getX(), pos.getX());
        int dz = Integer.compare(target.getZ(), pos.getZ());
        int dy = target.getY() - pos.getY();

        // 确定搭建位置和模式
        BlockPos placePos = null;
        String mode = "";

        if (dy > 1) {
            // 目标在上方→叠高：在自己脚下放方块
            placePos = pos;
            mode = "pillar";
        } else if (dy < -1) {
            // 目标在下方→向下挖阶梯：在脚前方下一格放方块铺垫
            BlockPos step = pos.offset(dx, -1, dz);
            if (level.getBlockState(step).isAir() && level.getBlockState(step.below()).isAir()) {
                placePos = step.below();
                mode = "stairDown";
            }
        }

        // 默认：搭桥向前
        if (placePos == null) {
            // 检查前方是否是空的（间隙）
            BlockPos forward = pos.offset(dx, 0, dz);
            BlockPos forwardBelow = forward.below();
            if (level.getBlockState(forward).isAir() && level.getBlockState(forward.below()).isAir()) {
                // 间隙→在前下方搭桥
                placePos = forward.below();
                mode = "bridge";
            } else if (level.getBlockState(forward).isAir()) {
                // 前方是空气但下方有支撑→可以直接走，不用搭
                return false;
            } else if (dy >= -1 && dy <= 1) {
                // 前方被堵但高度接近→打掉障碍物（由 findBarrierTo 处理）
                return false; // 让破障逻辑处理
            }
        }

        if (placePos == null || !level.getBlockState(placePos).isAir()
                && !level.getBlockState(placePos).canBeReplaced()) return false;

        // 拿出方块并放置
        ItemStack buildBlock = companion.getItem(slot);
        if (buildBlock.isEmpty() || !(buildBlock.getItem() instanceof BlockItem blockItem)) return false;

        // 放置方块
        Block block = blockItem.getBlock();
        level.setBlock(placePos, block.defaultBlockState(), 3);
        companion.animateBlockPlace(placePos);

        // 消耗方块
        buildBlock.shrink(1);
        if (buildBlock.isEmpty()) companion.setItem(slot, ItemStack.EMPTY);

        // 叠高模式 → 放完方块马上跳上去，避免原地踏步
        if ("pillar".equals(mode) && companion.onGround()) {
            companion.getJumpControl().jump();
        }

        buildCooldown = BUILD_INTERVAL;
        totalBuilds++;
        AICompanionMod.LOGGER.info("[GatherGoal] {} {} at {} (total builds: {})",
            mode, block, placePos, totalBuilds);
        companion.showDialogue("§7⛏ " + mode + "(" + totalBuilds + ")", 20);
        return true;
    }

    /** 在背包中找一个可用来搭建的廉价方块 */
    private int findBuildBlock() {
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) continue;
            if (stack.getCount() < 2) continue; // 至少保留一个
            String name = stack.getItem().builtInRegistryHolder().key().location().getPath();
            // 优先用廉价方块
            if (name.contains("dirt") || name.contains("cobblestone")
                || name.contains("netherrack") || name.contains("sandstone")
                || name.contains("planks"))
                return i;
        }
        // 其次任意方块
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem && stack.getCount() >= 2)
                return i;
        }
        return -1;
    }

    // ===== 扫描资源 =====

    private BlockPos scan() {
        BlockPos origin = companion.blockPosition();
        Level level = companion.level();
        GatherFilter filter = GatherFilter.fromString(companion.getGatherFilter());
        Set<String> llm = companion.getGatherPriorityResources();
        BlockPos best = null;
        int bestScore = -1;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -SCAN; dx <= SCAN; dx++) {
            for (int dy = -3; dy <= 5; dy++) {
                for (int dz = -SCAN; dz <= SCAN; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (blacklist.contains(p)) continue;
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) continue;
                    if (s.getBlock().defaultDestroyTime() < 0) continue;

                    String name = s.getBlock().builtInRegistryHolder().key().location().getPath();
                    int score = getScore(name);
                    if (score <= 0) continue;

                    // 过滤
                    if (filter == GatherFilter.ORES_ONLY && isWood(name)) continue;
                    if (filter == GatherFilter.WOOD_ONLY && !isWood(name)) continue;
                    // 树叶只在整树模式
                    if (isLeaves(name) && !treeMode) continue;

                    // 整树加分
                    if (treeMode && isWood(name)) score += 80;
                    if (treeMode && isLeaves(name)) score += 5;

                    // LLM优先级（仅对真正的资源生效，不对障碍物）
                    if (!isBarrier(name) && !llm.isEmpty() && matchesAny(name, llm))
                        score += 50;

                    double dist = origin.distSqr(p);
                    if (score > bestScore || (score == bestScore && dist < bestDist)) {
                        bestScore = score; bestDist = dist; best = p.immutable();
                    }
                }
            }
        }

        if (best != null) {
            AICompanionMod.LOGGER.info("[GatherGoal] Scan→{} score={} dist={}{}",
                level.getBlockState(best).getBlock(), bestScore, String.format("%.1f", Math.sqrt(bestDist)),
                treeMode ? " [tree]" : (pendingResource != null ? " [barrier]" : ""));
        }
        return best;
    }

    /**
     * 射线检测：从同伴眼睛向目标方块中心发射射线，
     * 判断是否能"看到并触及"这个方块（和玩家挖掘逻辑一致）。
     * @return 命中结果，null 表示看不到或太远
     */
    private net.minecraft.world.phys.BlockHitResult canSeeAndReach(BlockPos pos) {
        Vec3 eye = companion.getEyePosition();
        // 向方块最近的面向同伴的面发射射线
        Vec3 center = Vec3.atCenterOf(pos);
        Vec3 dir = center.subtract(eye).normalize();
        Vec3 end = eye.add(dir.scale(MINING_REACH + 0.5));

        net.minecraft.world.level.ClipContext ctx = new net.minecraft.world.level.ClipContext(
            eye, end,
            net.minecraft.world.level.ClipContext.Block.COLLIDER,
            net.minecraft.world.level.ClipContext.Fluid.NONE,
            companion);
        return companion.level().clip(ctx);
    }

    /** 找到挡在同伴和 pos 之间的第一个障碍方块 */
    private BlockPos findBarrierTo(BlockPos target) {
        Level level = companion.level();
        Vec3 eye = companion.getEyePosition();
        Vec3 goal = Vec3.atCenterOf(target);
        double dx = goal.x - eye.x, dy = goal.y - eye.y, dz = goal.z - eye.z;
        double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (dist < 1.0) return null;
        dx /= dist; dy /= dist; dz /= dist;

        BlockPos last = null;
        for (double t = 1.0; t < dist; t += 0.6) {
            BlockPos p = new BlockPos(
                (int)Math.floor(eye.x + dx*t),
                (int)Math.floor(eye.y + dy*t),
                (int)Math.floor(eye.z + dz*t));
            if (p.equals(target)) break;
            if (p.equals(last)) continue;
            last = p;
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && s.getBlock().defaultDestroyTime() >= 0 && isBarrier(s.getBlock().builtInRegistryHolder().key().location().getPath()))
                return p.immutable();
        }
        return null;
    }

    /** 找整棵树最底部的原木（从当前位置往下追溯） */
    private BlockPos findBottomLog(BlockPos from) {
        BlockPos bottom = from;
        BlockPos current = from;
        // 向下追溯
        while (true) {
            BlockState below = companion.level().getBlockState(current.below());
            if (!below.isAir() && isWood(below.getBlock().builtInRegistryHolder().key().location().getPath())) {
                current = current.below();
                bottom = current;
            } else break;
        }
        return bottom;
    }

    /** 找相连原木中的下一个（已有底部基准后，从底部往上找） */
    private BlockPos findConnectedLog(BlockPos from) {
        // 先从底部开始，向上搜索
        BlockPos bottom = findBottomLog(from);
        if (!bottom.equals(from)) return bottom; // 如果当前位置不是底部，先到底部

        for (BlockPos p : new BlockPos[]{
            from.above(), from.north(), from.south(), from.east(), from.west(),
            from.above(2), from.above().north(), from.above().south(), from.above().east(), from.above().west()
        }) {
            BlockState s = companion.level().getBlockState(p);
            if (!s.isAir() && isWood(s.getBlock().builtInRegistryHolder().key().location().getPath()))
                return p.immutable();
        }
        return null;
    }

    // ===== 工具 =====
    private boolean trySwapTool() {
        ItemStack current = companion.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        if (current.isEmpty()) return false;
        Class<?> toolClass = current.getItem() instanceof net.minecraft.world.item.PickaxeItem
            ? net.minecraft.world.item.PickaxeItem.class
            : current.getItem() instanceof net.minecraft.world.item.AxeItem
                ? net.minecraft.world.item.AxeItem.class : null;
        if (toolClass == null) return false;

        int bestSlot = -1;
        int bestDurability = 0;
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (stack.isEmpty() || !toolClass.isInstance(stack.getItem())) continue;
            int durability = stack.getMaxDamage() - stack.getDamageValue();
            if (durability > 5 && durability > bestDurability) {
                bestDurability = durability;
                bestSlot = i;
            }
        }
        if (bestSlot >= 0) {
            ItemStack newTool = companion.getItem(bestSlot).copy();
            companion.setItem(bestSlot, current.copy());
            companion.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, newTool);
            companion.animateSwing();
            return true;
        }
        return false;
    }
    private void switchTool() {
        if (target == null) return;
        BlockState s = companion.level().getBlockState(target);
        if (s.isAir()) return;
        boolean needAxe = isWood(s.getBlock().builtInRegistryHolder().key().location().getPath());
        ItemStack cur = companion.getEquippedTool();
        if (needAxe && cur.getItem() instanceof AxeItem) return;
        if (!needAxe && cur.getItem() instanceof PickaxeItem) return;

        int best = -1, bestT = -1;
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack st = companion.getItem(i);
            if (st.isEmpty()) continue;
            boolean ok = needAxe ? st.getItem() instanceof AxeItem : st.getItem() instanceof PickaxeItem;
            if (!ok) continue;
            int t = st.getItem() instanceof TieredItem ti ? ti.getTier().getLevel() : 0;
            if (t > bestT) { bestT = t; best = i; }
        }
        if (best >= 0) {
            ItemStack nt = companion.getItem(best);
            companion.setItem(best, cur);
            companion.setItemSlot(EquipmentSlot.MAINHAND, nt);
        }
    }

    // ===== 破坏 =====
    private void breakBlock(BlockPos pos) {
        if (companion.level().isClientSide) return;
        Level level = companion.level();
        BlockState s = level.getBlockState(pos);
        if (!(level instanceof ServerLevel sl)) return;
        ItemStack tool = companion.getEquippedTool();

        List<ItemStack> drops = Block.getDrops(s, sl, pos,
            level.getBlockEntity(pos), companion, tool);

        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        ItemStack heldTool = companion.getItemBySlot(EquipmentSlot.MAINHAND);
        if (!heldTool.isEmpty() && heldTool.isDamageableItem()) {
            heldTool.hurtAndBreak(1, companion, e -> e.broadcastBreakEvent(EquipmentSlot.MAINHAND));
        }
        level.levelEvent(2001, pos, net.minecraft.world.level.block.Block.getId(s));
        for (ItemStack d : drops) {
            if (!d.isEmpty()) { collected++; companion.addItemToInventory(d); }
        }
        // 授予经验值
        if (isWood(s.getBlock().builtInRegistryHolder().key().location().getPath()))
            companion.grantChoppingXp();
        else if (!isBarrier(s.getBlock().builtInRegistryHolder().key().location().getPath()))
            companion.grantMiningXp(s);
        AICompanionMod.LOGGER.info("[GatherGoal] {} ({}b/{}i)",
            s.getBlock().builtInRegistryHolder().key().location().getPath(), broken, collected);
    }

    // ===== 工具方法 =====
    private int getScore(String name) {
        if (PRI.containsKey(name)) return PRI.get(name);
        for (var e : PRI.entrySet()) if (name.contains(e.getKey())) return e.getValue();
        return 0;
    }
    private boolean isWood(String n) { return n.contains("_log")||n.contains("_stem")||n.contains("_wood")||n.contains("_hyphae"); }
    private boolean isLeaves(String n) { return n.contains("_leaves"); }
    private boolean isBarrier(String n) {
        return n.contains("dirt")||n.contains("grass")||n.contains("stone")||n.contains("cobblestone")
            ||n.contains("gravel")||n.contains("sand")||n.contains("sandstone")
            ||n.contains("netherrack")||n.contains("deepslate");
    }
    private boolean matchesAny(String n, Set<String> ks) { for (String k : ks) if (n.contains(k)) return true; return false; }
    private boolean valid(BlockPos p) { return p != null && !blacklist.contains(p) && !companion.level().getBlockState(p).isAir(); }

    // ===== 统计 =====
    public int getBlocksBroken() { return broken; }
    public int getItemsCollected() { return collected; }
    public long getElapsedMs() { return startedAt > 0 ? System.currentTimeMillis() - startedAt : 0; }
    public BlockPos getCurrentTarget() { return target; }

    /**
     * 供 LLM 战略层调用的感知摘要——只返回真正的资源，不包括障碍物。
     */
    public static List<String> getResourceSummary(AutomatonEntity entity, int radius) {
        List<String> resources = new ArrayList<>();
        BlockPos o = entity.blockPosition();
        Level l = entity.level();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = o.offset(dx, dy, dz);
                    BlockState s = l.getBlockState(p);
                    if (s.isAir()) continue;
                    if (s.getBlock().defaultDestroyTime() < 0) continue;
                    String n = s.getBlock().builtInRegistryHolder().key().location().getPath();
                    int score = 0;
                    if (PRI.containsKey(n)) score = PRI.get(n);
                    else for (var e : PRI.entrySet()) if (n.contains(e.getKey())) { score = e.getValue(); break; }
                    if (score >= 5) // 只汇报有价值的资源
                        resources.add(n + "@" + p.toShortString());
                }
            }
        }
        return resources;
    }
}
