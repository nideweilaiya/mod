package com.aiworkbench.companion.ai;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.skill.AutoCurriculum;
import com.aiworkbench.companion.skill.AutoCurriculum.CurriculumProposal;
import com.aiworkbench.companion.skill.CompositeTask;
import com.aiworkbench.companion.skill.Skill;
import com.aiworkbench.companion.entity.AutomatonEntity;

import java.util.*;

/**
 * 任务队列 —— 支持多任务排队、优先级抢占、暂停恢复、冷却管理。
 * <p>
 * 每个 AutomatonEntity 持有一个 TaskQueue 实例，替代旧的单一 pendingProposal。
 * 核心功能：
 * <ul>
 *   <li>优先级排序：高优先级任务自动排到队首</li>
 *   <li>动态优先级：等待中的任务每 tick +2 优先级，防止饥饿</li>
 *   <li>抢占：新高优先级任务可挂起当前任务</li>
 *   <li>恢复：抢占结束后恢复被挂起的任务</li>
 *   <li>冷却：同一技能连续失败后进入冷却期</li>
 * </ul>
 */
public class TaskQueue {
    /** 队列最大容量（防止无限排队） */
    private static final int MAX_QUEUE_SIZE = 8;
    /** 超时倍率：任务执行超过 estimatedTicks * TIMEOUT_MULTIPLIER 则强制完成 */
    private static final int TIMEOUT_MULTIPLIER = 5;
    /** 失败冷却基准（tick），连续失败后翻倍 */
    private static final int BASE_FAILURE_COOLDOWN = 600; // 30s
    /** 最大失败冷却 */
    private static final int MAX_FAILURE_COOLDOWN = 6000; // 5min
    /** 每 tick 等待优先级增量 */
    private static final int PRIORITY_BOOST_PER_TICK = 2;

    /** 按优先级排序的任务队列 */
    private final PriorityQueue<TaskEntry> queue = new PriorityQueue<>();
    /** 当前正在执行的任务 */
    private TaskEntry current;
    /** 被挂起的任务（等待恢复） */
    private TaskEntry suspended;
    /** 当前正在执行的复合任务（多步骤目标） */
    private CompositeTask compositeTask;
    /** 上一 tick 的技能活跃状态（用于检测技能完成） */
    private boolean wasSkillActive = false;
    /** 冷却中的技能 → 冷却结束tick */
    private final Map<String, Long> cooldowns = new HashMap<>();
    /** 技能连续失败计数 */
    private final Map<String, Integer> failureCounts = new HashMap<>();

    // ==================== 入队操作 ====================

    /**
     * 将课程提议入队。
     * @return 是否成功入队（队列满则失败）
     */
    public boolean enqueue(CurriculumProposal proposal) {
        if (queue.size() >= MAX_QUEUE_SIZE) {
            return false;
        }
        // 去重：相同技能已在队列或执行中则跳过
        String skillName = proposal.hasSkill() ? proposal.suggestedSkill : proposal.taskDescription;
        if (containsSkill(skillName)) {
            return false;
        }
        // 检查冷却
        if (isOnCooldown(skillName)) {
            return false;
        }

        TaskEntry entry = new TaskEntry(proposal);
        queue.add(entry);
        AICompanionMod.LOGGER.debug("[TaskQueue] Enqueued: {}", entry);
        return true;
    }

    /**
     * 强制插入紧急任务（无视队列大小限制和冷却）。
     */
    public boolean enqueueEmergency(CurriculumProposal proposal) {
        String skillName = proposal.hasSkill() ? proposal.suggestedSkill : proposal.taskDescription;
        if (containsSkill(skillName)) {
            return false; // 同一任务已在排队
        }
        // 如果当前有任务在执行，挂起它
        if (current != null && current.status == TaskStatus.RUNNING) {
            suspendCurrent();
        }
        TaskEntry entry = new TaskEntry(proposal);
        entry.priorityValue = TaskPriority.CRITICAL.baseValue;
        queue.add(entry);
        AICompanionMod.LOGGER.info("[TaskQueue] Emergency enqueued: {}", entry);
        return true;
    }

