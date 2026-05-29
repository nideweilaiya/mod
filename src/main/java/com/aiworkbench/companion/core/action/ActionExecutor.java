package com.aiworkbench.companion.core.action;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.core.action.impl.*;
import com.aiworkbench.companion.core.capability.CapabilityDefinition;
import com.aiworkbench.companion.core.capability.CapabilityRegistry;
import com.aiworkbench.companion.core.decision.ActionDecision;
import com.aiworkbench.companion.core.decision.DecisionSource;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.entity.AutomatonEntity;

import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * 动作序列执行器 — 执行层的核心。
 *
 * <p>从评估层接收 {@link ActionDecision}，创建对应的 {@link IAction} 实例，
 * 每 tick 驱动一个原语直到返回 SUCCESS 或 FAILURE。</p>
 *
 * <h3>单动作模式</h3>
 * <pre>{@code
 *   executor.dispatch(decision);
 *   // 每 tick: executor.tick(perception) → null=进行中, ActionTickResult=完成
 * }</pre>
 *
 * <h3>序列模式（能力驱动）</h3>
 * <pre>{@code
 *   executor.dispatchCapability("gather_logs");
 *   // 自动逐个执行: NavigateToInteract → EquipItem → BreakBlock → PickupItem
 *   // 当前动作 SUCCESS → 自动下一个
 *   // 任一动作 FAILURE → 序列终止，回报评估层
 * }</pre>
 */
public class ActionExecutor {

    private final AutomatonEntity entity;
    private IAction currentAction;
    private boolean sequenceActive;

    // 序列上下文
    private final Map<String, Object> variables = new LinkedHashMap<>();
    private Queue<ActionDecision> pendingSequence; // 待执行的剩余动作
    private String activeCapabilityId; // 当前正在执行的能力 ID（用于日志）
    // repeat 控制结构
    private List<ActionDecision> repeatBody; // repeat 循环体（完成后再插入）
    private Map<String, Object> repeatCondition; // repeat 退出条件

    public ActionExecutor(AutomatonEntity entity) {
        this.entity = entity;
    }

    /** 当前是否有正在执行的序列 */
    public boolean isActive() {
        return sequenceActive;
    }

    /** 中断当前序列 */
    public void abort() {
        sequenceActive = false;
        currentAction = null;
        pendingSequence = null;
        activeCapabilityId = null;
        repeatBody = null;
        repeatCondition = null;
        variables.clear();
    }

    // ==================== 单动作派遣 ====================

    /**
     * 根据 ActionDecision 创建对应的 IAction 并开始执行。
     * 如果已有序列在执行，先中断。
     * 创建前调用 canExecute 检查前置条件。
     */
    public void dispatch(ActionDecision decision) {
        // EXECUTE_CAPABILITY → 注入变量 + 委托给能力派遣
        if ("EXECUTE_CAPABILITY".equals(decision.actionId())) {
            String capId = (String) decision.params().get("capability_id");
            if (capId != null) {
                // 注入 $found_block.pos 等变量引用
                for (var entry : decision.params().entrySet()) {
                    if (entry.getKey().startsWith("$")) {
                        setVariable(entry.getKey(), entry.getValue());
                    }
                }
                dispatchCapability(capId);
            }
            return;
        }

        IAction newAction = createAction(decision);
        if (newAction == null) return;
        if (!newAction.canExecute(null)) {
            AICompanionMod.LOGGER.debug("[ActionExecutor] canExecute=false for {}, skipping", decision.actionId());
            return;
        }
        abort();
        currentAction = newAction;
        sequenceActive = true;
    }

    // ==================== 序列派遣（能力驱动） ====================

