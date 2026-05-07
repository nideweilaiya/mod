package com.aiworkbench.companion.skill;

import com.aiworkbench.companion.entity.AutomatonEntity;

/**
 * 原子操作接口 —— 技能的最小可执行单元。
 * <p>
 * 每个原子操作封装一个不可分割的行为（移动、破坏方块、攻击等），
 * 由 {@link SkillEngine} 按顺序驱动执行。
 * <p>
 * 实现类必须遵循：
 * <ul>
 *   <li>{@link #canStart(AutomatonEntity)} 做快速前置检查（返回 false = 跳过此步）</li>
 *   <li>{@link #tick(AutomatonEntity)} 每 tick 调用，返回 true = 本步完成</li>
 *   <li>{@link #stop(AutomatonEntity)} 在技能中断/取消时清理资源</li>
 * </ul>
 */
public interface AtomicAction {

    /**
     * 前置条件检查 —— 是否可以开始执行此操作。
     * 例如：MoveTo 检查目标位置是否可到达。
     */
    boolean canStart(AutomatonEntity entity);

    /**
     * 每 tick 执行一次，直到返回 true。
     * @return true 表示当前原子操作已完成，引擎将进入下一步
     */
    boolean tick(AutomatonEntity entity);

    /**
     * 停止/清理 —— 技能被取消或中断时调用。
     * 用于停止导航、重置状态等。
     */
    void stop(AutomatonEntity entity);

    /**
     * 当前操作的描述文本（显示在同伴头顶）。
     */
    default String getDescription() {
        return "";
    }
}