    // ==================== Tick 驱动 ====================

    /**
     * 每 tick 由 AutomatonEntity 调用。驱动任务执行和状态推进。
     */
    public void tick(AutomatonEntity entity) {
        boolean skillActive = entity.isSkillActive();

        // 检测技能刚完成：wasSkillActive=true, skillActive=false
        if (wasSkillActive && !skillActive && current != null && current.status == TaskStatus.RUNNING) {
            // 技能执行完毕 → 标记当前任务完成
            if (current.isCompositeStep) {
                current.completed = true;
                completeCurrent();
                // 推进复合任务到下一步
                if (compositeTask != null && !compositeTask.isDone()) {
                    onCompositeStepDone(entity);
                }
            }
        }

        wasSkillActive = skillActive;

        // 实体正在执行技能 → 等待
        if (skillActive) return;

        // 当前任务完成（非复合任务或已处理）→ 取下一个
        if (current != null && current.status == TaskStatus.RUNNING && current.completed) {
            completeCurrent();
        }

        // 没有当前任务 → 从队列取下一个
        if (current == null || current.status.isDone()) {
            advance(entity);
        }

        // 提升等待中任务的优先级
        boostPriorities();

        // 检查当前任务是否超时
        if (current != null && current.status == TaskStatus.RUNNING) {
            long elapsed = entity.level().getGameTime() - current.startedAt;
            if (elapsed > current.estimatedTicks * TIMEOUT_MULTIPLIER) {
                AICompanionMod.LOGGER.warn("[TaskQueue] Timeout: {} ({} ticks)", current, elapsed);
                failCurrent("TIMEOUT");
                advance(entity);
            }
        }
    }

    /** 从队列取出下一个任务开始执行 */
    private void advance(AutomatonEntity entity) {
        while (!queue.isEmpty()) {
            TaskEntry next = queue.poll();
            if (isOnCooldown(next.skillName)) continue;
            current = next;
            current.status = TaskStatus.RUNNING;
            current.startedAt = entity.level().getGameTime();
            executeEntry(current, entity);
            return;
        }
        // 队列空，检查是否有挂起的任务可以恢复
        if (suspended != null) {
            resumeSuspended(entity);
        }
        current = null;
    }

    /** 执行一个任务条目：查找技能并启动 SkillEngine */
    private void executeEntry(TaskEntry entry, AutomatonEntity entity) {
        if (entry.skillName == null || entry.skillName.isEmpty()) {
            entry.completed = true;
            return;
        }

        if ("gather".equals(entry.skillName)) {
            entity.setGatherModeEnabled(true);
            entity.showDialogue("§a🔧" + entry.description, 60);
            entry.completed = true;
            return;
        }

        Skill skill = AICompanionMod.skillLibrary.getPreset(entry.skillName);
        if (skill != null) {
            entity.getSkillEngine().startSkill(skill, entity);
            entity.showDialogue("§a🔧 " + entry.description, 60);
            AICompanionMod.LOGGER.info("[TaskQueue] Started: {}", entry);
            // 复合任务步骤不立即标记完成，等待 tick 检测技能结束
            if (!entry.isCompositeStep) {
                entry.completed = true;
            }
        } else {
            // 技能未注册 → fallback
            if (entry.skillName.startsWith("mine") || entry.skillName.contains("Wood") || entry.skillName.contains("collect")) {
                entity.setGatherModeEnabled(true);
                entity.showDialogue("§a🔧" + entry.description, 60);
            }
            entry.completed = true;
        }
    }

    // ==================== 暂停 / 恢复 ====================

    /** 挂起当前任务 */
    public void suspendCurrent() {
        if (current == null || current.status != TaskStatus.RUNNING) return;
        current.status = TaskStatus.SUSPENDED;
        suspended = current;
        current = null;
        AICompanionMod.LOGGER.info("[TaskQueue] Suspended: {}", suspended);
    }

