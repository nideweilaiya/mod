package com.aiworkbench.companion.manager;

/** 同伴角色 —— 决定自主行为偏好 */
public enum CompanionRole {
    MINER("⛏", "矿工", "主动挖矿，检测矿石"),
    GUARD("🛡", "守卫", "跟在身边，遇敌战斗"),
    FARMER("🌾", "农民", "收割补种、动物互动"),
    BUILDER("🏗", "建筑师", "执行建筑蓝图"),
    EXPLORER("🔍", "探索者", "探未探索区块，报告资源"),
    GENERAL("⭐", "通用", "均衡行为");

    public final String icon;
    public final String chineseName;
    public final String description;

    CompanionRole(String icon, String chineseName, String description) {
        this.icon = icon;
        this.chineseName = chineseName;
        this.description = description;
    }
}
