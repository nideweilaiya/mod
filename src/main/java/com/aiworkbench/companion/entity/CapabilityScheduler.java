package com.aiworkbench.companion.entity;

import com.aiworkbench.companion.AICompanionMod;

/**
 * v3.0: 能力调度器 — 三层优先级 + 危险中断管理。
 *
 * Always Layer:  GUARD_SENSE, SURVIVAL — 始终激活
 * Active Layer:  FOLLOW|GUARD|GATHER|FARM|PATROL — 互斥，最多一个
 * Background Layer: (空闲时自动 WANDER)
 *
 * 危险中断流程:
 *   1. interruptForDanger() → 保存当前 Active → 切换到 GUARD
 *   2. resumeFromDanger()   → 恢复保存的 Active
 */
public class CapabilityScheduler {
    private int capabilities;
    private int savedActive = CapabilityFlags.FOLLOW;
    private boolean interrupted = false;
    private String interruptReason = "";

    // V键防抖
    private int toggleCooldown = 0;

    public CapabilityScheduler() {
        // 默认: 跟随 + 被动感知
        this.capabilities = CapabilityFlags.FOLLOW | CapabilityFlags.GUARD_SENSE | CapabilityFlags.SURVIVAL;
    }

    // ==================== 查询 ====================

    public int getCapabilities() { return capabilities; }

    public boolean isActive(int cap) {
        return CapabilityFlags.has(capabilities, cap);
    }

    public int getActiveLayer() {
        return CapabilityFlags.getActive(capabilities);
    }

    public String getActiveName() {
        return CapabilityFlags.activeName(capabilities);
    }

    public boolean isInterrupted() { return interrupted; }

    public String getInterruptReason() { return interruptReason; }

    // ==================== Active 层设置 ====================

    /** 设置 Active 层能力（互斥），自动保存变更日志 */
    public void setActive(int cap) {
        int old = CapabilityFlags.getActive(capabilities);
        if (old == cap) return;
        capabilities = CapabilityFlags.setActive(capabilities, cap);
        AICompanionMod.LOGGER.info("[Scheduler] Active: {} → {}",
            CapabilityFlags.toChineseName(old), CapabilityFlags.toChineseName(cap));
    }

    /** V 键循环: FOLLOW → GUARD → GATHER → FARM → FOLLOW */
    public int cycleActive() {
        int current = CapabilityFlags.getActive(capabilities);
        int next = switch (current) {
            case CapabilityFlags.FOLLOW -> CapabilityFlags.GUARD;
            case CapabilityFlags.GUARD  -> CapabilityFlags.GATHER;
            case CapabilityFlags.GATHER -> CapabilityFlags.FARM;
            case CapabilityFlags.FARM   -> CapabilityFlags.PATROL;
            default                     -> CapabilityFlags.FOLLOW;
        };
        setActive(next);
        return next;
    }

    /** 直接跳转到指定模式 */
    public void jumpTo(int cap) {
        if ((cap & CapabilityFlags.ACTIVE_LAYER_MASK) == 0) return;
        setActive(cap);
    }

    /** 跳转到跟随模式 */
    public void jumpToFollow() {
        setActive(CapabilityFlags.FOLLOW);
    }

    // ==================== 危险中断 ====================

    /** 危险中断: 保存 Active 层 → 切换到 GUARD */
    public void interruptForDanger(String reason) {
        if (interrupted) return;
        savedActive = CapabilityFlags.getActive(capabilities);
        interrupted = true;
        interruptReason = reason;
        capabilities = CapabilityFlags.setActive(capabilities, CapabilityFlags.GUARD);
        AICompanionMod.LOGGER.info("[Scheduler] Interrupted: {} (saved={})",
            reason, CapabilityFlags.toChineseName(savedActive));
    }

    /** 危险解除: 恢复保存的 Active 层 */
    public void resumeFromDanger() {
        if (!interrupted) return;
        interrupted = false;
        interruptReason = "";
        capabilities = CapabilityFlags.setActive(capabilities, savedActive);
        AICompanionMod.LOGGER.info("[Scheduler] Resumed: {}", CapabilityFlags.toChineseName(savedActive));
    }

    // ==================== V键防抖 ====================

    public boolean canToggle() {
        return toggleCooldown <= 0;
    }

    public void markToggled(int cooldownTicks) {
        toggleCooldown = cooldownTicks;
    }

    public void tickCooldown() {
        if (toggleCooldown > 0) toggleCooldown--;
    }

    // ==================== NBT 序列化 ====================

    public int getForNbt() { return capabilities; }

    public void loadFromNbt(int saved) {
        // 恢复时保留 GUARD_SENSE + SURVIVAL（始终激活）
        this.capabilities = saved | CapabilityFlags.ALWAYS_LAYER_MASK;
        // 确保 Active 层有效
        int active = CapabilityFlags.getActive(this.capabilities);
        if (active == CapabilityFlags.NONE) {
            this.capabilities = CapabilityFlags.setActive(this.capabilities, CapabilityFlags.FOLLOW);
        }
    }
}
