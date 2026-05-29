package com.aiworkbench.companion.core.event;

/**
 * 全局事件类型枚举。
 *
 * <p>每个类型对应一种跨模块通信场景。模块之间禁止直接引用对方的类，
 * 所有跨模块通信通过 {@link EventBus#post(Event)} 发布事件，
 * 对方通过 {@link EventBus#register(EventType, java.util.function.Consumer)} 订阅。
 */
public enum EventType {

    /** 动作原语执行完成（携带动作名和结果） */
    ACTION_COMPLETED,

    /** 动作原语执行失败（携带失败原因） */
    ACTION_FAILED,

    /** 资源被采集（携带物品类型和数量） */
    RESOURCE_COLLECTED,

    /** 检测到威胁（携带威胁实体信息） */
    THREAT_DETECTED,

    /** 威胁消失 */
    THREAT_GONE,

    /** 背包内容变化 */
    INVENTORY_CHANGED,

    /** 血量低于阈值（默认 &lt; 6） */
    HEALTH_LOW,

    /** 伙伴空闲（无任务可执行） */
    COMPANION_IDLE
}
