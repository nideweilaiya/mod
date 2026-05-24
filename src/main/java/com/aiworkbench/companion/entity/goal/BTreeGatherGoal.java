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

    // ── v2.4: 树模式 ──
    private boolean treeMode = false;
    @Nullable private BlockPos treeBasePos;

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
        pendingResource = null;
        treeMode = false;
        treeBasePos = null;
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
        blockBreaker = null;
        cachedTarget = null;
        scanTimer = 0;
        stuckTicks = 0;
        lastStuckCheckPos = companion.blockPosition();
        lastActionText = "";
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
            pendingResource = null;
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

        BehaviorNode.Status status = behaviorTree.tick(companion, currentTarget);

        if (status == BehaviorNode.Status.SUCCESS) {
            BlockPos minedPos = currentTarget != null ? currentTarget.getPosition() : null;
            String minedName = currentTarget != null ? currentTarget.getTargetId() : "";

            // v2.5.1: 树模式 — 挖完后找上一层连接木头，够不到时走到树基垫脚
            if (treeMode && minedPos != null && isLogName(minedName)) {
                BlockPos above = findLogAbove(minedPos);
                if (above != null) {
                    if (!canReachBlock(above)) {
                        // 先走到树基正下方再垫脚
                        BlockPos basePos = new BlockPos(minedPos.getX(), companion.blockPosition().getY(), minedPos.getZ());
                        AICompanionMod.LOGGER.info("[BTreeGather] Next log {} out of reach, walking to tree base {} then pillar", above, basePos);
                        // 导航到树基位置
                        navigator.navigateToGroundBelow(basePos, 1.0);
                        // 尝试垫脚（只一次，下个 tick 再判断）
                        tryPillarUp();
                        // 设置 pendingResource 让 canUse 下次恢复（不立即 chain）
                        pendingResource = above;
                        currentTarget = null;
                        return;
                    }
                    AICompanionMod.LOGGER.info("[BTreeGather] Tree chain: {} → next log {}", minedPos, above);
                    currentTarget = TaskTarget.fromBlockState(above,
                        companion.level().getBlockState(above));
                    blockBreaker = null;
                    behaviorTree.onStart(companion, currentTarget);
                    return;
                } else {
                    AICompanionMod.LOGGER.info("[BTreeGather] Tree complete at {}", minedPos);
                }
            }

            // 非树模式或树已砍完 → 正常冷却
            companion.setActivelyGathering(false);
            cooldownUntil = companion.level().getGameTime() + COMPLETION_COOLDOWN_TICKS;
            blockBreaker = null;
            pendingResource = null;
            barrierAttempts = 0;
            treeMode = false;
            treeBasePos = null;
            String msg = "⛏ 采集完成";
            if (!msg.equals(lastActionText)) {
                companion.setActionText(msg);
                lastActionText = msg;
            }
            currentTarget = null;
            cachedTarget = null;
        } else if (status == BehaviorNode.Status.FAILURE) {
            companion.setActivelyGathering(false);
            cooldownUntil = companion.level().getGameTime() + COMPLETION_COOLDOWN_TICKS / 2;
            blockBreaker = null;
            if (barrierAttempts >= MAX_BARRIER_ATTEMPTS) {
                AICompanionMod.LOGGER.info("[BTreeGather] Barrier attempts exhausted");
                pendingResource = null;
                barrierAttempts = 0;
            }
            treeMode = false;
            treeBasePos = null;
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

        // v2.4.1: 挖掘距离匹配 BreakBlockAction (4.5²≈20)
        boolean inRange = distSq <= 20.0 || (distSq <= 36.0 && vertDist <= 8);
        if (inRange) {
            navigator.stop();
            return BehaviorNode.Status.SUCCESS;
        }

        // v2.4: 高温方块从不向空中寻路 — 只导航到正下方地面
        int entityY = entity.blockPosition().getY();
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
        }

        if (blockBreaker == null) {
            String blockName = target.getTargetId();
            String shortName = blockName.contains(":") ? blockName.split(":")[1] : blockName;
            blockBreaker = new BreakBlockAction(BlockMatcher.contains(shortName));
            blockBreaker.setTaskTarget(target);
        }

        String actionMsg = "⛏ " + target.getTargetId().replace("minecraft:", "").replace("_", " ");
        if (!actionMsg.equals(lastActionText)) {
            companion.setActionText(actionMsg);
            lastActionText = actionMsg;
        }

        if (blockBreaker.tick(entity)) {
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

    /** 找给定位置正上方直接相连的原木（用于树模式链式砍伐） */
    @Nullable
    private BlockPos findLogAbove(BlockPos from) {
        Level level = companion.level();
        // 检查正上方 1-3 格（覆盖原木+树叶间隙的情况）
        for (int dy = 1; dy <= 4; dy++) {
            BlockPos above = from.above(dy);
            BlockState state = level.getBlockState(above);
            if (state.isAir()) continue;
            String name = state.getBlock().builtInRegistryHolder().key().location().getPath();
            if (isLogName(name)) return above.immutable();
            // 如果遇到非木头非空气方块，说明树顶到了
            if (state.getBlock().defaultDestroyTime() >= 0 && !isLeavesName(name)) break;
        }
        return null;
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
     * 在脚下放置一个廉价方块并跳上去，用于爬高砍树。
     * 只在脚下位置放置，不作水平偏移。放置时蹲下防止掉落。
     */
    private boolean tryPillarUp() {
        Level level = companion.level();
        BlockPos feetPos = companion.blockPosition();

        // 脚下必须是空气或可替换（确保能放）
        BlockState state = level.getBlockState(feetPos);
        if (!state.isAir() && !state.canBeReplaced()) {
            AICompanionMod.LOGGER.info("[BTreeGather] Cannot pillar at {} — occupied by {}", feetPos,
                state.getBlock().builtInRegistryHolder().key().location().getPath());
            return false;
        }

        int slot = findPillarBlock();
        if (slot < 0) {
            AICompanionMod.LOGGER.info("[BTreeGather] No pillar block available");
            return false;
        }

        net.minecraft.world.item.ItemStack stack = companion.getItem(slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)) {
            return false;
        }

        companion.setShiftKeyDown(true);
        level.setBlock(feetPos, blockItem.getBlock().defaultBlockState(), 3);
        companion.setShiftKeyDown(false);

        stack.shrink(1);
        if (stack.isEmpty()) companion.setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);

        if (companion.onGround()) {
            companion.getJumpControl().jump();
        }

        AICompanionMod.LOGGER.info("[BTreeGather] Pillared up at {} using {}", feetPos,
            blockItem.getBlock().builtInRegistryHolder().key().location().getPath());
        return true;
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
        // 任意非矿石方块
        for (int i = 0; i < companion.getInventorySize(); i++) {
            net.minecraft.world.item.ItemStack stack = companion.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem)) continue;
            String name = stack.getItem().builtInRegistryHolder().key().location().getPath();
            if (name.contains("_ore") || name.contains("ancient_debris")
                || name.contains("gilded_blackstone")) continue;
            return i;
        }
        return -1;
    }

    // ==================== 扫描 ====================

    private int scanTimer = 0;
    private static final int SCAN_INTERVAL = 20;
    @Nullable private TaskTarget cachedTarget;

    @Nullable
    private TaskTarget scan() {
        scanTimer++;
        if (cachedTarget != null && scanTimer < SCAN_INTERVAL) return cachedTarget;
        scanTimer = 0;
        treeMode = false;
        treeBasePos = null;

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

                    if (!isExposed(level, p)) continue;

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
                // v2.4: 检测是否为树模式
                if (c.isLog) {
                    treeMode = true;
                    treeBasePos = c.pos;
                    AICompanionMod.LOGGER.info("[BTreeGather] Tree mode: bottom log at {}", c.pos);
                }
                return cachedTarget;
            }
        }

        cachedTarget = null;
        return null;
    }

    private static boolean isExposed(Level level, BlockPos pos) {
        for (var dir : net.minecraft.core.Direction.values()) {
            if (level.getBlockState(pos.relative(dir)).isAir()) return true;
        }
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
