package com.aiworkbench.companion.entity.goal;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.btree.BehaviorNode;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.atomic.BlockMatcher;
import com.aiworkbench.companion.skill.atomic.BreakBlockAction;
import com.aiworkbench.companion.task.Navigator;
import com.aiworkbench.companion.task.TaskTarget;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.*;

/**
 * v2.4: 树模式 — 从底向上逐层砍伐 + Navigator 降速修复
 *
 * 修复 (2026-05-24):
 * 1. 树模式: scan()检测到木头→追溯树干底部→挖完一个→找正上方连接木头→逐层向上
 * 2. 树模式中跳过冷却，直接链接下一层
 * 3. 高温目标不再向空中方块寻路（统一用 navigateToGroundBelow）
 * 4. Navigator 移除降速重试（避免绕树慢走）
 *
 * 行为树:
 *   Cooldown(60tick) ── Sequence("采集")
 *     ├─ Condition("有目标?")
 *     ├─ Action("走到目标")
 *     ├─ Action("挖掘目标")
 *     └─ Action("收集掉落")
 */
public class BTreeGatherGoal extends Goal {

    private final AutomatonEntity companion;
    private final Navigator navigator;
    private final BehaviorNode behaviorTree;
    private TaskTarget currentTarget;

    private BreakBlockAction blockBreaker;

    // ── gameTime 冷却 ──
    private long cooldownUntil = 0;
    private static final int COMPLETION_COOLDOWN_TICKS = 60;

    // ── 扫描参数 ──
    private static final int SCAN_RADIUS = 9;
    private static final int SCAN_Y_MIN = -3;
    private static final int SCAN_Y_MAX = 8;

    // ── 排障追踪 ──
    @Nullable private BlockPos pendingResource;
    private int barrierAttempts;
    private static final int MAX_BARRIER_ATTEMPTS = 15;

    // ── 播报去重 ──
    private String lastActionText = "";

    // ── 卡住 ──
    private int stuckTicks = 0;
    private BlockPos lastStuckCheckPos;
    private static final int MAX_STUCK_TICKS = 60;

    // MC-039: BreakBlockAction静默卡住检测
    private int breakerStuckTicks = 0;
    private static final int MAX_BREAKER_STUCK = 60; // 3秒无进度→重新射线检测

    // ── v2.4: 树模式 ──
    private boolean treeMode = false;
    @Nullable private BlockPos treeBasePos;

    // ── v2.5.3: BFS全树扫描 ──
    private final List<BlockPos> cutList = new ArrayList<>();       // 全树木头列表(Y升序)
    private int cutIndex = 0;                                        // 当前进度索引
    private static final int TREE_BFS_MAX = 200;                     // 最大扫描方块数

    // ── v2.5.2: 树模式持久化 + 垫脚回收 ──
    @Nullable private BlockPos lastTreeTarget;         // 正在处理中的树目标（跨 restart 持久化）
    private int treeStuckTicks = 0;                    // 树模式无进度计时
    private static final int TREE_STUCK_RESET = 120;   // 6秒无进度→换角度
    private static final int TREE_STUCK_ABANDON = 300; // 15秒无进度→放弃
    private final List<BlockPos> pillarPlacements = new ArrayList<>(); // 垫脚放置位置（用于回收）

    // 优先级表
    private static final Map<String, Integer> DEFAULT_PRIORITY = new LinkedHashMap<>();
    static {
        DEFAULT_PRIORITY.put("ancient_debris", 80);
        DEFAULT_PRIORITY.put("diamond_ore", 60);
        DEFAULT_PRIORITY.put("deepslate_diamond_ore", 60);
        DEFAULT_PRIORITY.put("emerald_ore", 55);
        DEFAULT_PRIORITY.put("deepslate_emerald_ore", 55);
        DEFAULT_PRIORITY.put("gold_ore", 45);
        DEFAULT_PRIORITY.put("deepslate_gold_ore", 45);
        DEFAULT_PRIORITY.put("iron_ore", 40);
        DEFAULT_PRIORITY.put("deepslate_iron_ore", 40);
        DEFAULT_PRIORITY.put("lapis_ore", 35);
        DEFAULT_PRIORITY.put("deepslate_lapis_ore", 35);
        DEFAULT_PRIORITY.put("redstone_ore", 30);
        DEFAULT_PRIORITY.put("deepslate_redstone_ore", 30);
        DEFAULT_PRIORITY.put("coal_ore", 30);
        DEFAULT_PRIORITY.put("deepslate_coal_ore", 30);
        DEFAULT_PRIORITY.put("copper_ore", 25);
        DEFAULT_PRIORITY.put("deepslate_copper_ore", 25);
        DEFAULT_PRIORITY.put("nether_gold_ore", 35);
        DEFAULT_PRIORITY.put("nether_quartz_ore", 20);
        DEFAULT_PRIORITY.put("gilded_blackstone", 30);
        DEFAULT_PRIORITY.put("oak_log", 8);
        DEFAULT_PRIORITY.put("birch_log", 8);
        DEFAULT_PRIORITY.put("spruce_log", 8);
        DEFAULT_PRIORITY.put("jungle_log", 8);
        DEFAULT_PRIORITY.put("acacia_log", 8);
        DEFAULT_PRIORITY.put("dark_oak_log", 8);
        DEFAULT_PRIORITY.put("mangrove_log", 8);
        DEFAULT_PRIORITY.put("cherry_log", 8);
        DEFAULT_PRIORITY.put("stone", 1);
        DEFAULT_PRIORITY.put("dirt", 1);
        DEFAULT_PRIORITY.put("grass_block", 1);
        DEFAULT_PRIORITY.put("gravel", 1);
        DEFAULT_PRIORITY.put("sand", 1);
    }

