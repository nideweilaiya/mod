package com.aiworkbench.companion.core.action;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.core.action.impl.BreakBlockAction;
import com.aiworkbench.companion.core.action.impl.CleanupTemporaryBlocksAction;
import com.aiworkbench.companion.core.action.impl.EnsureReachBlockAction;
import com.aiworkbench.companion.core.action.impl.EquipItemAction;
import com.aiworkbench.companion.core.action.impl.InteractEntityAction;
import com.aiworkbench.companion.core.action.impl.MoveToAction;
import com.aiworkbench.companion.core.action.impl.MoveUpTargetAction;
import com.aiworkbench.companion.core.action.impl.NavigateToInteractAction;
import com.aiworkbench.companion.core.action.impl.PickupItemAction;
import com.aiworkbench.companion.core.action.impl.PlaceBlockAction;
import com.aiworkbench.companion.core.action.impl.SetNextTargetAction;
import com.aiworkbench.companion.core.action.impl.UseItemAction;
import com.aiworkbench.companion.core.action.impl.WaitAction;
import com.aiworkbench.companion.core.capability.CapabilityDefinition;
import com.aiworkbench.companion.core.capability.CapabilityRegistry;
import com.aiworkbench.companion.core.decision.ActionDecision;
import com.aiworkbench.companion.core.decision.DecisionSource;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

/**
 * Executes one action or a capability-backed action sequence.
 */
public class ActionExecutor {

    private final AutomatonEntity entity;
    private final Map<String, Object> variables = new LinkedHashMap<>();

    private IAction currentAction;
    private String currentActionId;
    private boolean sequenceActive;
    private Queue<ActionDecision> pendingSequence;
    private String activeCapabilityId;

    private List<ActionDecision> repeatBody;
    private Map<String, Object> repeatCondition;
    private List<ActionDecision> repeatPostSequence;
    private ActionTickResult queuedTickResult;

    public ActionExecutor(AutomatonEntity entity) {
        this.entity = entity;
    }

    public boolean isActive() {
        return sequenceActive;
    }

    public void abort() {
        sequenceActive = false;
        currentAction = null;
        currentActionId = null;
        pendingSequence = null;
        activeCapabilityId = null;
        repeatBody = null;
        repeatCondition = null;
        repeatPostSequence = null;
        queuedTickResult = null;
        variables.remove("$pickup_profile");
        variables.clear();
    }

    public void dispatch(ActionDecision decision) {
        if ("EXECUTE_CAPABILITY".equals(decision.actionId())) {
            String capId = (String) decision.params().get("capability_id");
            if (capId != null) {
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
        if (newAction == null) {
            return;
        }
        if (!newAction.canExecute(null)) {
            AICompanionMod.LOGGER.info(
                "[ActionExecutor] canExecute=false for '{}' at dispatch entry, skipping",
                decision.actionId()
            );
            return;
        }

        abort();
        currentAction = newAction;
        currentActionId = decision.actionId();
        sequenceActive = true;
    }

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

        repeatBody = null;
        repeatCondition = null;
        repeatPostSequence = null;

        List<ActionDecision> preRepeat = new ArrayList<>();
        List<ActionDecision> postRepeat = new ArrayList<>();
        CapabilityDefinition.ActionStep repeatStep = null;
        boolean afterRepeat = false;

        for (var step : def.action_sequence) {
            if (step.isRepeat()) {
                repeatStep = step;
                afterRepeat = true;
                continue;
            }

            ActionDecision action = new ActionDecision(
                step.action,
                step.params != null ? new LinkedHashMap<>(step.params) : Map.of(),
                capabilityId + ":" + step.action,
                DecisionSource.RULE_ENGINE
            );

            if (afterRepeat) {
                postRepeat.add(action);
            } else {
                preRepeat.add(action);
            }
        }

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
                ? new LinkedHashMap<>(repeatStep.condition)
                : Map.of();
        }

        repeatPostSequence = postRepeat.isEmpty() ? null : postRepeat;
        AICompanionMod.LOGGER.info(
            "[ActionExecutor] Dispatch capability '{}' ({}) preRepeat={} repeatBody={} postRepeat={}",
            capabilityId,
            CapabilityRegistry.describe(capabilityId),
            preRepeat.size(),
            repeatBody != null ? repeatBody.size() : 0,
            postRepeat.size()
        );
        dispatchSequence(preRepeat, capabilityId);
    }

