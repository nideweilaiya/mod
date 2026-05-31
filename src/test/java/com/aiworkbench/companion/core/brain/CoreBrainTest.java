package com.aiworkbench.companion.core.brain;

import com.aiworkbench.companion.core.decision.ActionDecision;
import com.aiworkbench.companion.core.perception.PerceptionData;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoreBrainTest {

    @Test
    void followModeTurnsIdleIntoFollowAction() {
        CoreBrain brain = new CoreBrain();
        brain.setMode("follow");

        ActionDecision decision = brain.decide(emptyPerception(), new BlockPos(3, 64, 3));

        assertEquals("MoveTo", decision.actionId());
        assertEquals(new BlockPos(3, 64, 3), decision.params().get("target"));
    }

    @Test
    void chopModeAuthorizesLogGatheringOnly() {
        CoreBrain brain = new CoreBrain();
        brain.setMode("chop");

        assertTrue(brain.authorizedCapabilities().contains("gather_logs"));
        assertFalse(brain.authorizedCapabilities().contains("gather_ores"));
    }

    @Test
    void autonomousModeStartsWithKnownGatherCapabilities() {
        CoreBrain brain = new CoreBrain();
        brain.setMode("autonomous");

        assertTrue(brain.authorizedCapabilities().contains("gather_logs"));
        assertTrue(brain.authorizedCapabilities().contains("gather_ores"));
    }

    @Test
    void tierThreeIsMemoryDecisionTier() {
        assertTrue(LLMControlTier.MEMORY_DECISION.allowsLLMDecision());
        assertTrue(LLMControlTier.MEMORY_DECISION.usesMemory());
    }

    private static PerceptionData emptyPerception() {
        PerceptionData p = new PerceptionData();
        p.self = new PerceptionData.SelfStatus(20, 20, new BlockPos(0, 64, 0), "idle");
        p.nearbyBlocks = List.of();
        p.nearbyEntities = List.of();
        p.inventorySummary = Map.of();
        p.threats = List.of();
        return p;
    }
}
