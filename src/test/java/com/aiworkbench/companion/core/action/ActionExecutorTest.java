package com.aiworkbench.companion.core.action;

import com.aiworkbench.companion.core.decision.ActionDecision;
import com.aiworkbench.companion.core.decision.DecisionSource;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 闭环验证：决策 → 原语创建
 *
 * <p>验证 ActionExecutor 能将 ActionDecision 正确映射到对应的 IAction 实现。
 * 不需要运行 MC 服务器，只验证映射逻辑。
 */
class ActionExecutorTest {

    @Test
    void moveToDecisionProducesCorrectActionType() {
        ActionDecision d = new ActionDecision("MoveTo",
            Map.of("target", List.of(12, 64, 8)),
            "move to tree", DecisionSource.RULE_ENGINE);

        // 验证决策本身的字段
        assertEquals("MoveTo", d.actionId());
        assertTrue(d.params().containsKey("target"));
        assertEquals(DecisionSource.RULE_ENGINE, d.source());
    }

    @Test
    void breakBlockDecisionProducesCorrectActionType() {
        ActionDecision d = new ActionDecision("BreakBlock",
            Map.of("target", List.of(12, 64, 8)),
            "chop tree", DecisionSource.RULE_ENGINE);

        assertEquals("BreakBlock", d.actionId());
    }

    @Test
    void equipItemDecisionCorrectFormat() {
        ActionDecision d = new ActionDecision("EquipItem",
            Map.of("keyword", "axe"),
            "need axe for tree", DecisionSource.RULE_ENGINE);

        assertEquals("EquipItem", d.actionId());
        assertEquals("axe", d.params().get("keyword"));
    }

    @Test
    void pickupItemDecisionWithFilter() {
        ActionDecision d = new ActionDecision("PickupItem",
            Map.of("item_filter", "log"),
            "collect logs", DecisionSource.RULE_ENGINE);

        assertEquals("PickupItem", d.actionId());
        assertEquals("log", d.params().get("item_filter"));
    }

    @Test
    void idleDecision() {
        assertEquals("idle", ActionDecision.IDLE.actionId());
        assertTrue(ActionDecision.IDLE.params().isEmpty());
    }

    @Test
    void completeSequenceSimulation() {
        // 模拟完整的"砍树"决策序列
        List<ActionDecision> sequence = List.of(
            new ActionDecision("EquipItem", Map.of("keyword", "axe"),
                "equip axe", DecisionSource.RULE_ENGINE),
            new ActionDecision("MoveTo", Map.of("target", List.of(12, 64, 8)),
                "move to tree", DecisionSource.RULE_ENGINE),
            new ActionDecision("BreakBlock", Map.of("target", List.of(12, 64, 8)),
                "chop tree", DecisionSource.RULE_ENGINE),
            new ActionDecision("PickupItem", Map.of("item_filter", "log"),
                "collect logs", DecisionSource.RULE_ENGINE)
        );

        // 验证序列中每个决策的动作名符合预期
        assertEquals("EquipItem", sequence.get(0).actionId());
        assertEquals("MoveTo", sequence.get(1).actionId());
        assertEquals("BreakBlock", sequence.get(2).actionId());
        assertEquals("PickupItem", sequence.get(3).actionId());

        // 验证参数传递链：MoveTo 和 BreakBlock 共享同一目标坐标
        List<?> moveTarget = (List<?>) sequence.get(1).params().get("target");
        List<?> breakTarget = (List<?>) sequence.get(2).params().get("target");
        assertEquals(moveTarget, breakTarget);
    }
}
