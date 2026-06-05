package com.aiworkbench.companion.ai;

/**
 * 任务优先级 —— 数值越高越优先执行。
 * <p>
 * 8 级优先级：CRITICAL(100) → IDLE(10)。
 * 动态优先级调整：每 tick 等待 +2，超时 5x 预估时间则强制完成或放弃。
 */
public enum TaskPriority {
    CRITICAL(100),    // 生存危机：岩浆、濒死
    EMERGENCY(90),    // 紧急：敌对生物突袭
    HIGH(70),         // 主人命令、稀有资源（钻石/远古残骸）
    MEDIUM_HIGH(55),  // 高价值采集（钻石矿、绿宝石矿）
    MEDIUM(40),       // 普通采集（铁矿、金矿）
    MEDIUM_LOW(30),   // 背包管理
    LOW(20),          // 工具升级
    IDLE(10);         // 闲逛、发呆

    public final int baseValue;

    TaskPriority(int baseValue) {
        this.baseValue = baseValue;
    }

    /** 将优先级数值映射到最近的枚举值 */
    public static TaskPriority fromValue(int value) {
        if (value >= 90) return CRITICAL;
        if (value >= 80) return EMERGENCY;
        if (value >= 60) return HIGH;
        if (value >= 50) return MEDIUM_HIGH;
        if (value >= 35) return MEDIUM;
        if (value >= 25) return MEDIUM_LOW;
        if (value >= 15) return LOW;
        return IDLE;
    }
}
