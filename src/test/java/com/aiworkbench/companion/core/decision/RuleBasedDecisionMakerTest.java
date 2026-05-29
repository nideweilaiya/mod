package com.aiworkbench.companion.core.decision;

import com.aiworkbench.companion.core.perception.PerceptionData;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 闭环验证：规则引擎 → 决策 → 动作映射
 *
 * <p>验证 P0-P3 链路的完整性：
 * PerceptionData → RuleBasedDecisionMaker.decide() → ActionDecision → ActionExecutor
 */
class RuleBasedDecisionMakerTest {

    private final RuleBasedDecisionMaker engine = new RuleBasedDecisionMaker();

    @Test
    void idleWhenNoThreatsNoNeeds() {
        PerceptionData p = perceptionWithSelf(20, 20);
        MemorySnapshot m = MemorySnapshot.empty();

        ActionDecision d = engine.decide(p, m);
        // 无威胁、无饥饿、无资源需求 → 应返回 IDLE
        assertEquals("idle", d.actionId());
    }

    @Test
    void equipWeaponWhenThreatNearby() {
        PerceptionData p = perceptionWithSelf(20, 20);
        p.threats = List.of(
            new PerceptionData.NearbyEntity("zombie", UUID.randomUUID(),
                new BlockPos(10, 64, 12), 3.0, true)
        );
        MemorySnapshot m = MemorySnapshot.empty();

        ActionDecision d = engine.decide(p, m);
        // 有近距离威胁 → 应装备武器
        assertEquals("EquipItem", d.actionId());
        assertTrue(d.params().containsKey("keyword"));
    }

    @Test
    void moveToTreeWhenWoodNeeded() {
        PerceptionData p = perceptionWithSelf(20, 20);
        // 背包没有木头
        p.inventorySummary = Map.of("stone", 3);
        // 附近有树
        p.nearbyBlocks = List.of(
            new PerceptionData.NearbyBlock("oak_log", new BlockPos(12, 64, 8), 3.0, true)
        );
        MemorySnapshot m = MemorySnapshot.empty();

        ActionDecision d = engine.decide(p, m);
        // 需要木头 + 附近有树 → 移动到树
        assertEquals("MoveTo", d.actionId());
        assertNotNull(d.params().get("target"));
    }

    @Test
    void moveToFoodWhenHungry() {
        PerceptionData p = perceptionWithSelf(20, 4); // 饥饿值 4
        p.nearbyEntities = List.of(
            new PerceptionData.NearbyEntity("cow", UUID.randomUUID(),
                new BlockPos(15, 64, 10), 5.0, false)
        );
        MemorySnapshot m = MemorySnapshot.empty();

        ActionDecision d = engine.decide(p, m);
        // 饥饿 + 附近有动物 → 移动到食物源
        assertEquals("MoveTo", d.actionId());
    }

    @Test
    void retreatWhenHealthLow() {
        PerceptionData p = perceptionWithSelf(4, 20); // 血量 4
        MemorySnapshot m = MemorySnapshot.empty();

        ActionDecision d = engine.decide(p, m);
        // 血量低 → 应有动作（逃跑或装备）
        assertNotEquals("idle", d.actionId());
    }

    @Test
    void decisionHasCorrectSource() {
        PerceptionData p = perceptionWithSelf(4, 20);
        MemorySnapshot m = MemorySnapshot.empty();

        ActionDecision d = engine.decide(p, m);
        assertEquals(DecisionSource.RULE_ENGINE, d.source());
    }

    // ==================== 辅助方法 ====================

    private PerceptionData perceptionWithSelf(float health, int hunger) {
        PerceptionData p = new PerceptionData();
        p.self = new PerceptionData.SelfStatus(health, hunger, new BlockPos(10, 64, 10), "idle");
        p.nearbyBlocks = List.of();
        p.nearbyEntities = List.of();
        p.inventorySummary = Map.of();
        p.threats = List.of();
        p.scanTimestamp = System.currentTimeMillis();
        return p;
    }
}
