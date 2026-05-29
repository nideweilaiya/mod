package com.aiworkbench.companion.core.action;

/**
 * 动作原语单次 {@link IAction#execute(PerceptionData)} 的执行结果。
 */
public enum ActionResult {

    /** 动作已完成，序列可进入下一个原语 */
    SUCCESS,

    /** 动作执行中，下个 tick 继续调用 execute() */
    IN_PROGRESS,

    /** 动作失败且不可重试，决策层需重新评估 */
    FAILURE
}
