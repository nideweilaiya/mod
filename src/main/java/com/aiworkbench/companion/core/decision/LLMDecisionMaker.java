package com.aiworkbench.companion.core.decision;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.network.BridgeClient;

import java.util.*;

/**
 * LLM 驱动的决策器（档次2/3）。
 *
 * <p>将感知数据发送给外部 LLM（通过 BridgeClient），由 LLM 返回动作指令。
 * 当前 BridgeClient 为异步发送模式，LLM 响应通过独立通道接收。
 * 在异步响应到达前，以及 LLM 不可用时，自动回退到规则引擎。</p>
 *
 * <h3>当前实现</h3>
 * <ul>
 *   <li>同步调用：尝试获取最近的 LLM 决策缓存</li>
 *   <li>LLM 不可用 → 回退 {@link RuleBasedDecisionMaker}</li>
 *   <li>感知数据通过 BridgeClient 持续推送给 LLM 作为上下文</li>
 * </ul>
 *
 * <h3>TODO（同步协议就绪后启用）</h3>
 * <ul>
 *   <li>BridgeClient 增加 request-response 模式</li>
 *   <li>LLM 返回 JSON → 解析 → 校验 → 执行</li>
 *   <li>校验失败自动回退规则引擎</li>
 * </ul>
 */
public class LLMDecisionMaker implements IDecisionMaker {

    private static final Set<String> VALID_ACTIONS = Set.of(
        "MoveTo", "BreakBlock", "PlaceBlock", "UseItem",
        "PickupItem", "InteractEntity", "Wait", "EquipItem", "idle"
    );

    private final RuleBasedDecisionMaker fallback = new RuleBasedDecisionMaker();
    private BridgeClient bridge;
    private ActionDecision lastLLMDecision;
    private int pushCooldown; // 降频推送感知数据

    public LLMDecisionMaker() {}

    /** 注入 BridgeClient（需在 TCP 连接建立后调用） */
    public void setBridge(BridgeClient bridge) {
        this.bridge = bridge;
    }

    /**
     * 设置 LLM 异步返回的决策结果（由 BridgeClient 的消息处理器调用）。
     * LLM 返回的动作会经过验证，不合法则丢弃。
     */
    public void onLLMResponse(Map<String, Object> llmResponse) {
        try {
            String action = (String) llmResponse.getOrDefault("action", "idle");
            if (!VALID_ACTIONS.contains(action)) {
                AICompanionMod.LOGGER.warn("[LLMDecisionMaker] Invalid action from LLM: {}", action);
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) llmResponse.getOrDefault("params", Map.of());
            String reasoning = (String) llmResponse.getOrDefault("reasoning", "");
            lastLLMDecision = new ActionDecision(action, params, reasoning, DecisionSource.LLM_TIER2);
        } catch (Exception e) {
            AICompanionMod.LOGGER.warn("[LLMDecisionMaker] Failed to parse LLM response: {}", e.getMessage());
        }
    }

    @Override
    public ActionDecision decide(PerceptionData perception, MemorySnapshot memory) {
        // 推送感知数据给 LLM 作为上下文（降频：每 40 tick 一次）
        pushCooldown--;
        if (pushCooldown <= 0 && bridge != null && bridge.isConnected()) {
            pushCooldown = 40;
            pushPerception(perception, memory);
        }

        // 如果有缓存的 LLM 决策，消费并返回
        if (lastLLMDecision != null) {
            ActionDecision d = lastLLMDecision;
            lastLLMDecision = null;
            return d;
        }

        // LLM 无决策可用 → 回退规则引擎
        return fallback.decide(perception, memory);
    }

    // ==================== 内部方法 ====================

    private void pushPerception(PerceptionData p, MemorySnapshot m) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "perception_update");

        if (p.self != null) {
            data.put("health", p.self.health);
            data.put("hunger", p.self.hunger);
            data.put("position", List.of(
                p.self.position.getX(), p.self.position.getY(), p.self.position.getZ()));
        }

        // 附近最近的 10 个方块
        if (p.nearbyBlocks != null) {
            List<String> blockNames = new ArrayList<>();
            for (int i = 0; i < Math.min(p.nearbyBlocks.size(), 10); i++) {
                blockNames.add(p.nearbyBlocks.get(i).blockType());
            }
            data.put("nearbyBlockTypes", blockNames);
        }

        // 最近的 5 个实体
        if (p.nearbyEntities != null) {
            List<String> entityNames = new ArrayList<>();
            for (int i = 0; i < Math.min(p.nearbyEntities.size(), 5); i++) {
                entityNames.add(p.nearbyEntities.get(i).entityType());
            }
            data.put("nearbyEntityTypes", entityNames);
        }

        if (p.threats != null && !p.threats.isEmpty()) {
            data.put("threatDetected", true);
            data.put("nearestThreatDist", Math.round(p.threats.get(0).distance() * 10.0) / 10.0);
        }

        bridge.send(data);
    }
}