    public BTreeGatherGoal(AutomatonEntity companion) {
        this.companion = companion;
        this.navigator = new Navigator(companion);
        this.behaviorTree = buildTree();
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private BehaviorNode buildTree() {
        return BehaviorNode.cooldown("采集冷却", COMPLETION_COOLDOWN_TICKS,
            BehaviorNode.sequence("采集",
                new BehaviorNode.Condition("有目标?", (e, t) -> hasTarget(e, t)),
                new BehaviorNode.Action("走到目标", this::moveToTarget),
                new BehaviorNode.Action("挖掘目标", this::mineTarget),
                new BehaviorNode.Action("收集掉落", this::collectItems)
            )
        );
    }

    // ==================== Goal 生命周期 ====================

    @Override public boolean canUse() {
        if (companion.isSkillActive() || !companion.isGatherModeEnabled()) return false;
        if (companion.level().getGameTime() < cooldownUntil) return false;
        // 树模式链式继续：pendingResource 存下一层木头
        if (pendingResource != null && isValidTarget(pendingResource)) {
            currentTarget = TaskTarget.fromBlockState(pendingResource,
                companion.level().getBlockState(pendingResource));
            if (currentTarget != null) {
                pendingResource = null;
                return true;
            }
        }
        // v2.5.2: 持久化树目标 — 同一棵树跨 restart 继续
        if (lastTreeTarget != null && isValidTarget(lastTreeTarget)) {
            currentTarget = TaskTarget.fromBlockState(lastTreeTarget,
                companion.level().getBlockState(lastTreeTarget));
            treeMode = true;
            treeBasePos = lastTreeTarget;
            treeStuckTicks = 0;
            return true;
        }
        pendingResource = null;
        treeMode = false;
        treeBasePos = null;
        lastTreeTarget = null;
        pillarPlacements.clear();
        cutList.clear();
        currentTarget = scan();
        return currentTarget != null;
    }

    @Override public boolean canContinueToUse() {
        if (!companion.isGatherModeEnabled()) return false;
        if (pendingResource != null && isValidTarget(pendingResource)) return true;
        return currentTarget != null;
    }

    @Override public void start() {
        AICompanionMod.LOGGER.info("[BTreeGather] Started: {} treeMode={} treeBase={}",
            currentTarget, treeMode, treeBasePos);
        companion.setActivelyGathering(true);
        navigator.stop(); // 清除上一Goal遗留的导航状态，避免启动延迟
        blockBreaker = null;
        cachedTarget = null;
        scanTimer = 0;
        stuckTicks = 0;
        treeStuckTicks = 0;
        lastStuckCheckPos = companion.blockPosition();
        lastActionText = "";
        // v2.5.2: 树模式记录持久化目标（但保留之前的 pillarPlacements）
        if (treeMode && treeBasePos != null) {
            lastTreeTarget = treeBasePos;
        }
        behaviorTree.onStart(companion, currentTarget);
    }

    @Override public void tick() {
        // 树模式链式目标恢复
        if (currentTarget == null && pendingResource != null && isValidTarget(pendingResource)) {
            currentTarget = TaskTarget.fromBlockState(pendingResource,
                companion.level().getBlockState(pendingResource));
            if (currentTarget == null) {
                pendingResource = null;
                barrierAttempts = 0;
                return;
            }
            // v2.5.2: 树模式保留 pendingResource，moveToTarget 用它判断是否需要垫脚
            if (!treeMode) pendingResource = null;
        }

        if (currentTarget == null) {
            currentTarget = scan();
            if (currentTarget == null) return;
        }

        // 卡住检测
        BlockPos now = companion.blockPosition();
        if (lastStuckCheckPos != null && now.distSqr(lastStuckCheckPos) < 2.25) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
            lastStuckCheckPos = now;
        }
        if (stuckTicks > MAX_STUCK_TICKS) {
            companion.setSprinting(false);
        }

        // v2.5.2: 树模式无进度计时
        if (treeMode) {
            treeStuckTicks++;
            if (treeStuckTicks > TREE_STUCK_ABANDON) {
                AICompanionMod.LOGGER.info("[BTreeGather] Tree stuck {} ticks, abandoning tree at {}",
                    treeStuckTicks, treeBasePos);
                companion.setActivelyGathering(false);
                cooldownUntil = companion.level().getGameTime() + COMPLETION_COOLDOWN_TICKS;
                blockBreaker = null;
                pendingResource = null;
                barrierAttempts = 0;
                treeMode = false;
                treeBasePos = null;
                lastTreeTarget = null;
                pillarPlacements.clear();
                currentTarget = null;
                cachedTarget = null;
                return;
            }
            if (treeStuckTicks > TREE_STUCK_RESET && treeStuckTicks % 40 == 0) {
                AICompanionMod.LOGGER.info("[BTreeGather] Tree slow progress ({} ticks), re-scanning approach", treeStuckTicks);
                cachedTarget = null; // 强制下次扫描
            }
        }

        BehaviorNode.Status status = behaviorTree.tick(companion, currentTarget);

        if (status == BehaviorNode.Status.SUCCESS) {
            BlockPos minedPos = currentTarget != null ? currentTarget.getPosition() : null;
            String minedName = currentTarget != null ? currentTarget.getTargetId() : "";

            // v2.5.2: 树模式 — 有进度时重置卡住计时
            if (treeMode) treeStuckTicks = 0;

            if (treeMode && minedPos != null && isLogName(minedName)) {
                cutIndex++;
                if (cutIndex < cutList.size()) {
                    BlockPos nextLog = cutList.get(cutIndex);
                    // 验证目标仍是木头（可能被自然事件改变或已被同伴自己挖掉）
                    String nextName = companion.level().getBlockState(nextLog)
                        .getBlock().builtInRegistryHolder().key().location().getPath();
                    if (!isLogName(nextName)) {
                        AICompanionMod.LOGGER.info("[BTreeGather] Next log {} changed to {}, skipping", nextLog, nextName);
                        currentTarget = null; // 触发scan找下一个目标
                        return;
                    }
                    // v2.5.3: 统一路径 — 直接设目标+重启行为树，moveToTarget负责导航/垫脚
                    // 不通过pendingResource绕圈（MC-036：pendingResource恢复间隙导致FAILURE）
                    AICompanionMod.LOGGER.info("[BTreeGather] Tree progress: {}/{} → next log {} (Y={})",
                        cutIndex, cutList.size(), nextLog, nextLog.getY());
                    currentTarget = TaskTarget.fromBlockState(nextLog,
                        companion.level().getBlockState(nextLog));
                    companion.getLookControl().setLookAt(
                        nextLog.getX() + 0.5, nextLog.getY() + 0.5, nextLog.getZ() + 0.5);
                    blockBreaker = null;
                    behaviorTree.onStart(companion, currentTarget);
                    return;
                } else {
                    AICompanionMod.LOGGER.info("[BTreeGather] Tree complete: {} logs cut", cutList.size());
                    recoverPillarBlocks();
                }
            }

            // 非树模式或树已砍完 → 冷却
            companion.setActivelyGathering(false);
            // v2.5.2: 树完成后短冷却(10tick)，快速跳到下一棵树
            boolean wasTree = treeMode;
            cooldownUntil = companion.level().getGameTime() + (wasTree ? 10 : COMPLETION_COOLDOWN_TICKS);
            blockBreaker = null;
            pendingResource = null;
            barrierAttempts = 0;
            treeMode = false;
            treeBasePos = null;
            lastTreeTarget = null;
            pillarPlacements.clear();
            String msg = "⛏ 采集完成";
            if (!msg.equals(lastActionText)) {
                companion.setActionText(msg);
                lastActionText = msg;
            }
            currentTarget = null;
            cachedTarget = null;
        } else if (status == BehaviorNode.Status.FAILURE) {
            // v2.5.2: 树模式失败不立即清除状态，保持持久化目标
            if (treeMode) {
                AICompanionMod.LOGGER.info("[BTreeGather] Tree mode FAILURE on {}, keeping tree state for retry", treeBasePos);
                cooldownUntil = companion.level().getGameTime() + 10; // 短冷却快速重试
                blockBreaker = null;
                pendingResource = null;
                currentTarget = null;
                cachedTarget = null;
                return;
            }
            companion.setActivelyGathering(false);
            cooldownUntil = companion.level().getGameTime() + COMPLETION_COOLDOWN_TICKS / 2;
            blockBreaker = null;
            if (barrierAttempts >= MAX_BARRIER_ATTEMPTS) {
                AICompanionMod.LOGGER.info("[BTreeGather] Barrier attempts exhausted");
                pendingResource = null;
                barrierAttempts = 0;
            }
            currentTarget = null;
            cachedTarget = null;
        }
    }