    /** 恢复被挂起的任务 */
    public void resumeSuspended(AutomatonEntity entity) {
        if (suspended == null) return;
        suspended.status = TaskStatus.RUNNING;
        current = suspended;
        suspended = null;
        executeEntry(current, entity);
        AICompanionMod.LOGGER.info("[TaskQueue] Resumed: {}", current);
    }

    // ==================== 完成 / 失败 ====================

    /** 标记当前任务成功完成 */
    public void completeCurrent() {
        if (current == null) return;
        current.status = TaskStatus.COMPLETED;
        current.completed = true;
        failureCounts.remove(current.skillName); // 成功后重置失败计数
        AICompanionMod.LOGGER.info("[TaskQueue] Completed: {}", current);
    }

    /** 标记当前任务失败 */
    public void failCurrent(String reason) {
        if (current == null) return;
        current.status = TaskStatus.FAILED;
        current.completed = true;

        // 失败冷却
        int failures = failureCounts.merge(current.skillName, 1, Integer::sum);
        long cooldown = Math.min(BASE_FAILURE_COOLDOWN * (1L << Math.min(failures - 1, 5)), MAX_FAILURE_COOLDOWN);
        cooldowns.put(current.skillName, System.currentTimeMillis() + cooldown * 50);
        AICompanionMod.LOGGER.warn("[TaskQueue] Failed: {} (reason={}, failures={}, cooldown={}s)", current, reason, failures, cooldown / 20);
    }

    // ==================== 查询 ====================

    public TaskEntry getCurrent() { return current; }
    public int size() { return queue.size() + (current != null && !current.status.isDone() ? 1 : 0); }
    public boolean isEmpty() { return size() == 0; }
    public boolean hasSuspended() { return suspended != null; }

    // ==================== 复合任务 ====================

    /**
     * 将复合任务入队。复合任务的每个子步骤作为独立任务逐一执行。
     */
    public boolean enqueueComposite(CompositeTask task) {
        if (compositeTask != null && !compositeTask.isDone()) {
            return false;
        }
        compositeTask = task;
        compositeTask.start();
        AICompanionMod.LOGGER.info("[TaskQueue] Composite started: {}", task.getProgressString());
        // 将第一个子步骤作为 current 任务
        startNextCompositeSubTask();
        return true;
    }

    /** 启动复合任务的下一个子步骤 */
    private void startNextCompositeSubTask() {
        if (compositeTask == null || compositeTask.isDone()) return;
        CompositeTask.SubTask sub = compositeTask.currentSubTask();
        if (sub != null) {
            TaskEntry entry = new TaskEntry(sub.skillName, sub.description);
            entry.priorityValue = TaskPriority.HIGH.baseValue;
            current = entry;
            current.status = TaskStatus.RUNNING;
        }
    }

    /** 复合任务子步骤完成后的处理 */
    private void onCompositeStepDone(AutomatonEntity entity) {
        if (compositeTask == null || compositeTask.isDone()) return;
        CompositeTask.SubTask next = compositeTask.advance();
        if (compositeTask.isDone()) {
            AICompanionMod.LOGGER.info("[TaskQueue] Composite done: {}", compositeTask.getProgressString());
            if (entity != null) {
                entity.showDialogue("§a✅ " + compositeTask.goalDescription + " 完成!", 80);
            }
            compositeTask = null;
            current = null;
            return;
        }
        if (next != null) {
            TaskEntry entry = new TaskEntry(next.skillName, next.description);
            entry.priorityValue = TaskPriority.HIGH.baseValue;
            current = entry;
            current.status = TaskStatus.RUNNING;
            current.startedAt = entity != null ? entity.level().getGameTime() : 0;
        }
    }

