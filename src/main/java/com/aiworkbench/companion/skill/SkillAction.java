package com.aiworkbench.companion.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 技能行为序列 —— 管理原子操作的有序执行。
 * <p>
 * 维护当前步骤索引，支持顺序推进和重置。
 * thread-safety: 仅在服务器主线程访问。
 */
public class SkillAction {

    private final List<AtomicAction> steps;
    private int currentStep;
    private final boolean looping;

    public SkillAction(List<AtomicAction> steps, boolean looping) {
        this.steps = new ArrayList<>(Objects.requireNonNull(steps, "steps must not be null"));
        this.currentStep = -1;
        this.looping = looping;
    }

    public SkillAction(List<AtomicAction> steps) {
        this(steps, false);
    }

    /** 获取当前原子操作 */
    public AtomicAction getCurrentAction() {
        if (currentStep < 0 || currentStep >= steps.size()) {
            return null;
        }
        return steps.get(currentStep);
    }

    /** 当前步骤索引（-1 = 未开始） */
    public int getCurrentStep() {
        return currentStep;
    }

    /** 总步骤数 */
    public int getTotalSteps() {
        return steps.size();
    }

    /** 推进到下一步。首次调用前需调用 start()。 */
    public void advance() {
        if (currentStep < steps.size() - 1) {
            currentStep++;
        } else if (looping) {
            currentStep = 0;
        } else {
            currentStep++; // 越过末尾 → isDone() 返回 true
        }
    }

    /** 开始执行（从第一步开始） */
    public void start() {
        currentStep = 0;
    }

    /** 所有步骤是否已完成 */
    public boolean isDone() {
        return currentStep >= steps.size() && !looping;
    }

    /** 是否有步骤在执行 */
    public boolean isStarted() {
        return currentStep >= 0;
    }

    /** 重置到未开始状态 */
    public void reset() {
        currentStep = -1;
    }

    /** 是否循环执行 */
    public boolean isLooping() {
        return looping;
    }
}
