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

import java.util.*;

/**
 * v2.2: 行为树驱动的采集 Goal — 过滤 + 优先级 + Y范围 + 直接挖掘
 *
 * 核心改动:
 * 1. 支持采集过滤 (ores/wood/all) 从 companion.getGatherFilter()
 * 2. 支持玩家配置的优先级 (companion.getGatherPriorityResources())
 * 3. 支持指定目标方块 (companion.getGatherTargetBlock())
 * 4. Y 扫描范围扩展: -3~8 (覆盖头顶树木)
 * 5. 直接用 BreakBlockAction 挖掘，不依赖 LLM/skill 匹配
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

    // ── 直接挖掘：不依赖 LLM/skill 匹配 ──
    private com.aiworkbench.companion.skill.atomic.BreakBlockAction blockBreaker;

    // ── 完成冷却 ──
    private int completionCooldown = 0;
    private static final int COMPLETION_COOLDOWN_TICKS = 60; // 3秒冷却

    // ── 扫描参数 ──
    private static final int SCAN_RADIUS = 9;
    private static final int SCAN_Y_MIN = -3;
    private static final int SCAN_Y_MAX = 8;  // v2.2: 扩展到8格覆盖头顶树木

    // 硬编码默认优先级 (1-100，越高越优先)
    private static final Map<String, Integer> DEFAULT_PRIORITY = new LinkedHashMap<>();
    static {
        // 矿石 (高优先级)
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
        // 木材 (中低优先级)
        DEFAULT_PRIORITY.put("oak_log", 8);
        DEFAULT_PRIORITY.put("birch_log", 8);
        DEFAULT_PRIORITY.put("spruce_log", 8);
        DEFAULT_PRIORITY.put("jungle_log", 8);
        DEFAULT_PRIORITY.put("acacia_log", 8);
        DEFAULT_PRIORITY.put("dark_oak_log", 8);
        DEFAULT_PRIORITY.put("mangrove_log", 8);
        DEFAULT_PRIORITY.put("cherry_log", 8);
        // 其他
        DEFAULT_PRIORITY.put("stone", 1);
        DEFAULT_PRIORITY.put("dirt", 1);
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
        // 冷却中不启动
        if (completionCooldown > 0) return false;
        currentTarget = scan();
        return currentTarget != null;
    }

    @Override public boolean canContinueToUse() {
        return currentTarget != null && companion.isGatherModeEnabled();
    }

    @Override public void start() {
        AICompanionMod.LOGGER.info("[BTreeGather] Started: {}", currentTarget);
        blockBreaker = null;
        behaviorTree.onStart(companion, currentTarget);
    }

    @Override public void tick() {
        if (completionCooldown > 0) {
            completionCooldown--;
            return;
        }

        if (currentTarget == null) {
            currentTarget = scan();
            if (currentTarget == null) return;
        }

        BehaviorNode.Status status = behaviorTree.tick(companion, currentTarget);
        if (status == BehaviorNode.Status.SUCCESS) {
            completionCooldown = COMPLETION_COOLDOWN_TICKS;
            blockBreaker = null;
            currentTarget = null;
            companion.setActionText("⛏ 采集完成");
        } else if (status == BehaviorNode.Status.FAILURE) {
            // 失败 → 放弃当前目标，短冷却后重新扫描
            completionCooldown = COMPLETION_COOLDOWN_TICKS / 2;
            blockBreaker = null;
            currentTarget = null;
            AICompanionMod.LOGGER.info("[BTreeGather] Target failed, will rescan");
        }
    }

    @Override public void stop() {
        navigator.stop();
        if (blockBreaker != null) {
            blockBreaker.stop(companion);
            blockBreaker = null;
        }
        currentTarget = null;
        completionCooldown = 0;
    }

    // ==================== 行为树原子动作 ====================

    private boolean hasTarget(AutomatonEntity entity, TaskTarget target) {
        return target != null && target.getPosition() != null;
    }

    private BehaviorNode.Status moveToTarget(AutomatonEntity entity, TaskTarget target) {
        BlockPos pos = target.getPosition();
        if (pos == null) return BehaviorNode.Status.FAILURE;

        double distSq = entity.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (distSq < 9.0) { // 3格
            navigator.stop();
            return BehaviorNode.Status.SUCCESS;
        }

        navigator.navigateTo(pos, 1.0);
        if (navigator.checkStuck()) {
            // 卡住 -> 放弃此目标
            AICompanionMod.LOGGER.info("[BTreeGather] Stuck, abandoning {}", pos);
            return BehaviorNode.Status.FAILURE;
        }
        return BehaviorNode.Status.RUNNING;
    }

    private BehaviorNode.Status mineTarget(AutomatonEntity entity, TaskTarget target) {
        BlockPos pos = target.getPosition();
        if (pos == null) return BehaviorNode.Status.FAILURE;

        // 确保够近
        double distSq = entity.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (distSq > 9.0) {
            navigator.navigateTo(pos, 1.0);
            return BehaviorNode.Status.RUNNING;
        }
        navigator.stop();

        // 目标已消失
        if (entity.level().getBlockState(pos).isAir()) {
            return BehaviorNode.Status.SUCCESS;
        }

        // 直接使用 BreakBlockAction，不走 LLM 技能匹配
        if (blockBreaker == null) {
            String blockName = target.getTargetId();
            String shortName = blockName.contains(":") ? blockName.split(":")[1] : blockName;
            blockBreaker = new BreakBlockAction(BlockMatcher.contains(shortName));
            blockBreaker.setTaskTarget(target);
        }

        if (blockBreaker.tick(entity)) {
            blockBreaker = null;
            return BehaviorNode.Status.SUCCESS;
        }
        return BehaviorNode.Status.RUNNING;
    }

    private BehaviorNode.Status collectItems(AutomatonEntity entity, TaskTarget target) {
        // 简单实现: 自动拾取由实体 tick() 处理
        return BehaviorNode.Status.SUCCESS;
    }

    // ==================== 扫描 ====================

    @Nullable
    private TaskTarget scan() {
        Level level = companion.level();
        BlockPos origin = companion.blockPosition();
        String filter = companion.getGatherFilter();               // "all", "ores", "wood"
        java.util.Set<String> priorities = companion.getGatherPriorityResources();
        String targetBlock = companion.getGatherTargetBlock();     // 如 "minecraft:iron_ore"

        TaskTarget best = null;
        int bestScore = 0;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dy = SCAN_Y_MIN; dy <= SCAN_Y_MAX; dy++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(p);
                    if (state.isAir()) continue;
                    if (state.getBlock().defaultDestroyTime() < 0) continue;

                    String name = state.getBlock().builtInRegistryHolder().key().location().getPath();
                    String fullId = state.getBlock().builtInRegistryHolder().key().location().toString();

                    // ── 指定目标过滤 ──
                    if (targetBlock != null && !targetBlock.isEmpty()) {
                        if (!fullId.equals(targetBlock)) continue;
                    }

                    // ── 类型过滤 ──
                    if ("ores".equals(filter) && !isOreBlock(name)) continue;
                    if ("wood".equals(filter) && !isWoodBlock(name)) continue;

                    // ── 优先级计算 ──
                    int score = DEFAULT_PRIORITY.getOrDefault(name, 0);

                    // 玩家自定义优先级：匹配到的 +50 分（保证排在默认优先级之前）
                    if (!priorities.isEmpty()) {
                        for (String pRes : priorities) {
                            if (name.contains(pRes.toLowerCase())) {
                                score += 50;
                                break;
                            }
                        }
                    }

                    if (score <= 0) continue;

                    double dist = origin.distSqr(p);
                    if (score > bestScore || (score == bestScore && dist < bestDist)) {
                        bestScore = score;
                        bestDist = dist;
                        best = TaskTarget.fromBlockState(p, state);
                    }
                }
            }
        }
        return best;
    }

    /** 判断是否为矿石类方块 */
    private static boolean isOreBlock(String name) {
        return name.contains("_ore") || name.equals("ancient_debris")
            || name.equals("gilded_blackstone");
    }

    /** 判断是否为木材类方块 */
    private static boolean isWoodBlock(String name) {
        return name.contains("_log") || name.endsWith("_wood")
            || name.equals("oak_wood") || name.equals("birch_wood")
            || name.equals("spruce_wood");
    }

    // ==================== Condition 辅助类 ====================

    private interface ConditionCheck {
        boolean check(AutomatonEntity entity, TaskTarget target);
    }

    private interface ActionRunner {
        BehaviorNode.Status run(AutomatonEntity entity, TaskTarget target);
    }
}