    /**
     * 从 CapabilityRegistry 加载能力定义的原语序列并开始执行。
     * 序列中的 $found_block.pos 变量需在调用前通过 setVariable() 注入。
     */
    public void dispatchCapability(String capabilityId) {
        CapabilityDefinition def = CapabilityRegistry.get(capabilityId);
        if (def == null) {
            AICompanionMod.LOGGER.warn("[ActionExecutor] Unknown capability: {}", capabilityId);
            return;
        }
        if (def.action_sequence == null || def.action_sequence.isEmpty()) {
            AICompanionMod.LOGGER.warn("[ActionExecutor] Empty action_sequence for capability: {}", capabilityId);
            return;
        }

        // 分离 pre-repeat 步骤和 repeat 体
        List<ActionDecision> preRepeat = new ArrayList<>();
        CapabilityDefinition.ActionStep repeatStep = null;

        for (var step : def.action_sequence) {
            if (step.isRepeat()) {
                repeatStep = step;
                break; // repeat 之后不应再有其他步骤
            }
            preRepeat.add(new ActionDecision(
                step.action,
                step.params != null ? new LinkedHashMap<>(step.params) : Map.of(),
                capabilityId + ":" + step.action,
                DecisionSource.RULE_ENGINE
            ));
        }

        // 设置 repeat 循环体
        if (repeatStep != null && repeatStep.body != null) {
            repeatBody = new ArrayList<>();
            for (var bodyStep : repeatStep.body) {
                repeatBody.add(new ActionDecision(
                    bodyStep.action,
                    bodyStep.params != null ? new LinkedHashMap<>(bodyStep.params) : Map.of(),
                    capabilityId + ":" + bodyStep.action,
                    DecisionSource.RULE_ENGINE
                ));
            }
            repeatCondition = repeatStep.condition != null
                ? new LinkedHashMap<>(repeatStep.condition) : Map.of();
        }

        dispatchSequence(preRepeat, capabilityId);
    }

    /**
     * 加载一个 ActionDecision 序列并开始逐个执行。
     * 当前动作 SUCCESS → 自动弹出下一个动作。
     * 任一动作 FAILURE → 序列终止。
     *
     * <p>注意：不使用 abort()，因为 variables 可能已在 dispatch() 中注入。
     * 仅重置序列状态。</p>
     */
    public void dispatchSequence(List<ActionDecision> sequence, String capabilityId) {
        if (sequence == null || sequence.isEmpty()) return;

        // 重置序列状态，但保留 variables（调用者可能已注入 $found_block.pos 等变量）
        currentAction = null;
        sequenceActive = false;
        pendingSequence = new ArrayDeque<>(sequence);
        activeCapabilityId = capabilityId;

        advanceSequence();
    }

    /** 弹出序列中下一个动作并开始执行 */
    private void advanceSequence() {
        if (pendingSequence == null || pendingSequence.isEmpty()) {
            // 序列全部完成
            AICompanionMod.LOGGER.info("[ActionExecutor] Capability '{}' sequence completed", activeCapabilityId);
            sequenceActive = false;
            currentAction = null;
            activeCapabilityId = null;
            return;
        }

        ActionDecision next = pendingSequence.poll();
        // 解析变量引用（$found_block.pos）
        ActionDecision resolved = resolveVariables(next);
        currentAction = createAction(resolved);

        if (currentAction == null) {
            AICompanionMod.LOGGER.warn("[ActionExecutor] Failed to create action for '{}', aborting capability '{}'",
                resolved.actionId(), activeCapabilityId);
            sequenceActive = false;
            pendingSequence = null;
            repeatBody = null;
            repeatCondition = null;
            return;
        }

        if (!currentAction.canExecute(null)) {
            // canExecute=false 不一定是错误 — 可能是"已经满足"（如已装备斧头）
            // 跳过当前步骤，继续执行下一个
            AICompanionMod.LOGGER.debug("[ActionExecutor] canExecute=false for '{}' in '{}', skipping to next",
                resolved.actionId(), activeCapabilityId);
            advanceSequence();
            return;
        }

        sequenceActive = true;
        AICompanionMod.LOGGER.debug("[ActionExecutor] Capability '{}' step: {}", activeCapabilityId, resolved.actionId());
    }

