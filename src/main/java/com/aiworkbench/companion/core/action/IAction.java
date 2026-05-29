package com.aiworkbench.companion.core.action;

import com.aiworkbench.companion.core.perception.PerceptionData;

/**
 * 动作原语接口 — 所有伙伴原子动作的契约。
 *
 * <p>每个实现代表一个不可再分的原子操作（如移动到坐标、破坏方块）。
 * 复杂行为（砍树、钓鱼、建筑）由多个 IAction 实现有序组合而成，
 * 组合逻辑定义在 config/capabilities/*.json 中。</p>
 *
 * <p>生命周期：canExecute() → execute() 循环调用直到返回 SUCCESS 或 FAILURE。
 * 评估层负责选择哪个 IAction，执行层负责驱动 execute() 每 tick 调用。</p>
 */
public interface IAction {

    /**
     * 检查在当前感知数据下，此动作的前置条件是否满足。
     * 必须在 {@link #execute(PerceptionData)} 之前调用。
     *
     * @param perception 最新感知快照
     * @return true 表示前置条件满足，可以调用 execute()
     */
    boolean canExecute(PerceptionData perception);

    /**
     * 执行此动作的一个 tick 步进。
     * 可能需要多 tick 才能完成（如走到目标坐标、持续破坏方块）。
     *
     * @param perception 当前 tick 的感知快照
     * @return SUCCESS（动作完成）、FAILURE（动作失败，不可重试）、IN_PROGRESS（继续执行）
     */
    ActionResult execute(PerceptionData perception);

    /**
     * 估算此动作的完成代价（tick 数）。
     * 供评估层在不同候选动作之间比较用。代价越高，越不优先。
     *
     * @return 预估 tick 数
     */
    int getCost();
}
