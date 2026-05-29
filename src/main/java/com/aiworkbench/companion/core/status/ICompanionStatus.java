package com.aiworkbench.companion.core.status;

import net.minecraft.core.BlockPos;
import java.util.List;
import java.util.Map;

/**
 * 伙伴状态查询接口 — 面板（Web/GUI）的唯一数据来源。
 *
 * <p>所有面板只依赖此接口，不依赖任何具体功能模块。
 * 功能模块通过实现此接口暴露状态，面板开发与功能开发完全分离。</p>
 */
public interface ICompanionStatus {

    /** 当前正在执行的动作描述，如 "砍树中"、"移动到坐标"、"空闲" */
    String getCurrentAction();

    /** 当前动作进度 0-100 */
    int getActionProgress();

    /** 当前激活的能力名称列表 */
    List<String> getActiveCapabilities();

    /** 背包摘要：物品类型 → 数量 */
    Map<String, Integer> getInventorySummary();

    /** 当前位置 */
    BlockPos getPosition();

    /** 血量 */
    float getHealth();

    /** 饥饿值 */
    int getHunger();

    /** 当前决策来源 */
    String getActiveDecisionSource();

    /** 主人位置（如已知） */
    BlockPos getOwnerPosition();

    /** 附近威胁数量 */
    int getThreatCount();
}