    @Override public void stop() {
        companion.setActivelyGathering(false);
        navigator.stop();
        if (blockBreaker != null) {
            blockBreaker.stop(companion);
            blockBreaker = null;
        }
        currentTarget = null;
        cachedTarget = null;
        scanTimer = 0;
        stuckTicks = 0;
        treeStuckTicks = 0;
        pendingResource = null;
        treeMode = false;
        treeBasePos = null;
        lastTreeTarget = null;
        pillarPlacements.clear();
        cutList.clear();
        justEnteredMode = true;
    }

    // ==================== 行为树原子动作 ====================

    private boolean hasTarget(AutomatonEntity entity, TaskTarget target) {
        return target != null && target.getPosition() != null;
    }

    private BehaviorNode.Status moveToTarget(AutomatonEntity entity, TaskTarget target) {
        BlockPos pos = target.getPosition();
        if (pos == null) return BehaviorNode.Status.FAILURE;

        double distSq = entity.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        int vertDist = Math.abs(entity.blockPosition().getY() - pos.getY());
        int entityY = entity.blockPosition().getY();

        // v2.5.3: 树模式 — Y差>2就继续垫脚，垫到Y差≤2再开挖。不用canReachBlock做阈值。
        if (treeMode && treeBasePos != null && pos.getY() > entityY + 2) {
            double xzDist = Math.sqrt(
                (entity.blockPosition().getX() - treeBasePos.getX()) * (entity.blockPosition().getX() - treeBasePos.getX()) +
                (entity.blockPosition().getZ() - treeBasePos.getZ()) * (entity.blockPosition().getZ() - treeBasePos.getZ()));
            if (xzDist <= 2.0) {
                int vertGap = pos.getY() - entity.blockPosition().getY();
                if (vertGap > 2) {
                    if (entity.onGround()) {
                        AICompanionMod.LOGGER.info("[BTreeGather] Pillar needed: target=Y{}, myY={}, vertGap={}",
                            pos.getY(), entity.blockPosition().getY(), vertGap);
                        tryPillarUp(treeBasePos, pos);
                    } else {
                        AICompanionMod.LOGGER.info("[BTreeGather] Pillar needed but not onGround, waiting...");
                    }
                }
                navigator.stop();
                entity.setSprinting(false);
                entity.getLookControl().setLookAt(
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                return vertGap <= 2 ? BehaviorNode.Status.SUCCESS : BehaviorNode.Status.RUNNING;
            }
            // 还没到树旁：导航到树基邻接空地
            BlockPos walkTo = findAdjacentWalkable(treeBasePos);
            if (walkTo != null) navigator.navigateToExact(walkTo, 1.0);
            else navigator.navigateToExact(treeBasePos, 1.0);
            entity.setSprinting(distSq > 25.0);
            entity.getLookControl().setLookAt(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            return BehaviorNode.Status.RUNNING;
        }

        // v2.4.1: 挖掘距离匹配 BreakBlockAction (4.5²≈20)
        boolean inRange = distSq <= 20.0 || (distSq <= 36.0 && vertDist <= 8);
        if (inRange) {
            navigator.stop();
            entity.setSprinting(false);
            return BehaviorNode.Status.SUCCESS;
        }

        // v2.5.2: 远距离疾跑，近距离走路
        entity.setSprinting(distSq > 25.0);

        // v2.4: 高温方块从不向空中寻路 — 只导航到正下方地面
        if (pos.getY() > entityY + 1) {
            navigator.navigateToGroundBelow(pos, 1.0);
        } else {
            navigator.navigateTo(pos, 1.0);
        }

        entity.getLookControl().setLookAt(
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

        if (navigator.checkStuck()) {
            AICompanionMod.LOGGER.info("[BTreeGather] Stuck navigating to {}, abandoning", pos);
            return BehaviorNode.Status.FAILURE;
        }
        return BehaviorNode.Status.RUNNING;
    }

    private BehaviorNode.Status mineTarget(AutomatonEntity entity, TaskTarget target) {
        BlockPos pos = target.getPosition();
        if (pos == null) return BehaviorNode.Status.FAILURE;

        double distSq = entity.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        int vertDist = Math.abs(entity.blockPosition().getY() - pos.getY());

        // v2.4.1: 挖掘距离匹配 BreakBlockAction (4.5²≈20)
        boolean inRange = distSq <= 20.0 || (distSq <= 36.0 && vertDist <= 8);

        if (!inRange) {
            // 不向空中方块寻路，导航到正下方
            int entityY = entity.blockPosition().getY();
            if (pos.getY() > entityY + 1) {
                navigator.navigateToGroundBelow(pos, 1.0);
            } else {
                navigator.navigateTo(pos, 1.0);
            }
            return BehaviorNode.Status.RUNNING;
        }
        navigator.stop();

        if (entity.level().getBlockState(pos).isAir()) {
            return BehaviorNode.Status.SUCCESS;
        }

        // 射线排障检测
        if (pendingResource == null || pos.equals(pendingResource)) {
            BlockHitResult hit = raycastToBlock(entity, pos);
            if (hit != null && !hit.getBlockPos().equals(pos)) {
                BlockPos barrier = hit.getBlockPos();
                BlockState barrierState = entity.level().getBlockState(barrier);
                if (!barrierState.isAir() && barrierState.getBlock().defaultDestroyTime() >= 0
                    && isBarrier(barrierState)) {
                    if (barrierAttempts >= MAX_BARRIER_ATTEMPTS) {
                        AICompanionMod.LOGGER.info("[BTreeGather] Too many barrier attempts, giving up on {}", pos);
                        pendingResource = null;
                        barrierAttempts = 0;
                        return BehaviorNode.Status.FAILURE;
                    }
                    pendingResource = pos;
                    barrierAttempts++;
                    if (blockBreaker != null) {
                        blockBreaker.stop(entity);
                        blockBreaker = null;
                    }
                    currentTarget = TaskTarget.fromBlockState(barrier, barrierState);
                    AICompanionMod.LOGGER.info("[BTreeGather] Clearing barrier {} for resource {} (attempt {})",
                        barrier, pendingResource, barrierAttempts);
                    return BehaviorNode.Status.RUNNING;
                }
            }

            // v2.5.2: 射线无障碍但目标在下方且不可导航 → 尝试向下挖掘
            int entityY = entity.blockPosition().getY();
            String targetName = target.getTargetId();
            if (pos.getY() < entityY && isOreBlock(targetName.replace("minecraft:", ""))) {
                BlockPos digTarget = findDownwardBarrier(entity, pos);
                if (digTarget != null) {
                    if (barrierAttempts >= MAX_BARRIER_ATTEMPTS) {
                        AICompanionMod.LOGGER.info("[BTreeGather] Too many downward dig attempts for {}", pos);
                        pendingResource = null;
                        barrierAttempts = 0;
                        return BehaviorNode.Status.FAILURE;
                    }
                    pendingResource = pos;
                    barrierAttempts++;
                    if (blockBreaker != null) {
                        blockBreaker.stop(entity);
                        blockBreaker = null;
                    }
                    BlockState digState = entity.level().getBlockState(digTarget);
                    currentTarget = TaskTarget.fromBlockState(digTarget, digState);
                    AICompanionMod.LOGGER.info("[BTreeGather] Digging downward {} to reach ore {} (attempt {})",
                        digTarget, pos, barrierAttempts);
                    return BehaviorNode.Status.RUNNING;
                }
            }
        }

        if (blockBreaker == null) {
            String blockName = target.getTargetId();
            String shortName = blockName.contains(":") ? blockName.split(":")[1] : blockName;
            blockBreaker = new BreakBlockAction(BlockMatcher.contains(shortName));
            blockBreaker.setTaskTarget(target);
            breakerStuckTicks = 0;
        }

        // MC-039: BreakBlockAction连续运行但无进展→可能被树叶/障碍物挡住→重新射线检测
        breakerStuckTicks++;
        if (breakerStuckTicks > MAX_BREAKER_STUCK && breakerStuckTicks % 20 == 0) {
            BlockHitResult rehit = raycastToBlock(entity, pos);
            if (rehit != null && !rehit.getBlockPos().equals(pos)) {
                BlockPos newBarrier = rehit.getBlockPos();
                BlockState newBarrierState = entity.level().getBlockState(newBarrier);
                if (!newBarrierState.isAir() && newBarrierState.getBlock().defaultDestroyTime() >= 0
                    && isBarrier(newBarrierState)) {
                    AICompanionMod.LOGGER.info("[BTreeGather] Breaker stuck {} ticks, found new barrier {}",
                        breakerStuckTicks, newBarrier);
                    pendingResource = pos;
                    if (blockBreaker != null) { blockBreaker.stop(entity); blockBreaker = null; }
                    currentTarget = TaskTarget.fromBlockState(newBarrier, newBarrierState);
                    barrierAttempts++;
                    breakerStuckTicks = 0;
                    return BehaviorNode.Status.RUNNING;
                }
            }
        }

        String actionMsg = "⛏ " + target.getTargetId().replace("minecraft:", "").replace("_", " ");
        if (!actionMsg.equals(lastActionText)) {
            companion.setActionText(actionMsg);
            lastActionText = actionMsg;
        }

        if (blockBreaker.tick(entity)) {
            breakerStuckTicks = 0;
            blockBreaker = null;
            if (pendingResource != null && !pos.equals(pendingResource)) {
                AICompanionMod.LOGGER.info("[BTreeGather] Barrier mined, switching back to resource {}", pendingResource);
                currentTarget = TaskTarget.fromBlockState(pendingResource,
                    entity.level().getBlockState(pendingResource));
                return BehaviorNode.Status.RUNNING;
            }
            pendingResource = null;
            barrierAttempts = 0;
            return BehaviorNode.Status.SUCCESS;
        }
        return BehaviorNode.Status.RUNNING;
    }

    private BehaviorNode.Status collectItems(AutomatonEntity entity, TaskTarget target) {
        return BehaviorNode.Status.SUCCESS;
    }

    // ==================== 射线检测 ====================

    @Nullable
    private BlockHitResult raycastToBlock(AutomatonEntity entity, BlockPos target) {
        Vec3 eye = entity.getEyePosition();
        Vec3 center = Vec3.atCenterOf(target);
        Vec3 dir = center.subtract(eye).normalize();
        double dist = eye.distanceTo(center) + 1.0;
        Vec3 end = eye.add(dir.scale(dist));

        ClipContext ctx = new ClipContext(eye, end,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            entity);
        return entity.level().clip(ctx);
    }

    // ==================== v2.4: 树模式辅助方法 ====================

    /** 从给定位置向下追溯，找到树干最底部的原木。跳过 ≤2 格空气间隙继续向下。 */
    @Nullable
    private BlockPos findBottomLog(BlockPos from) {
        Level level = companion.level();
        BlockPos current = from;
        int airGap = 0;
        for (int i = 0; i < 20; i++) {
            BlockPos below = current.below();
            BlockState state = level.getBlockState(below);
            if (state.isAir()) {
                airGap++;
                if (airGap > 2) break; // 超过2格空气，树确实断了
                current = below;
                continue;
            }
            String name = state.getBlock().builtInRegistryHolder().key().location().getPath();
            if (!isLogName(name)) break;
            current = below;
            airGap = 0; // 找到木头，重置空气计数
        }
        return current.equals(from) ? null : current;
    }

    /**
     * v2.5.3: BFS全树扫描。从树底出发沿6方向遍历所有相连原木方块，
     * 按Y升序→距离排序存入cutList，一步获取整棵树的完整挖掘计划。
     */
    private void scanFullTree(BlockPos startLog) {
        cutList.clear();
        cutIndex = 0;
        Level level = companion.level();
        BlockPos origin = companion.blockPosition();

        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<BlockPos> visited = new java.util.HashSet<>();
        int leavesFound = 0;
        queue.add(startLog);
        visited.add(startLog);

        while (!queue.isEmpty() && visited.size() < TREE_BFS_MAX) {
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
                    // 树叶遮挡：穿透一层树叶看后面是否还有原木
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

        // Y升序，同Y按距同伴距离排序 → 同层平推效果
        cutList.sort((a, b) -> {
            int yCmp = Integer.compare(a.getY(), b.getY());
            if (yCmp != 0) return yCmp;
            return Double.compare(
                origin.distSqr(a), origin.distSqr(b));
        });

        AICompanionMod.LOGGER.info("[BTreeGather] Full tree scan: {} logs ({} leaf-penetrated), base={}, top={}",
            cutList.size(), leavesFound, startLog,
            cutList.isEmpty() ? "none" : cutList.get(cutList.size() - 1));
    }

    /** 判断方块名是否为原木/菌柄/木头类 */
    private static boolean isLogName(String name) {
        return name.contains("_log") || name.contains("_stem")
            || name.endsWith("_wood") || name.endsWith("_hyphae");
    }

    /** 判断方块名是否为树叶 */
    private static boolean isLeavesName(String name) {
        return name.contains("_leaves") || name.contains("_leaf");
    }

    // ==================== v2.5: 垫脚爬升 ====================

    /** 检查方块是否在挖掘范围内（匹配 BreakBlockAction 的 4.5 格） */
    private boolean canReachBlock(BlockPos pos) {
        double distSq = companion.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        return distSq <= 20.0; // 4.5² = 20.25
    }

    /**
     * v2.5.3: 在树基附近放置垫脚方块并跳上去。
     * 先找到树基旁最近的可行走地面，走到后再在脚下放方块垫高。
     * 支持连续多层垫脚直到够到目标。
     *
     * @param pillarTarget 树基位置（用于定位附近地面）
     * @param targetLog    需要够到的目标原木位置
     */
    private boolean tryPillarUp(BlockPos pillarTarget, BlockPos targetLog) {
        Level level = companion.level();
        BlockPos myPos = companion.blockPosition();

        // v2.5.3: 找树基附近最近的可站立地面（避开树干占用的XZ），而不是直接导航到树基XZ
        BlockPos groundSpot = findAdjacentWalkable(pillarTarget);
        if (groundSpot == null) {
            groundSpot = pillarTarget; // fallback
        }

        double distToGround = Math.sqrt(
            (myPos.getX() - groundSpot.getX()) * (myPos.getX() - groundSpot.getX()) +
            (myPos.getZ() - groundSpot.getZ()) * (myPos.getZ() - groundSpot.getZ()));

        // 还没走到树基附近，先导航
        if (distToGround > 2.0) {
            AICompanionMod.LOGGER.info("[BTreeGather] Pillar: not at ground spot (dist={}), navigating to {}",
                String.format("%.1f", distToGround), groundSpot);
            navigator.navigateToExact(groundSpot, 1.0);
            return false;
        }

        // MC-038: 垫脚前检查头顶两格是否有树叶/障碍物，有则先清除
        BlockPos above1 = myPos.above();
        BlockPos above2 = myPos.above(2);
        BlockState aboveState1 = level.getBlockState(above1);
        BlockState aboveState2 = level.getBlockState(above2);
        if ((!aboveState1.isAir() && !aboveState1.canBeReplaced() && isBarrier(aboveState1))
            || (!aboveState2.isAir() && !aboveState2.canBeReplaced() && isBarrier(aboveState2))) {
            BlockPos blocker = (!aboveState1.isAir() && isBarrier(aboveState1)) ? above1 : above2;
            AICompanionMod.LOGGER.info("[BTreeGather] Overhead blocked by {} at {}, clearing before pillar",
                level.getBlockState(blocker).getBlock().builtInRegistryHolder().key().location().getPath(), blocker);
            // 用pendingResource保护树目标：挖完树叶后mineTarget会自动切回pendingResource
            pendingResource = targetLog;
            currentTarget = TaskTarget.fromBlockState(blocker, level.getBlockState(blocker));
            blockBreaker = null;
            behaviorTree.onStart(companion, currentTarget);
            barrierAttempts = 0; // 树叶不算排障失败
            return false;
        }

        // v2.5.3: 垫脚纯执行——放方块+跳。决策（何时垫、垫多少）由调用者根据vertGap控制
        if (companion.onGround()) {
            BlockPos feetPos = myPos;
            BlockState state = level.getBlockState(feetPos);
            if (state.isAir() || state.canBeReplaced()) {
                int slot = findPillarBlock();
                if (slot >= 0) {
                    net.minecraft.world.item.ItemStack stack = companion.getItem(slot);
                    if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem) {
                        int yBefore = myPos.getY();
                        companion.setShiftKeyDown(true);
                        level.setBlock(feetPos, blockItem.getBlock().defaultBlockState(), 3);
                        companion.setShiftKeyDown(false);
                        stack.shrink(1);
                        if (stack.isEmpty()) companion.setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);
                        pillarPlacements.add(feetPos.immutable());
                        if (companion.onGround()) companion.getJumpControl().jump();
                        // MC-042: 方块在脚下放置时可能被横向推开而非向上顶。
                        // 如果Y没变，说明被推下垫脚柱→手动抬升一格。
                        if (companion.blockPosition().getY() == yBefore) {
                            companion.setPos(companion.getX(), yBefore + 1.0, companion.getZ());
                            AICompanionMod.LOGGER.info("[BTreeGather] Forced elevate to Y={} after pillar push-off", yBefore + 1);
                        }
                        AICompanionMod.LOGGER.info("[BTreeGather] Pillared up at {} using {} (treeBase={}, target=Y{}, vertGap={})",
                            feetPos, blockItem.getBlock().builtInRegistryHolder().key().location().getPath(),
                            pillarTarget, targetLog.getY(), targetLog.getY() - myPos.getY());
                        return true;
                    }
                } else {
                    AICompanionMod.LOGGER.info("[BTreeGather] No pillar block available");
                }
            }
        }
        return false;
    }

    /**
     * v2.5.3: 在树基周围的水平邻接方向中，找同伴当前Y高度上第一个可站立的空地。
     * 不用findWalkableGroundStatic（从高往低扫可能返回树上叶子层的错误坐标），
     * 直接用同伴当前地面Y检查邻接位置。
     */
    @Nullable
    private BlockPos findAdjacentWalkable(BlockPos treeBase) {
        Level level = companion.level();
        int groundY = companion.blockPosition().getY();
        int[][] offsets = {{1,0}, {-1,0}, {0,1}, {0,-1}, {1,1}, {-1,-1}, {1,-1}, {-1,1}};
        for (int[] off : offsets) {
            BlockPos check = new BlockPos(treeBase.getX() + off[0], groundY, treeBase.getZ() + off[1]);
            var below = level.getBlockState(check.below());
            var at = level.getBlockState(check);
            var above = level.getBlockState(check.above());
            if (!below.isAir() && below.getBlock().defaultDestroyTime() >= 0
                && (at.isAir() || at.canBeReplaced())
                && (above.isAir() || above.canBeReplaced())) {
                return check;
            }
        }
        return null;
    }

    /**
     * v2.5.2: 树砍完后回收所有垫脚方块。
     * 从最高的开始向下挖，确保方块能掉落到地面被收集。
     */
    private void recoverPillarBlocks() {
        if (pillarPlacements.isEmpty()) return;
        AICompanionMod.LOGGER.info("[BTreeGather] Recovering {} pillar blocks", pillarPlacements.size());
        Level level = companion.level();
        // 从高到低排序
        pillarPlacements.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
        for (BlockPos pos : pillarPlacements) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            String name = state.getBlock().builtInRegistryHolder().key().location().getPath();
            // 只回收不是矿石/原木的廉价方块（即我们放置的垫脚方块）
            if (!isOreBlock(name) && !isLogName(name)) {
                level.destroyBlock(pos, true);
            }
        }
        pillarPlacements.clear();
    }

    /** 在背包中找一个可用来垫脚的廉价方块（排除矿石） */
    private int findPillarBlock() {
        for (int i = 0; i < companion.getInventorySize(); i++) {
            net.minecraft.world.item.ItemStack stack = companion.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem)) continue;
            String name = stack.getItem().builtInRegistryHolder().key().location().getPath();
            // 排除矿石
            if (name.contains("_ore") || name.contains("ancient_debris")
                || name.contains("gilded_blackstone")) continue;
            // 优先用廉价方块（排除原木，避免挖自己垫的方块）
            if (name.contains("dirt") || name.contains("cobblestone")
                || name.contains("netherrack") || name.contains("sandstone")
                || name.contains("planks")
                || name.contains("gravel") || name.contains("sand"))
                return i;
        }
        // 任意非矿石非原木方块
        for (int i = 0; i < companion.getInventorySize(); i++) {
            net.minecraft.world.item.ItemStack stack = companion.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem)) continue;
            String name = stack.getItem().builtInRegistryHolder().key().location().getPath();
            if (name.contains("_ore") || name.contains("ancient_debris")
                || name.contains("gilded_blackstone")) continue;
            // MC-030: 排除原木类，避免用砍下来的原木垫脚导致循环
            if (isLogName(name)) continue;
            return i;
        }
        return -1;
    }

    // ==================== 扫描 ====================

    private int scanTimer = 0;
    private static final int SCAN_INTERVAL = 20;
    @Nullable private TaskTarget cachedTarget;
    private boolean justEnteredMode = true; // v2.5.2: 首次进入采集模式强制立即扫描

    @Nullable
    private TaskTarget scan() {
        // v2.5.2: 首次进入模式强制立即扫描，不使用缓存
        if (justEnteredMode) {
            justEnteredMode = false;
            cachedTarget = null;
            scanTimer = SCAN_INTERVAL; // 强制触发扫描
        }
        scanTimer++;
        if (cachedTarget != null && scanTimer < SCAN_INTERVAL) return cachedTarget;
        scanTimer = 0;
        treeMode = false;
        treeBasePos = null;
        cutList.clear();

        Level level = companion.level();
        BlockPos origin = companion.blockPosition();
        String filter = companion.getGatherFilter();
        Set<String> priorities = companion.getGatherPriorityResources();
        String targetBlock = companion.getGatherTargetBlock();

        List<Candidate> candidates = new ArrayList<>();

        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dy = SCAN_Y_MIN; dy <= SCAN_Y_MAX; dy++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(p);
                    if (state.isAir()) continue;
                    if (state.getBlock().defaultDestroyTime() < 0) continue;

                    String name = state.getBlock().builtInRegistryHolder().key().location().getPath();
                    String fullId = state.getBlock().builtInRegistryHolder().key().location().toString();

                    if (targetBlock != null && !targetBlock.isEmpty()) {
                        if (!fullId.equals(targetBlock)) continue;
                    }

                    if ("ores".equals(filter) && isOreBlock(name)) { /* pass */ }
                    else if ("wood".equals(filter) && !isLogName(name)) continue;
                    else if (!"ores".equals(filter) && !"wood".equals(filter)) { /* all */ }

                    int score = DEFAULT_PRIORITY.getOrDefault(name, 0);
                    if (!priorities.isEmpty()) {
                        for (String pRes : priorities) {
                            if (name.contains(pRes.toLowerCase())) {
                                score += 50;
                                break;
                            }
                        }
                    }
                    if (score <= 0) continue;

                    if (!isDiscoverable(level, p, isOreBlock(name))) continue;

                    // v2.5.1: 木头类方块 — 追溯到底部，验证底部仍是木头
                    BlockPos effectivePos = p;
                    if (isLogName(name)) {
                        BlockPos bottom = findBottomLog(p);
                        if (bottom != null && isLogName(level.getBlockState(bottom)
                                .getBlock().builtInRegistryHolder().key().location().getPath())) {
                            effectivePos = bottom;
                        }
                    }

                    double dist = origin.distSqr(effectivePos);
                    candidates.add(new Candidate(effectivePos, state, score, dist, isLogName(name)));
                }
            }
        }

        candidates.sort((a, b) -> {
            if (b.score != a.score) return Integer.compare(b.score, a.score);
            return Double.compare(a.dist, b.dist);
        });

        int checked = 0;
        for (Candidate c : candidates) {
            if (checked >= 8) break;
            checked++;

            var path = companion.getNavigation().createPath(
                c.pos.getX(), c.pos.getY(), c.pos.getZ(), 1);
            if (path != null && path.canReach()) {
                cachedTarget = TaskTarget.fromBlockState(c.pos,
                    level.getBlockState(c.pos));
                // v2.5.3: 树模式 — BFS全树扫描，填充cutList
                if (c.isLog) {
                    treeMode = true;
                    treeBasePos = c.pos;
                    scanFullTree(c.pos);
                    AICompanionMod.LOGGER.info("[BTreeGather] Tree mode: {} logs scanned from base {}",
                        cutList.size(), c.pos);
                }
                return cachedTarget;
            }
        }

        cachedTarget = null;
        return null;
    }

    /**
     * v2.5.2: 检测方块是否可被发现。
     * 矿石类：放宽条件——只要不是6面都被不可破坏方块包围即可。
     * 木头/其他：保持原有逻辑，至少一个面接触空气。
     */
    private static boolean isDiscoverable(Level level, BlockPos pos, boolean isOre) {
        int blockedFaces = 0;
        int totalFaces = 0;
        for (var dir : net.minecraft.core.Direction.values()) {
            totalFaces++;
            var neighborState = level.getBlockState(pos.relative(dir));
            if (neighborState.isAir()) return true; // 任何类型暴露空气都立即返回
            // 计数不可破坏的面（基岩、屏障等）
            if (neighborState.getBlock().defaultDestroyTime() < 0) {
                blockedFaces++;
            }
        }
        if (isOre) {
            // 矿石：只有6面都被不可破坏方块包围才认为不可发现
            return blockedFaces < totalFaces;
        }
        // 非矿石：必须至少有一个空气面（上面已返回true，到这里就是没有空气面）
        return false;
    }

    private boolean isValidTarget(BlockPos pos) {
        if (pos == null) return false;
        BlockState state = companion.level().getBlockState(pos);
        return !state.isAir() && state.getBlock().defaultDestroyTime() >= 0;
    }

    private static class Candidate {
        final BlockPos pos;
        final BlockState state;
        final int score;
        final double dist;
        final boolean isLog;
        Candidate(BlockPos p, BlockState s, int sc, double d, boolean log) {
            pos = p; state = s; score = sc; dist = d; isLog = log;
        }
    }

    private static boolean isOreBlock(String name) {
        return name.contains("_ore") || name.equals("ancient_debris")
            || name.equals("gilded_blackstone");
    }

    private static boolean isWoodBlock(String name) {
        return isLogName(name);
    }

    /**
     * v2.5.2: 找实体到下方目标之间的第一个可挖障碍物。
     * 从实体脚底向下扫描，找到第一个屏障方块用于挖掘通道。
     */
    @Nullable
    private BlockPos findDownwardBarrier(AutomatonEntity entity, BlockPos target) {
        BlockPos entityPos = entity.blockPosition();
        // 从实体正下方开始，向下扫描到目标Y+1
        for (int y = entityPos.getY() - 1; y > target.getY(); y--) {
            BlockPos check = new BlockPos(target.getX(), y, target.getZ());
            // 先检查目标XZ列
            BlockState state = entity.level().getBlockState(check);
            if (!state.isAir() && state.getBlock().defaultDestroyTime() >= 0 && isBarrier(state)) {
                return check;
            }
            // 再检查实体XZ列
            check = new BlockPos(entityPos.getX(), y, entityPos.getZ());
            state = entity.level().getBlockState(check);
            if (!state.isAir() && state.getBlock().defaultDestroyTime() >= 0 && isBarrier(state)) {
                return check;
            }
        }
        return null;
    }

    private static boolean isBarrier(BlockState state) {
        String name = state.getBlock().builtInRegistryHolder().key().location().getPath();
        return name.contains("dirt") || name.contains("grass") || name.contains("stone")
            || name.contains("cobblestone") || name.contains("gravel") || name.contains("sand")
            || name.contains("sandstone") || name.contains("netherrack")
            || name.contains("deepslate") || name.contains("tuff")
            || name.contains("leaves") || name.contains("leaf") || name.contains("vine")
            || name.contains("moss") || name.contains("snow") || name.contains("carpet")
            || name.contains("_plant") || name.contains("fern") || name.contains("bamboo")
            || name.contains("cane") || name.contains("cobweb") || name.contains("scaffold")
            || name.contains("wool");
    }
}