    public void dispatchSequence(List<ActionDecision> sequence, String capabilityId) {
        if (sequence == null || sequence.isEmpty()) {
            if (tryExpandRepeat() || tryRunRepeatPostSequence()) {
                return;
            }
            AICompanionMod.LOGGER.info("[ActionExecutor] Capability '{}' sequence completed", capabilityId);
            clearPickupProfile();
            sequenceActive = false;
            currentAction = null;
            currentActionId = null;
            activeCapabilityId = null;
            return;
        }

        currentAction = null;
        currentActionId = null;
        sequenceActive = false;
        pendingSequence = new ArrayDeque<>(sequence);
        activeCapabilityId = capabilityId;
        advanceSequence();
    }

    private void advanceSequence() {
        if (pendingSequence == null || pendingSequence.isEmpty()) {
            if (tryExpandRepeat() || tryRunRepeatPostSequence()) {
                return;
            }
            AICompanionMod.LOGGER.info("[ActionExecutor] Capability '{}' sequence completed", activeCapabilityId);
            clearPickupProfile();
            sequenceActive = false;
            currentAction = null;
            currentActionId = null;
            activeCapabilityId = null;
            return;
        }

        ActionDecision next = pendingSequence.poll();
        ActionDecision resolved = resolveVariables(next);
        currentAction = createAction(resolved);
        currentActionId = resolved.actionId();

        if (currentAction == null) {
            AICompanionMod.LOGGER.warn(
                "[ActionExecutor] Failed to create action for '{}', aborting capability '{}'",
                resolved.actionId(),
                activeCapabilityId
            );
                sequenceActive = false;
                pendingSequence = null;
                currentActionId = null;
                repeatBody = null;
            repeatCondition = null;
            repeatPostSequence = null;
            clearPickupProfile();
            return;
        }

        if (!currentAction.canExecute(null)) {
            if (shouldAbortOnCanExecuteFalse(resolved)) {
                queueCanExecuteAbort(resolved);
                return;
            }
            AICompanionMod.LOGGER.info(
                "[ActionExecutor] canExecute=false for '{}' in '{}', skipping to next",
                resolved.actionId(),
                activeCapabilityId
            );
            advanceSequence();
            return;
        }

        sequenceActive = true;
        AICompanionMod.LOGGER.info(
            "[ActionExecutor] Capability '{}' step: {} {}",
            activeCapabilityId,
            resolved.actionId(),
            resolved.params()
        );
    }

    private ActionDecision resolveVariables(ActionDecision decision) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (var entry : decision.params().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s && s.startsWith("$")) {
                Object varValue = variables.get(s);
                resolved.put(entry.getKey(), varValue != null ? varValue : s);
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return new ActionDecision(decision.actionId(), resolved, decision.reasoning(), decision.source());
    }

