package com.aiworkbench.companion.btree;

import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.task.TaskTarget;

import java.util.ArrayList;
import java.util.List;

/**
 * v2.0: 行为树引擎 — 取代硬编码 if-else Goal 链
 *
 * 设计参考: bevy_behavior / mineflayer-statemachine
 *
 * 节点类型:
 *   Selector(OR)  — 依次尝试子节点, 第一个成功的返回
 *   Sequence(AND) — 依次执行子节点, 全部成功才返回
 *   Cooldown      — 执行后冷却一段时间
 *   Action        — 原子行为 (挖矿/移动/收集)
 *   Condition     — 条件检查 (有目标吗? 到达了吗?)
 */
public abstract class BehaviorNode {

    protected List<BehaviorNode> children = new ArrayList<>();
    protected String name;

    public BehaviorNode(String name) { this.name = name; }
    public void addChild(BehaviorNode child) { children.add(child); }

    /** 返回值: Success / Failure / Running */
    public enum Status { SUCCESS, FAILURE, RUNNING }

    /** 执行一次 tick */
    public abstract Status tick(AutomatonEntity entity, TaskTarget target);

    /** 节点被选中时调用 */
    public void onStart(AutomatonEntity entity, TaskTarget target) {}
    public void onStop(AutomatonEntity entity, TaskTarget target) {}

    public String getName() { return name; }

    // ==================== 标准节点 ====================

    /** Selector: 依次尝试子节点, 第一个 SUCCESS 就返回 */
    public static class Selector extends BehaviorNode {
        private int currentIndex = 0;
        public Selector(String name) { super(name); }
        @Override
        public Status tick(AutomatonEntity entity, TaskTarget target) {
            for (int i = currentIndex; i < children.size(); i++) {
                Status s = children.get(i).tick(entity, target);
                if (s == Status.RUNNING) { currentIndex = i; return Status.RUNNING; }
                if (s == Status.SUCCESS) { currentIndex = 0; return Status.SUCCESS; }
            }
            currentIndex = 0;
            return Status.FAILURE;
        }
    }

    /** Sequence: 依次执行, 全部 SUCCESS 才返回 */
    public static class Sequence extends BehaviorNode {
        private int currentIndex = 0;
        public Sequence(String name) { super(name); }
        @Override
        public void onStart(AutomatonEntity entity, TaskTarget target) { currentIndex = 0; }
        @Override
        public Status tick(AutomatonEntity entity, TaskTarget target) {
            for (int i = currentIndex; i < children.size(); i++) {
                Status s = children.get(i).tick(entity, target);
                if (s == Status.RUNNING) { currentIndex = i; return Status.RUNNING; }
                if (s == Status.FAILURE) { currentIndex = 0; return Status.FAILURE; }
            }
            currentIndex = 0;
            return Status.SUCCESS;
        }
    }

    /** Cooldown: 执行成功后冷却 N tick, 期间返回 FAILURE */
    public static class Cooldown extends BehaviorNode {
        private final int cooldownTicks;
        private int remaining = 0;
        public Cooldown(String name, int cooldownTicks) {
            super(name);
            this.cooldownTicks = cooldownTicks;
        }
        @Override
        public Status tick(AutomatonEntity entity, TaskTarget target) {
            if (remaining > 0) { remaining--; return Status.FAILURE; }
            Status s = children.get(0).tick(entity, target);
            if (s == Status.SUCCESS) remaining = cooldownTicks;
            return s;
        }
        @Override public void onStart(AutomatonEntity entity, TaskTarget target) { remaining = 0; }
    }

    /** 反转子节点结果 */
    public static class Invert extends BehaviorNode {
        public Invert(String name) { super(name); }
        @Override public Status tick(AutomatonEntity entity, TaskTarget target) {
            Status s = children.get(0).tick(entity, target);
            if (s == Status.SUCCESS) return Status.FAILURE;
            if (s == Status.FAILURE) return Status.SUCCESS;
            return Status.RUNNING;
        }
    }

    /** 条件检查节点 — 函数式条件 */
    public static class Condition extends BehaviorNode {
        private final java.util.function.BiPredicate<AutomatonEntity, TaskTarget> predicate;
        public Condition(String name, java.util.function.BiPredicate<AutomatonEntity, TaskTarget> predicate) {
            super(name); this.predicate = predicate;
        }
        @Override public Status tick(AutomatonEntity entity, TaskTarget target) {
            return predicate.test(entity, target) ? Status.SUCCESS : Status.FAILURE;
        }
    }

    /** 动作节点 — 函数式动作 */
    public static class Action extends BehaviorNode {
        private final java.util.function.BiFunction<AutomatonEntity, TaskTarget, Status> action;
        public Action(String name, java.util.function.BiFunction<AutomatonEntity, TaskTarget, Status> action) {
            super(name); this.action = action;
        }
        @Override public Status tick(AutomatonEntity entity, TaskTarget target) {
            return action.apply(entity, target);
        }
    }

    // ==================== 工厂方法 ====================

    public static Selector selector(String name, BehaviorNode... nodes) {
        Selector s = new Selector(name);
        for (BehaviorNode n : nodes) s.addChild(n);
        return s;
    }

    public static Sequence sequence(String name, BehaviorNode... nodes) {
        Sequence seq = new Sequence(name);
        for (BehaviorNode n : nodes) seq.addChild(n);
        return seq;
    }

    public static Cooldown cooldown(String name, int ticks, BehaviorNode child) {
        Cooldown c = new Cooldown(name, ticks);
        c.addChild(child);
        return c;
    }
}
