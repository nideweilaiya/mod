package com.aiworkbench.companion.skill;

import com.aiworkbench.companion.skill.RecipeResolver.RecipeStep;

import java.util.*;

/**
 * 复合任务 —— 将高层目标分解为有序子任务链，追踪执行进度和依赖关系。
 * <p>
 * 例如 "做铁镐" 分解为 [挖铁矿→挖煤炭→做熔炉→烧铁锭→做木棍→合成铁镐]。
 * 每个子任务对应一个预设技能，按拓扑序依次执行。
 */
public class CompositeTask {

    public enum Status { PENDING, RUNNING, COMPLETED, FAILED, ABORTED }

    public enum FailureStrategy { RETRY_ONCE, SKIP, ABORT }

    public final String goalId;
    public final String goalDescription;
    private final List<SubTask> subTasks;
    private int currentIndex = 0;
    private Status status = Status.PENDING;
    private final FailureStrategy failureStrategy;
    private final Map<String, Integer> retryCounts = new HashMap<>();
    private int consecutiveFailures = 0;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    public CompositeTask(String goalId, String goalDescription,
                         List<RecipeStep> steps, FailureStrategy failureStrategy) {
        this.goalId = goalId;
        this.goalDescription = goalDescription;
        this.failureStrategy = failureStrategy;
        this.subTasks = new ArrayList<>();
        for (RecipeStep step : steps) {
            this.subTasks.add(new SubTask(step.id, step.product, step.skillName, step.description));
        }
    }

    // ==================== 执行控制 ====================

    /** 获取当前待执行的子任务 */
    public SubTask currentSubTask() {
        if (isDone() || currentIndex >= subTasks.size()) return null;
        return subTasks.get(currentIndex);
    }

    /** 标记当前子任务成功，推进到下一步 */
    public SubTask advance() {
        if (currentIndex < subTasks.size()) {
            retryCounts.remove(subTasks.get(currentIndex).id);
            consecutiveFailures = 0;
        }
        currentIndex++;
        if (currentIndex >= subTasks.size()) {
            status = Status.COMPLETED;
            return null;
        }
        return subTasks.get(currentIndex);
    }

    /** 当前子任务失败，根据策略决定下一步 */
    public FailureAction onFail() {
        if (currentIndex >= subTasks.size()) return FailureAction.ABORT_TASK;
        SubTask current = subTasks.get(currentIndex);
        consecutiveFailures++;

        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            status = Status.FAILED;
            return FailureAction.ABORT_TASK;
        }

        int retries = retryCounts.merge(current.id, 1, Integer::sum);
        return switch (failureStrategy) {
            case RETRY_ONCE -> retries <= 1 ? FailureAction.RETRY_STEP : FailureAction.SKIP_STEP;
            case SKIP -> FailureAction.SKIP_STEP;
            case ABORT -> {
                status = Status.FAILED;
                yield FailureAction.ABORT_TASK;
            }
        };
    }

    public void start() { this.status = Status.RUNNING; }
    public void abort() { this.status = Status.ABORTED; }
    public boolean isDone() { return status == Status.COMPLETED || status == Status.FAILED || status == Status.ABORTED; }
    public Status getStatus() { return status; }
    public int getCurrentIndex() { return currentIndex; }
    public int getTotalSteps() { return subTasks.size(); }
    public int getRemainingSteps() { return Math.max(0, subTasks.size() - currentIndex); }

    /** 获取已完成步骤的描述列表 */
    public List<String> getCompletedSteps() {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(currentIndex, subTasks.size()); i++) {
            result.add("✅ " + subTasks.get(i).description);
        }
        return result;
    }

    /** 获取剩余步骤的描述列表 */
    public List<String> getRemainingStepDescriptions() {
        List<String> result = new ArrayList<>();
        for (int i = currentIndex; i < subTasks.size(); i++) {
            result.add((i == currentIndex ? "▶ " : "  ") + subTasks.get(i).description);
        }
        return result;
    }

    public String getProgressString() {
        return String.format("[%d/%d] %s", currentIndex, subTasks.size(), goalDescription);
    }

    // ==================== 数据类型 ====================

    public static class SubTask {
        public final String id;
        public final String product;
        public final String skillName;
        public final String description;

        SubTask(String id, String product, String skillName, String description) {
            this.id = id;
            this.product = product;
            this.skillName = skillName;
            this.description = description;
        }

        @Override
        public String toString() {
            return String.format("%s → %s", description, product);
        }
    }

    public enum FailureAction { RETRY_STEP, SKIP_STEP, ABORT_TASK }

    @Override
    public String toString() {
        return String.format("CompositeTask{%s [%d/%d] %s}",
            goalId, currentIndex, subTasks.size(), status);
    }
}
