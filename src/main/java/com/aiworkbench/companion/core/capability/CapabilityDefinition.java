package com.aiworkbench.companion.core.capability;

import java.util.List;
import java.util.Map;

/**
 * 能力定义 — 从 config/capabilities/*.json 反序列化的数据对象。
 *
 * <p>一个能力 = 一组原语序列 + 触发条件。评估层输出能力引用（capability_id），
 * 执行层从 CapabilityRegistry 加载序列后逐个驱动原语。</p>
 *
 * <h3>JSON Schema</h3>
 * <pre>
 * {
 *   "id": "gather_logs",
 *   "name": "采集木材",
 *   "need": "wood",
 *   "action_sequence": [
 *     {"action": "NavigateToInteract", "params": {"target": "$found_block.pos"}},
 *     {"action": "EquipItem",       "params": {"keyword": "axe"}},
 *     {"action": "BreakBlock",      "params": {"target": "$found_block.pos"}},
 *     {"action": "PickupItem",      "params": {"item_filter": "_log"}}
 *   ]
 * }
 * </pre>
 */
public class CapabilityDefinition {

    public String id;
    public String name;
    /** 对应的需求类型：wood, food, safety, ore */
    public String need;
    /** 匹配的方块类型关键词，用于在感知数据中定位目标（如 _log, _ore） */
    public List<String> block_keywords;
    /** 原语序列 */
    public List<ActionStep> action_sequence;

    public CapabilityDefinition() {}

    public CapabilityDefinition(String id, String name, String need,
                                List<String> blockKeywords, List<ActionStep> actionSequence) {
        this.id = id;
        this.name = name;
        this.need = need;
        this.block_keywords = blockKeywords;
        this.action_sequence = actionSequence;
    }

    /** 序列中的单个动作步骤 */
    public static class ActionStep {
        public String action;
        public Map<String, Object> params;
        /** repeat 循环体：子步骤列表（仅 action="repeat" 时使用） */
        public List<ActionStep> body;
        /** repeat 退出条件：{"block_above": "_log"} 仅 action="repeat" 时使用 */
        public Map<String, Object> condition;

        public ActionStep() {}

        public ActionStep(String action, Map<String, Object> params) {
            this.action = action;
            this.params = params;
        }

        public boolean isRepeat() {
            return "repeat".equals(action);
        }
    }
}
