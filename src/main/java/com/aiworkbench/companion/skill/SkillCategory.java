package com.aiworkbench.companion.skill;

/**
 * 技能分类 —— 用于 GUI 分组和检索筛选。
 */
public enum SkillCategory {
    MOVEMENT("移动"),
    INTERACTION("交互"),
    COMBAT("战斗"),
    CRAFTING("合成");

    private final String displayName;

    SkillCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
