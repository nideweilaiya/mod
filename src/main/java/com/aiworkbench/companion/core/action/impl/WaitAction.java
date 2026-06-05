package com.aiworkbench.companion.core.action.impl;

import com.aiworkbench.companion.core.action.ActionResult;
import com.aiworkbench.companion.core.action.IAction;
import com.aiworkbench.companion.core.perception.PerceptionData;

/**
 * 原地等待指定 tick 数的动作原语。
 *
 * <p>用于需要时间流逝的行为序列中：
 * <pre>{@code
 *   // 钓鱼：抛竿后等待
 *   UseItemAction(rod) → WaitAction(100) → PickupItemAction()
 *
 *   // 冶炼：放入物品后等待烧完
 *   UseItemAction(furnace) → WaitAction(200) → UseItemAction(furnace)
 * }</pre>
 */
public class WaitAction implements IAction {

    private final int waitTicks;
    private int elapsedTicks;

    public WaitAction(int waitTicks) {
        this.waitTicks = waitTicks;
    }

    @Override
    public boolean canExecute(PerceptionData perception) {
        return waitTicks > 0;
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        elapsedTicks++;
        if (elapsedTicks >= waitTicks) {
            return ActionResult.SUCCESS;
        }
        return ActionResult.IN_PROGRESS;
    }

    @Override
    public int getCost() {
        return waitTicks;
    }
}
