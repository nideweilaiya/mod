package com.aiworkbench.companion.skill;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能 —— 由名称、描述、前置条件和行为序列组成的可执行单元。
 * <p>
 * 一个技能 = 一系列原子操作的组合。
 * 技能库中的技能通过 JSON 持久化存储。
 */
public class Skill {

    private final String name;
    private final String description;
    private final List<String> prerequisites;
    private final SkillAction action;
    private final SkillCategory category;
    private final boolean passive;

    public Skill(String name, String description, List<String> prerequisites,
                 SkillAction action, SkillCategory category, boolean passive) {
        this.name = name;
        this.description = description;
        this.prerequisites = (prerequisites != null) ? prerequisites : new ArrayList<>();
        this.action = action;
        this.category = category;
        this.passive = passive;
    }

    /** 唯一标识名 */
    public String getName() { return name; }

    /** 显示描述 */
    public String getDescription() { return description; }

    /** 前置技能列表（技能名） */
    public List<String> getPrerequisites() { return prerequisites; }

    /** 行为序列 */
    public SkillAction getAction() { return action; }

    /** 技能分类 */
    public SkillCategory getCategory() { return category; }

    /** 是否为被动技能（自动触发，无需玩家命令） */
    public boolean isPassive() { return passive; }

    @Override
    public String toString() {
        return "Skill{name='" + name + "', category=" + category + "}";
    }
}
