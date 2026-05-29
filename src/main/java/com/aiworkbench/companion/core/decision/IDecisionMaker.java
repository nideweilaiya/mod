package com.aiworkbench.companion.core.decision;

import com.aiworkbench.companion.core.perception.PerceptionData;

/**
 * 统一决策接口 — 评估层的唯一入口。
 *
 * <p>实现类可以是内置规则引擎（{@code RuleBasedDecisionMaker}）
 * 或外部 LLM（{@code LLMDecisionMaker}）。执行层不知道也不关心谁在决策，
 * 只接收 {@link ActionDecision} 并执行。</p>
 *
 * <h3>三层 LLM 档次</h3>
 * <ul>
 *   <li>档次1（纯聊天）：{@code RuleBasedDecisionMaker} 独立决策，LLM 只生成对话文本</li>
 *   <li>档次2（可决策）：{@code LLMDecisionMaker} 调用 LLM 输出动作指令，无长期记忆</li>
 *   <li>档次3（自主+记忆）：在档次2基础上增加记忆读写接口</li>
 * </ul>
 */
@FunctionalInterface
public interface IDecisionMaker {

    /**
     * 根据感知和记忆，决定下一步要执行的动作。
     *
     * @param perception 最新感知数据
     * @param memory     当前记忆快照
     * @return 决策结果；如果无需执行任何动作，返回 {@link ActionDecision#IDLE}
     */
    ActionDecision decide(PerceptionData perception, MemorySnapshot memory);
}
