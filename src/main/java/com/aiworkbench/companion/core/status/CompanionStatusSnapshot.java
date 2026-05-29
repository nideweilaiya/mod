package com.aiworkbench.companion.core.status;

import net.minecraft.core.BlockPos;
import java.util.List;
import java.util.Map;

/**
 * {@link ICompanionStatus} 的不可变快照实现。
 *
 * <p>供 Web API 序列化为 JSON 返回，避免直接暴露实体引用。</p>
 */
public record CompanionStatusSnapshot(
    String currentAction,
    int actionProgress,
    List<String> activeCapabilities,
    Map<String, Integer> inventorySummary,
    BlockPos position,
    float health,
    int hunger,
    String activeDecisionSource,
    BlockPos ownerPosition,
    int threatCount
) implements ICompanionStatus {

    @Override public String getCurrentAction() { return currentAction; }
    @Override public int getActionProgress() { return actionProgress; }
    @Override public List<String> getActiveCapabilities() { return activeCapabilities; }
    @Override public Map<String, Integer> getInventorySummary() { return inventorySummary; }
    @Override public BlockPos getPosition() { return position; }
    @Override public float getHealth() { return health; }
    @Override public int getHunger() { return hunger; }
    @Override public String getActiveDecisionSource() { return activeDecisionSource; }
    @Override public BlockPos getOwnerPosition() { return ownerPosition; }
    @Override public int getThreatCount() { return threatCount; }

    /** 从 AutomatonEntity 创建快照（需要实体引用时调用） */
    public static CompanionStatusSnapshot from(
        BlockPos pos, float health, int hunger,
        String action, int progress,
        List<String> capabilities,
        Map<String, Integer> inventory,
        String decisionSource,
        BlockPos ownerPos, int threats
    ) {
        return new CompanionStatusSnapshot(
            action, progress, capabilities, inventory,
            pos, health, hunger, decisionSource, ownerPos, threats
        );
    }
}