    public ActionTickResult tick(PerceptionData perception) {
        if (!sequenceActive || currentAction == null) {
            return null;
        }

        ActionResult result = currentAction.execute(perception);
        return switch (result) {
            case SUCCESS -> {
                AICompanionMod.LOGGER.debug("[ActionExecutor] Action SUCCESS");
                maybeQueueColumnTransitionCleanup();
                if (pendingSequence != null && !pendingSequence.isEmpty()) {
                    advanceSequence();
                    yield consumeQueuedTickResult();
                }
                if (tryExpandRepeat() || tryRunRepeatPostSequence()) {
                    yield consumeQueuedTickResult();
                }
                sequenceActive = false;
                currentAction = null;
                currentActionId = null;
                clearPickupProfile();
                yield new ActionTickResult(ActionTickResult.Outcome.COMPLETED, activeCapabilityId);
            }
            case FAILURE -> {
                if (tryHandleRetargetBlock()) {
                    yield consumeQueuedTickResult();
                }
                if (tryHandleRecoverableFailure()) {
                    yield consumeQueuedTickResult();
                }
                if (tryHandleSkippedTarget()) {
                    yield consumeQueuedTickResult();
                }
                AICompanionMod.LOGGER.debug("[ActionExecutor] Action FAILURE, aborting sequence");
                String capId = activeCapabilityId;
                sequenceActive = false;
                currentAction = null;
                currentActionId = null;
                pendingSequence = null;
                activeCapabilityId = null;
                clearPickupProfile();
                yield new ActionTickResult(
                    ActionTickResult.Outcome.FAILED,
                    capId != null ? "capability " + capId + " failed" : "action returned FAILURE"
                );
            }
            case INVENTORY_FULL -> {
                String capId = activeCapabilityId;
                abort();
                yield new ActionTickResult(ActionTickResult.Outcome.FALLBACK_INVENTORY_FULL, capId);
            }
            case TOOL_MISSING -> {
                String capId = activeCapabilityId;
                abort();
                yield new ActionTickResult(ActionTickResult.Outcome.FALLBACK_TOOL_MISSING, capId);
            }
            case IN_PROGRESS -> consumeQueuedTickResult();
        };
    }

    private ActionTickResult consumeQueuedTickResult() {
        if (queuedTickResult == null) {
            return null;
        }
        ActionTickResult result = queuedTickResult;
        queuedTickResult = null;
        return result;
    }

    private boolean tryHandleRecoverableFailure() {
        Object failureType = variables.remove("$recoverable_failure");
        if (!"los_blocked".equals(failureType) || pendingSequence == null) {
            return false;
        }

        BlockPos repositionTarget = resolveTarget(variables.get("$reposition_target"));
        if (repositionTarget == null) {
            return false;
        }

        BlockPos moveTarget = resolveTarget(variables.remove("$reposition_move_target"));
        variables.remove("$reposition_blocker");
        variables.remove("$reposition_target");

        List<ActionDecision> recoverySteps = new ArrayList<>();
        if (moveTarget != null) {
            recoverySteps.add(new ActionDecision(
                "MoveTo",
                Map.of("target", moveTarget),
                activeCapabilityId + ":reposition_move",
                DecisionSource.RULE_ENGINE
            ));
        }
        recoverySteps.add(new ActionDecision(
            "NavigateToInteract",
            Map.of("target", repositionTarget),
            activeCapabilityId + ":reposition_nav",
            DecisionSource.RULE_ENGINE
        ));
        recoverySteps.add(new ActionDecision(
            "EnsureReachBlock",
            Map.of("target", repositionTarget),
            activeCapabilityId + ":reposition_reach",
            DecisionSource.RULE_ENGINE
        ));
        recoverySteps.add(new ActionDecision(
            "BreakBlock",
            Map.of("target", repositionTarget),
            activeCapabilityId + ":reposition_break",
            DecisionSource.RULE_ENGINE
        ));

        prependSteps(recoverySteps);
        currentAction = null;
        currentActionId = null;
        sequenceActive = false;
        AICompanionMod.LOGGER.info(
            "[ActionExecutor] Recoverable LOS failure: queued reposition sequence for {} via {}",
            repositionTarget,
            moveTarget
        );
        advanceSequence();
        return true;
    }

