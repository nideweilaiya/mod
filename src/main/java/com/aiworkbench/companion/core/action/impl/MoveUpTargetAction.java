package com.aiworkbench.companion.core.action.impl;

import com.aiworkbench.companion.core.action.ActionResult;
import com.aiworkbench.companion.core.action.IAction;
import com.aiworkbench.companion.core.perception.PerceptionData;

import net.minecraft.core.BlockPos;

/**
 * 将能力内部的 $current_target 向上移动一格。
 *
 * <p>这是一个元原语——不执行任何物理动作，只更新变量上下文。
 * 用于 repeat 循环中的步进操作。</p>
 *
 * <h3>canExecute</h3>
 * 总是返回 true（纯变量操作）。
 *
 * <h3>execute</h3>
 * 将 $current_target.y 增加 1，立即返回 SUCCESS。
 */
public class MoveUpTargetAction implements IAction {

    private final java.util.Map<String, Object> variables;

    public MoveUpTargetAction(java.util.Map<String, Object> variables) {
        this.variables = variables;
    }

    @Override
    public boolean canExecute(PerceptionData perception) {
        return variables != null && variables.containsKey("$current_target");
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        BlockPos current = (BlockPos) variables.get("$current_target");
        if (current == null) return ActionResult.FAILURE;
        variables.put("$current_target", current.above());
        return ActionResult.SUCCESS;
    }

    @Override
    public int getCost() {
        return 1;
    }
}
