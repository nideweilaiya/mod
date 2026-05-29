package com.aiworkbench.companion.core.action.impl;

import com.aiworkbench.companion.core.action.ActionResult;
import com.aiworkbench.companion.core.action.IAction;
import com.aiworkbench.companion.core.perception.PerceptionData;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;

/**
 * 从能力变量列表中弹出下一个目标位置的元原语。
 *
 * <p>优先从 {@code $tree_cut_list} 弹出下一个 BlockPos 写入 {@code $current_target}。
 * 无列表时退化为 {@link MoveUpTargetAction} 行为（{@code $current_target.above()}）。</p>
 *
 * <h3>canExecute</h3>
 * 列表非空或 $current_target 存在时返回 true（纯函数）。
 *
 * <h3>execute</h3>
 * 列表非空 → pop(0) → 写入 $current_target → SUCCESS<br>
 * 列表为空但 $current_target 存在 → above() → SUCCESS<br>
 * 列表为空且 $current_target 不存在 → FAILURE
 */
public class SetNextTargetAction implements IAction {

    private final Map<String, Object> variables;

    public SetNextTargetAction(Map<String, Object> variables) {
        this.variables = variables;
    }

    @Override
    public boolean canExecute(PerceptionData perception) {
        if (variables == null) return false;
        // 列表非空 → 可以弹出下一个目标
        Object val = variables.get("$tree_cut_list");
        if (val instanceof List<?> list && !list.isEmpty()) return true;
        // 兜底：至少 $current_target 存在 → 退化为 MoveUpTarget
        return variables.containsKey("$current_target");
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        if (variables == null) return ActionResult.FAILURE;

        // 优先：从全树扫描列表弹下一个目标
        Object val = variables.get("$tree_cut_list");
        if (val instanceof List<?> list && !list.isEmpty()) {
            Object next = list.remove(0);
            if (next instanceof BlockPos bp) {
                variables.put("$current_target", bp);
                return ActionResult.SUCCESS;
            }
        }

        // 兜底：退化为 MoveUpTarget（兼容 gather_ores 等旧能力配置）
        BlockPos current = (BlockPos) variables.get("$current_target");
        if (current != null) {
            variables.put("$current_target", current.above());
            return ActionResult.SUCCESS;
        }

        return ActionResult.FAILURE;
    }

    @Override
    public int getCost() {
        return 1;
    }
}
