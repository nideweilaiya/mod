package com.aiworkbench.companion.entity;

/**
 * v3.0: 能力标记系统 — 替代互斥的 CompanionState 枚举。
 *
 * 使用位掩码实现多层并发能力:
 * - Always Layer: 始终激活 (GUARD_SENSE + SURVIVAL)
 * - Active Layer: 互斥，最多一个 (FOLLOW|GUARD|GATHER|FARM|PATROL)
 * - Background Layer: 空闲时激活 (WANDER)
 *
 * 用法:
 *   int caps = CapabilityFlags.FOLLOW | CapabilityFlags.GUARD_SENSE;
 *   CapabilityFlags.has(caps, CapabilityFlags.GATHER);   // false
 *   caps = CapabilityFlags.enable(caps, CapabilityFlags.GATHER);
 */
public final class CapabilityFlags {
    private CapabilityFlags() {}

    // Active Layer (互斥: 设置一个会清除其他 Active 能力)
    public static final int NONE     = 0;
    public static final int FOLLOW   = 1;        // 1 << 0
    public static final int GUARD    = 1 << 1;   // 2 — 主动防守
    public static final int GATHER   = 1 << 2;   // 4 — 采集
    public static final int FARM     = 1 << 3;   // 8 — 种植
    public static final int PATROL   = 1 << 4;   // 16 — 巡逻

    // Always Layer (始终激活，不参与互斥)
    public static final int GUARD_SENSE = 1 << 5; // 32 — 被动危险感知
    public static final int SURVIVAL    = 1 << 6; // 64 — 夜间生存

    // Always Layer 掩码
    public static final int ALWAYS_LAYER_MASK = GUARD_SENSE | SURVIVAL;

    // Active Layer 掩码
    public static final int ACTIVE_LAYER_MASK = FOLLOW | GUARD | GATHER | FARM | PATROL;

    // ==================== 位运算工具 ====================

    /** 是否拥有指定能力 */
    public static boolean has(int flags, int cap) {
        return (flags & cap) != 0;
    }

    /** 开启指定能力 */
    public static int enable(int flags, int cap) {
        return flags | cap;
    }

    /** 关闭指定能力 */
    public static int disable(int flags, int cap) {
        return flags & ~cap;
    }

    /** 设置 Active 层能力（互斥：清除其他 Active 能力，保留 Always 层） */
    public static int setActive(int flags, int activeCap) {
        // 只保留 Always 层 + 新的 Active 能力
        return (flags & ALWAYS_LAYER_MASK) | activeCap;
    }

    /** 获取当前 Active 层能力（互斥层中激活的那一个） */
    public static int getActive(int flags) {
        return flags & ACTIVE_LAYER_MASK;
    }

    /** 获取中文名 */
    public static String toChineseName(int cap) {
        return switch (cap) {
            case FOLLOW -> "跟随";
            case GUARD -> "守护";
            case GATHER -> "采集";
            case FARM -> "种植";
            case PATROL -> "巡逻";
            case GUARD_SENSE -> "危险感知";
            case SURVIVAL -> "生存";
            default -> "未知";
        };
    }

    /** 获取当前激活的 Active 层中文名 */
    public static String activeName(int flags) {
        int active = getActive(flags);
        if (active == NONE) return "空闲";
        return toChineseName(active);
    }
}