    /** 处理复合任务子步骤失败 */
    private void onCompositeStepFail(AutomatonEntity entity) {
        if (compositeTask == null) return;
        CompositeTask.FailureAction action = compositeTask.onFail();
        switch (action) {
            case RETRY_STEP -> startNextCompositeSubTask();
            case SKIP_STEP -> {
                AICompanionMod.LOGGER.warn("[TaskQueue] Composite skipped: {}", compositeTask.currentSubTask());
                compositeTask.advance();
                onCompositeStepDone(entity);
            }
            case ABORT_TASK -> {
                AICompanionMod.LOGGER.error("[TaskQueue] Composite aborted: {}", compositeTask.goalDescription);
                compositeTask.abort();
                compositeTask = null;
                current = null;
                if (entity != null) entity.showDialogue("§c❌ 任务链中断", 60);
            }
        }
    }

    public boolean hasCompositeTask() {
        return compositeTask != null && !compositeTask.isDone();
    }

    public String getCompositeProgress() {
        return compositeTask != null ? compositeTask.getProgressString() : null;
    }

    public void clear() {
        queue.clear();
        suspended = null;
        compositeTask = null;
    }

    /** 获取排队中的任务描述列表（用于 HUD/命令显示） */
    public List<String> getQueueDescriptions() {
        List<String> list = new ArrayList<>();
        if (current != null && !current.status.isDone()) {
            list.add("▶ " + current.status.icon() + " " + current.description);
        }
        for (TaskEntry e : queue) {
            list.add("  " + e.description);
        }
        if (suspended != null) {
            list.add("⏸ " + suspended.description);
        }
        return list;
    }

    // ==================== 内部方法 ====================

    private void boostPriorities() {
        for (TaskEntry e : queue) {
            e.priorityValue += PRIORITY_BOOST_PER_TICK;
        }
    }

    private boolean containsSkill(String skillName) {
        if (current != null && !current.status.isDone() && skillName.equals(current.skillName)) return true;
        for (TaskEntry e : queue) {
            if (skillName.equals(e.skillName)) return true;
        }
        return false;
    }

    private boolean isOnCooldown(String skillName) {
        Long until = cooldowns.get(skillName);
        return until != null && System.currentTimeMillis() < until;
    }

    // ==================== 数据类型 ====================

    public enum TaskStatus {
        PENDING("⏳"), RUNNING("▶"), COMPLETED("✅"), FAILED("❌"), SUSPENDED("⏸");

        private final String icon;
        TaskStatus(String icon) { this.icon = icon; }
        public String icon() { return icon; }
        public boolean isDone() { return this == COMPLETED || this == FAILED; }
    }

    public static class TaskEntry implements Comparable<TaskEntry> {
        public final String description;
        public final String skillName;
        public int priorityValue;
        public TaskStatus status = TaskStatus.PENDING;
        public boolean completed;
        public long startedAt;
        public final long estimatedTicks;
        public boolean isCompositeStep; // 是否属于复合任务的一个步骤

        TaskEntry(CurriculumProposal proposal) {
            this.description = proposal.taskDescription;
            this.skillName = proposal.hasSkill() ? proposal.suggestedSkill : proposal.taskDescription;
            this.priorityValue = proposal.isHighPriority() ? TaskPriority.HIGH.baseValue :
                proposal.priority == AutoCurriculum.Priority.MEDIUM ? TaskPriority.MEDIUM.baseValue :
                TaskPriority.LOW.baseValue;
            this.estimatedTicks = 200;
        }

        TaskEntry(String skillName, String description) {
            this.description = description;
            this.skillName = skillName;
            this.priorityValue = TaskPriority.HIGH.baseValue;
            this.estimatedTicks = 200;
            this.isCompositeStep = true;
        }

        @Override
        public int compareTo(TaskEntry other) {
            return Integer.compare(other.priorityValue, this.priorityValue); // 降序
        }

        @Override
        public String toString() {
            return String.format("TaskEntry{%s priority=%d status=%s}", description, priorityValue, status);
        }
    }
}