    private boolean tryHandleRetargetBlock() {
        BlockPos blocker = resolveTarget(variables.remove("$retarget_block"));
        if (blocker == null || pendingSequence == null) {
            variables.remove("$retarget_original_target");
            return false;
        }

        BlockPos originalTarget = resolveTarget(variables.remove("$retarget_original_target"));
        variables.remove("$recoverable_failure");
        variables.remove("$reposition_target");
        variables.remove("$reposition_blocker");
        variables.remove("$reposition_move_target");

        List<ActionDecision> recoverySteps = new ArrayList<>();
        recoverySteps.add(new ActionDecision(
            "BreakBlock",
            Map.of("target", blocker),
            activeCapabilityId + ":retarget_blocker",
            DecisionSource.RULE_ENGINE
        ));
        if (originalTarget != null) {
            recoverySteps.add(new ActionDecision(
                "EnsureReachBlock",
                Map.of("target", originalTarget),
                activeCapabilityId + ":retarget_reach",
                DecisionSource.RULE_ENGINE
            ));
            recoverySteps.add(new ActionDecision(
                "BreakBlock",
                Map.of("target", originalTarget),
                activeCapabilityId + ":retarget_original",
                DecisionSource.RULE_ENGINE
            ));
        }

        prependSteps(recoverySteps);
        currentAction = null;
        currentActionId = null;
        sequenceActive = false;
        AICompanionMod.LOGGER.info(
            "[ActionExecutor] Break-glass retarget queued: blocker {} before {}",
            blocker,
            originalTarget
        );
        advanceSequence();
        return true;
    }

    private boolean tryHandleSkippedTarget() {
        Object skipped = variables.remove("$skip_current_target");
        if (!(skipped instanceof BlockPos skippedTarget)) {
            return false;
        }
        currentAction = null;
        currentActionId = null;
        sequenceActive = false;
        AICompanionMod.LOGGER.warn(
            "[ActionExecutor] Skipping unreachable tree target {} and continuing sequence",
            skippedTarget
        );
        if (pendingSequence != null && !pendingSequence.isEmpty()) {
            advanceSequence();
            return true;
        }
        if (tryExpandRepeat() || tryRunRepeatPostSequence()) {
            return true;
        }
        return false;
    }

    private void maybeQueueColumnTransitionCleanup() {
        if (!"SetNextTarget".equals(currentActionId)) {
            return;
        }
        Object transitionRequired = variables.remove("$column_transition_required");
        if (!Boolean.TRUE.equals(transitionRequired) || pendingSequence == null) {
            return;
        }
        prependStep(new ActionDecision(
            "CleanupTemporaryBlocks",
            Map.of(),
            activeCapabilityId + ":column_transition_cleanup",
            DecisionSource.RULE_ENGINE
        ));
        AICompanionMod.LOGGER.info(
            "[ActionExecutor] Column transition: inserted CleanupTemporaryBlocks before next target navigation"
        );
    }

    private void prependStep(ActionDecision decision) {
        if (pendingSequence instanceof ArrayDeque<ActionDecision> deque) {
            deque.addFirst(decision);
            return;
        }
        ArrayDeque<ActionDecision> rebuilt = new ArrayDeque<>();
        rebuilt.add(decision);
        rebuilt.addAll(pendingSequence);
        pendingSequence = rebuilt;
    }

    private void prependSteps(List<ActionDecision> steps) {
        for (int index = steps.size() - 1; index >= 0; index--) {
            prependStep(steps.get(index));
        }
    }

    private boolean shouldAbortOnCanExecuteFalse(ActionDecision decision) {
        if (!"NavigateToInteract".equals(decision.actionId())) {
            return false;
        }
        BlockPos target = resolveTarget(decision.params().get("target"));
        return target != null && target.getY() < entity.blockPosition().getY() - 2;
    }

    private void queueCanExecuteAbort(ActionDecision decision) {
        String capabilityId = activeCapabilityId;
        String detail = "prerequisite " + decision.actionId() + " unavailable for target below current height";
        AICompanionMod.LOGGER.warn(
            "[ActionExecutor] canExecute=false for prerequisite '{}' in '{}', aborting sequence: {}",
            decision.actionId(),
            capabilityId,
            detail
        );
        abort();
        queuedTickResult = new ActionTickResult(
            ActionTickResult.Outcome.FAILED,
            capabilityId != null ? detail + " [" + capabilityId + "]" : detail
        );
    }