    /** 解析 ActionDecision 中的变量引用 */
    private ActionDecision resolveVariables(ActionDecision decision) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (var entry : decision.params().entrySet()) {
            Object val = entry.getValue();
            if (val instanceof String s && s.startsWith("$")) {
                Object varVal = variables.get(s);
                resolved.put(entry.getKey(), varVal != null ? varVal : s);
            } else {
                resolved.put(entry.getKey(), val);
            }
        }
        return new ActionDecision(decision.actionId(), resolved, decision.reasoning(), decision.source());
    }

    // ==================== 每 tick 驱动 ====================

    /**
     * 每 tick 驱动当前动作。
     *
     * @param perception 当前感知快照
     * @return 当前动作完成时的结果汇总，未完成时返回 null
     */
    public ActionTickResult tick(PerceptionData perception) {
        if (!sequenceActive || currentAction == null) {
            return null;
        }

        ActionResult result = currentAction.execute(perception);

        return switch (result) {
            case SUCCESS -> {
                AICompanionMod.LOGGER.debug("[ActionExecutor] Action SUCCESS");
                // 有后续动作 → 自动衔接
                if (pendingSequence != null && !pendingSequence.isEmpty()) {
                    advanceSequence();
                    yield null;
                }
                // 序列空 → 检查 repeat 条件
                if (tryExpandRepeat()) {
                    yield null; // repeat 扩展成功，继续执行
                }
                // 无 repeat 或有条件不满足 → 序列结束
                sequenceActive = false;
                currentAction = null;
                yield new ActionTickResult(ActionTickResult.Outcome.COMPLETED, activeCapabilityId);
            }
            case FAILURE -> {
                AICompanionMod.LOGGER.debug("[ActionExecutor] Action FAILURE, aborting sequence");
                String capId = activeCapabilityId;
                sequenceActive = false;
                currentAction = null;
                pendingSequence = null;
                activeCapabilityId = null;
                yield new ActionTickResult(ActionTickResult.Outcome.FAILED,
                    capId != null ? "capability " + capId + " failed" : "action returned FAILURE");
            }
            case IN_PROGRESS -> null;
        };
    }

    // ==================== 决策 → 原语映射 ====================

    @SuppressWarnings("unchecked")
    private IAction createAction(ActionDecision decision) {
        return switch (decision.actionId()) {
            case "MoveTo" -> {
                BlockPos target = resolveTarget(decision.params().get("target"));
                if (target == null) yield null;
                yield new MoveToAction(entity, target);
            }
            case "NavigateToInteract" -> {
                BlockPos target = resolveTarget(decision.params().get("target"));
                if (target == null) yield null;
                yield new NavigateToInteractAction(entity, target);
            }
            case "BreakBlock" -> {
                BlockPos target = resolveTarget(decision.params().get("target"));
                if (target == null) yield null;
                yield new BreakBlockAction(entity, target);
            }
            case "PlaceBlock" -> {
                BlockPos target = resolveTarget(decision.params().get("target"));
                String keyword = (String) decision.params().getOrDefault("block_keyword", null);
                if (target == null) yield null;
                yield new PlaceBlockAction(entity, target, keyword);
            }
            case "UseItem" -> {
                BlockPos target = resolveTarget(decision.params().get("target"));
                if (target != null) {
                    yield new UseItemAction(entity, target);
                } else {
                    yield UseItemAction.onAir(entity);
                }
            }
            case "PickupItem" -> {
                String filter = (String) decision.params().get("item_filter");
                if (filter != null) {
                    yield PickupItemAction.byItemId(entity, filter);
                } else {
                    yield new PickupItemAction(entity);
                }
            }
            case "InteractEntity" -> {
                UUID uuid = resolveUUID(decision.params().get("uuid"));
                if (uuid == null) yield null;
                yield new InteractEntityAction(entity, uuid);
            }
            case "Wait" -> {
                int ticks = ((Number) decision.params().getOrDefault("ticks", 20)).intValue();
                yield new WaitAction(ticks);
            }
            case "EquipItem" -> {
                String keyword = (String) decision.params().get("keyword");
                if (keyword == null) keyword = (String) decision.params().get("item_keyword");
                if (keyword == null) yield null;
                yield new EquipItemAction(entity, keyword);
            }
            case "MoveUpTarget" -> new MoveUpTargetAction(variables);
            case "SetNextTarget" -> new SetNextTargetAction(variables);
            case "idle" -> null;
            default -> {
                AICompanionMod.LOGGER.warn("[ActionExecutor] Unknown action: {}", decision.actionId());
                yield null;
            }
        };
    }

    // ==================== 参数解析 ====================

    private BlockPos resolveTarget(Object target) {
        if (target == null) return null;
        if (target instanceof BlockPos bp) return bp;
        if (target instanceof List<?> list && list.size() == 3) {
            return new BlockPos(
                ((Number) list.get(0)).intValue(),
                ((Number) list.get(1)).intValue(),
                ((Number) list.get(2)).intValue()
            );
        }
        if (target instanceof String ref && ref.startsWith("$")) {
            Object resolved = variables.get(ref);
            if (resolved instanceof BlockPos bp) return bp;
        }
        return null;
    }

    private UUID resolveUUID(Object uuidParam) {
        if (uuidParam == null) return null;
        if (uuidParam instanceof UUID u) return u;
        if (uuidParam instanceof String s) {
            try { return UUID.fromString(s); }
            catch (IllegalArgumentException e) { return null; }
        }
        return null;
    }

    /** 存储变量（供序列中的 $found_block.pos 等变量引用使用） */
    public void setVariable(String name, Object value) {
        variables.put(name, value);
    }

    // ==================== repeat 控制结构 ====================

    /**
     * repeat 控制结构 — do-while 语义。
     * 支持两种条件模式：
     * <ul>
     *   <li>{@code list_not_empty} — 检查变量列表是否非空（全树扫描模式）</li>
     *   <li>{@code block_matches} — 检查 $current_target 方块名匹配关键字（单列上移模式）</li>
     * </ul>
     */
    private boolean tryExpandRepeat() {
        if (repeatBody == null || repeatBody.isEmpty() || repeatCondition == null) {
            return false;
        }

        // ---- 条件模式 1: list_not_empty ----
        String listVar = (String) repeatCondition.get("list_not_empty");
        if (listVar != null) {
            Object val = variables.get(listVar);
            if (val instanceof List<?> list && !list.isEmpty()) {
                pendingSequence = new ArrayDeque<>(repeatBody);
                AICompanionMod.LOGGER.info("[ActionExecutor] Repeat expand: {} steps, list size={}",
                    repeatBody.size(), list.size());
                advanceSequence();
                return true;
            }
            AICompanionMod.LOGGER.info("[ActionExecutor] Repeat done: list '{}' empty or invalid", listVar);
            repeatBody = null;
            repeatCondition = null;
            return false;
        }

        // ---- 条件模式 2: block_matches (单列上移，兼容 gather_ores) ----
        boolean firstEntry = !variables.containsKey("$current_target");
        if (firstEntry) {
            Object foundBlock = variables.get("$found_block.pos");
            if (foundBlock instanceof BlockPos bp) {
                variables.put("$current_target", bp);
            } else {
                return false;
            }
        }

        if (!firstEntry) {
            String keyword = (String) repeatCondition.getOrDefault("block_matches",
                repeatCondition.get("block_above")); // 兼容旧配置
            BlockPos currentTarget = (BlockPos) variables.get("$current_target");
            if (currentTarget == null || keyword == null) {
                repeatBody = null; repeatCondition = null; return false;
            }
            String blockName = entity.level().getBlockState(currentTarget).getBlock().getDescriptionId();
            if (!blockName.contains(keyword)) {
                AICompanionMod.LOGGER.info("[ActionExecutor] Repeat done: current={} '{}' not matching '{}'",
                    currentTarget, blockName, keyword);
                repeatBody = null; repeatCondition = null;
                return false;
            }
        }

        pendingSequence = new ArrayDeque<>(repeatBody);
        AICompanionMod.LOGGER.info("[ActionExecutor] Repeat expand: {} steps, target={}",
            repeatBody.size(), variables.get("$current_target"));
        advanceSequence();
        return true;
    }

    // ==================== 内部类型 ====================

    public record ActionTickResult(Outcome outcome, String detail) {
        public enum Outcome { COMPLETED, FAILED }
    }
}
