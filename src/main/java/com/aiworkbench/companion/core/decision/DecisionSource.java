package com.aiworkbench.companion.core.decision;

/** 决策来源标识 */
public enum DecisionSource {
    /** 内置规则引擎（LLM 只聊天，不参与行为控制） */
    RULE_ENGINE,
    /** LLM 做决策，无长期记忆（档次2） */
    LLM_TIER2,
    /** LLM 做决策 + 自主维护记忆（档次3） */
    LLM_TIER3
}