    @SuppressWarnings("unchecked")
    private IAction createAction(ActionDecision decision) {
        return switch (decision.actionId()) {
            case "MoveTo" -> {
                BlockPos target = resolveTarget(decision.params().get("target"));
                if (target == null) {
                    yield null;
                }
                yield new MoveToAction(entity, target);
            }
            case "NavigateToInteract" -> {
                BlockPos target = resolveTarget(decision.params().get("target"));
                if (target == null) {
                    yield null;
                }
                yield new NavigateToInteractAction(entity, target);
            }
            case "BreakBlock" -> {
                BlockPos target = resolveTarget(decision.params().get("target"));
                if (target == null) {
                    yield null;
                }
                yield new BreakBlockAction(entity, target, variables);
            }
            case "EnsureReachBlock" -> {
                BlockPos target = resolveTarget(decision.params().get("target"));
                if (target == null) {
                    yield null;
                }
                yield new EnsureReachBlockAction(entity, target, variables);
            }
            case "CleanupTemporaryBlocks" -> new CleanupTemporaryBlocksAction(entity, variables);
            case "PlaceBlock" -> {
                BlockPos target = resolveTarget(decision.params().get("target"));
                String keyword = (String) decision.params().getOrDefault("block_keyword", null);
                if (target == null) {
                    yield null;
                }
                yield new PlaceBlockAction(entity, target, keyword);
            }
            case "UseItem" -> {
                BlockPos target = resolveTarget(decision.params().get("target"));
                if (target != null) {
                    yield new UseItemAction(entity, target);
                }
                yield UseItemAction.onAir(entity);
            }
            case "PickupItem" -> {
                String filter = (String) decision.params().get("item_filter");
                String profile = (String) decision.params().getOrDefault("profile", "default");
                variables.put("$pickup_profile", profile);
                if (filter != null) {
                    yield PickupItemAction.byItemId(entity, filter, profile);
                }
                yield new PickupItemAction(entity, e -> true, profile);
            }
            case "InteractEntity" -> {
                UUID uuid = resolveUUID(decision.params().get("uuid"));
                if (uuid == null) {
                    yield null;
                }
                yield new InteractEntityAction(entity, uuid);
            }
            case "Wait" -> {
                int ticks = ((Number) decision.params().getOrDefault("ticks", 20)).intValue();
                yield new WaitAction(ticks);
            }
            case "EquipItem" -> {
                String keyword = (String) decision.params().get("keyword");
                if (keyword == null) {
                    keyword = (String) decision.params().get("item_keyword");
                }
                if (keyword == null) {
                    yield null;
                }
                yield new EquipItemAction(entity, keyword);
            }
            case "MoveUpTarget" -> new MoveUpTargetAction(variables);
            case "SetNextTarget" -> new SetNextTargetAction(entity, variables);
            case "idle" -> null;
            default -> {
                AICompanionMod.LOGGER.warn("[ActionExecutor] Unknown action: {}", decision.actionId());
                yield null;
            }
        };
    }

    private BlockPos resolveTarget(Object target) {
        if (target == null) {
            return null;
        }
        if (target instanceof BlockPos blockPos) {
            return blockPos;
        }
        if (target instanceof List<?> list && list.size() == 3) {
            return new BlockPos(
                ((Number) list.get(0)).intValue(),
                ((Number) list.get(1)).intValue(),
                ((Number) list.get(2)).intValue()
            );
        }
        if (target instanceof String ref && ref.startsWith("$")) {
            Object resolved = variables.get(ref);
            if (resolved instanceof BlockPos blockPos) {
                return blockPos;
            }
        }
        return null;
    }

