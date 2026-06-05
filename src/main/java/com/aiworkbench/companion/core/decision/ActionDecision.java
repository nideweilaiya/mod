package com.aiworkbench.companion.core.decision;

import java.util.Collections;
import java.util.Map;

/**
 * 决策结果：评估层产出的统一输出。
 *
 * <p>执行层只认这个对象，不关心它来自规则引擎还是 LLM。
 * {@link #IDLE} 表示当前无事可做。</p>
 */
public record ActionDecision(
    String actionId,                   // 动作原语名，如 "MoveTo", "BreakBlock"
    Map<String, Object> params,        // 参数，如 {"target": [12,64,8]}
    String reasoning,                  // 决策理由（规则引擎输出效用分，LLM输出自然语言）
    DecisionSource source              // 决策来源
) {
    /** 空决策：表示当前无需执行任何动作 */
    public static final ActionDecision IDLE = new ActionDecision(
        "idle", Collections.emptyMap(), "no action needed", DecisionSource.RULE_ENGINE
    );
}
