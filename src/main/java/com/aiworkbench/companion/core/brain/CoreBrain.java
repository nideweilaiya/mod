package com.aiworkbench.companion.core.brain;

import com.aiworkbench.companion.core.decision.ActionDecision;
import com.aiworkbench.companion.core.decision.DecisionSource;
import com.aiworkbench.companion.core.decision.IDecisionMaker;
import com.aiworkbench.companion.core.decision.LLMDecisionMaker;
import com.aiworkbench.companion.core.decision.MemorySnapshot;
import com.aiworkbench.companion.core.decision.RuleBasedDecisionMaker;
import com.aiworkbench.companion.core.perception.PerceptionData;
import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The new-framework companion brain.
 *
 * <p>This class owns mode selection and LLM control tier. Minecraft-facing code
 * should feed it perception data and execute the returned ActionDecision.</p>
 */
public class CoreBrain {

    private CompanionMode mode = CompanionMode.FOLLOW;
    private LLMControlTier llmTier = LLMControlTier.CHAT_ONLY;
    private final RuleBasedDecisionMaker ruleDecisionMaker = new RuleBasedDecisionMaker();
    private LLMDecisionMaker llmDecisionMaker;

    public CompanionMode mode() {
        return mode;
    }

    public String modeId() {
        return mode.id();
    }

    public void setMode(String modeId) {
        this.mode = CompanionMode.fromId(modeId);
    }

    public LLMControlTier llmTier() {
        return llmTier;
    }

    public void setLlmTier(LLMControlTier llmTier) {
        this.llmTier = llmTier != null ? llmTier : LLMControlTier.CHAT_ONLY;
    }

    public void setLlmDecisionMaker(LLMDecisionMaker llmDecisionMaker) {
        this.llmDecisionMaker = llmDecisionMaker;
    }

    public Set<String> authorizedCapabilities() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(mode.authorizedCapabilities()));
    }

    public ActionDecision decide(PerceptionData perception, BlockPos followTarget) {
        IDecisionMaker decisionMaker = selectDecisionMaker();
        MemorySnapshot memory = MemorySnapshot.withAuth(mode.authorizedCapabilities());
        ActionDecision decision = decisionMaker.decide(perception, memory);
        return applyModeFallback(decision, followTarget);
    }

    private IDecisionMaker selectDecisionMaker() {
        if (llmTier.allowsLLMDecision() && llmDecisionMaker != null) {
            return llmDecisionMaker;
        }
        return ruleDecisionMaker;
    }

    private ActionDecision applyModeFallback(ActionDecision decision, BlockPos followTarget) {
        if (decision == null) return ActionDecision.IDLE;
        if (!"idle".equals(decision.actionId())) return decision;

        if (mode.followOnIdle() && followTarget != null) {
            return new ActionDecision(
                "MoveTo",
                java.util.Map.of("target", followTarget, "speed", 1.0),
                "mode " + mode.id() + " idle fallback: follow player",
                DecisionSource.RULE_ENGINE
            );
        }
        return decision;
    }
}