    private UUID resolveUUID(Object uuidParam) {
        if (uuidParam == null) {
            return null;
        }
        if (uuidParam instanceof UUID uuid) {
            return uuid;
        }
        if (uuidParam instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    public void setVariable(String name, Object value) {
        variables.put(name, value);
    }

    private void clearPickupProfile() {
        variables.remove("$pickup_profile");
    }

    public Object getVariable(String name) {
        return variables.get(name);
    }

    public String getCurrentActionId() {
        return currentActionId;
    }

    private boolean tryExpandRepeat() {
        if (repeatBody == null || repeatBody.isEmpty() || repeatCondition == null) {
            return false;
        }

        String listVar = (String) repeatCondition.get("list_not_empty");
        if (listVar != null) {
            Object value = variables.get(listVar);
            if (value instanceof List<?> list && !list.isEmpty()) {
                if ("$tree_cut_list".equals(listVar)) {
                    list.removeIf(entry -> {
                        if (!(entry instanceof BlockPos pos)) {
                            return true;
                        }
                        return entity.level().getBlockState(pos).isAir();
                    });
                    if (list.isEmpty()) {
                        AICompanionMod.LOGGER.info("[ActionExecutor] Repeat done: list '{}' exhausted after pruning", listVar);
                        repeatBody = null;
                        repeatCondition = null;
                        return false;
                    }
                }
                pendingSequence = new ArrayDeque<>(repeatBody);
                AICompanionMod.LOGGER.info(
                    "[ActionExecutor] Repeat expand: {} steps, list size={}",
                    repeatBody.size(),
                    list.size()
                );
                advanceSequence();
                return true;
            }
            AICompanionMod.LOGGER.info("[ActionExecutor] Repeat done: list '{}' empty or invalid", listVar);
            repeatBody = null;
            repeatCondition = null;
            return false;
        }

        boolean firstEntry = !variables.containsKey("$current_target");
        if (firstEntry) {
            Object foundBlock = variables.get("$found_block.pos");
            if (foundBlock instanceof BlockPos blockPos) {
                variables.put("$current_target", blockPos);
            } else {
                return false;
            }
        }

        if (!firstEntry) {
            String keyword = (String) repeatCondition.getOrDefault(
                "block_matches",
                repeatCondition.get("block_above")
            );
            BlockPos currentTarget = (BlockPos) variables.get("$current_target");
            if (currentTarget == null || keyword == null) {
                repeatBody = null;
                repeatCondition = null;
                return false;
            }
            String blockName = entity.level().getBlockState(currentTarget).getBlock().getDescriptionId();
            if (!blockName.contains(keyword)) {
                AICompanionMod.LOGGER.info(
                    "[ActionExecutor] Repeat done: current={} '{}' not matching '{}'",
                    currentTarget,
                    blockName,
                    keyword
                );
                repeatBody = null;
                repeatCondition = null;
                return false;
            }
        }

        pendingSequence = new ArrayDeque<>(repeatBody);
        AICompanionMod.LOGGER.info(
            "[ActionExecutor] Repeat expand: {} steps, target={}",
            repeatBody.size(),
            variables.get("$current_target")
        );
        advanceSequence();
        return true;
    }

    private boolean tryRunRepeatPostSequence() {
        if (repeatPostSequence == null || repeatPostSequence.isEmpty()) {
            return false;
        }
        pendingSequence = new ArrayDeque<>(repeatPostSequence);
        repeatPostSequence = null;
        AICompanionMod.LOGGER.info("[ActionExecutor] Repeat post sequence: {} steps", pendingSequence.size());
        advanceSequence();
        return true;
    }

    public record ActionTickResult(Outcome outcome, String detail) {
        public enum Outcome {
            COMPLETED,
            FAILED,
            FALLBACK_INVENTORY_FULL,
            FALLBACK_TOOL_MISSING
        }
    }
}
