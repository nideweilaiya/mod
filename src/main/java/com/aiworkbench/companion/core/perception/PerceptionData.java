package com.aiworkbench.companion.core.perception;

import com.aiworkbench.companion.core.action.TreeHarvestCursor;
import net.minecraft.core.BlockPos;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 伙伴对当前世界的一次完整感知快照。
 *
 * <p>由 PerceptionEngine 扫描后填充，供 RuleBasedDecisionMaker 和 LLMDecisionMaker
 * 共同使用。无论谁在决策，看到的数据结构是一样的。</p>
 *
 * <p>P0 阶段为占位定义，P1 阶段将接入 PerceptionEngine 实际扫描。</p>
 */
public class PerceptionData {

    /** 周围方块列表（按距离排序，最近在前） */
    public List<NearbyBlock> nearbyBlocks;

    /** 周围实体列表 */
    public List<NearbyEntity> nearbyEntities;

    /** Nearby dropped items. */
    public List<NearbyItem> nearbyItems;

    /** 背包摘要：物品类型名 → 数量 */
    public Map<String, Integer> inventorySummary;

    /** 自身状态 */
    public SelfStatus self;

    /** 已识别的威胁（距离8格以内的敌对实体） */
    public List<NearbyEntity> threats;

    /** 扫描时间戳（系统毫秒） */
    public long scanTimestamp;

    /** 全树扫描结果：BFS排序后的原木位置列表（Y升序，同层距离升序）。
     *  由 PerceptionEngine.gatherStructured() 在检测到原木时填充。null=无树或未触发扫描。 */
    public List<BlockPos> treeCutList;

    /** Structured tree scan result for multi-column harvesting. */
    public TreeStructure treeStructure;

    /** Active structured harvesting cursor when an interrupted task is being resumed. */
    public TreeHarvestCursor treeHarvestCursor;

    /** Long-range standable approach hint used to approach a tree before local structure scan can take over. */
    public BlockPos treeAnchorHint;

    // ---- 内嵌类型 ----

    public record NearbyBlock(String blockType, BlockPos pos, double distance, boolean isReachable) {}

    public record NearbyEntity(String entityType, UUID uuid, BlockPos pos, double distance, boolean isHostile) {}

    public record NearbyItem(String itemType, int count, BlockPos pos, double distance) {}

    public static class SelfStatus {
        public float health;
        public int hunger;
        public BlockPos position;
        public String currentAction;

        public SelfStatus(float health, int hunger, BlockPos position, String currentAction) {
            this.health = health;
            this.hunger = hunger;
            this.position = position;
            this.currentAction = currentAction;
        }
    }
}
