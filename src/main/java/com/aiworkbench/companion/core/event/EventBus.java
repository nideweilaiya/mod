package com.aiworkbench.companion.core.event;

import com.aiworkbench.companion.AICompanionMod;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 全局事件总线 — 模块间零耦合通信中枢。
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 *   // 发布事件
 *   EventBus.get().post(Event.of(EventType.RESOURCE_COLLECTED, "item", "oak_log"));
 *
 *   // 订阅事件
 *   EventBus.get().register(EventType.RESOURCE_COLLECTED, event -> {
 *       String item = event.get("item");
 *       // 响应逻辑...
 *   });
 * }</pre>
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>单例：全局唯一实例，通过 {@link #get()} 获取</li>
 *   <li>隔离：单个监听器异常不影响其他监听器</li>
 *   <li>同步：监听器按注册顺序执行（非异步）</li>
 *   <li>轻量：监听器内禁止执行耗时操作</li>
 * </ul>
 */
public class EventBus {

    private static final EventBus INSTANCE = new EventBus();
    private final Map<EventType, List<Consumer<Event>>> listeners = new EnumMap<>(EventType.class);

    private EventBus() {}

    public static EventBus get() {
        return INSTANCE;
    }

    /**
     * 注册事件监听器。
     *
     * @param type     事件类型
     * @param listener 回调函数（消费事件数据）
     */
    public void register(EventType type, Consumer<Event> listener) {
        listeners.computeIfAbsent(type, k -> new ArrayList<>()).add(listener);
    }

    /**
     * 发布事件。所有注册的监听器按顺序同步执行。
     * 单个监听器异常会被捕获并记录，不阻止后续监听器。
     *
     * @param event 事件
     */
    public void post(Event event) {
        List<Consumer<Event>> list = listeners.get(event.type);
        if (list == null || list.isEmpty()) return;

        for (Consumer<Event> listener : list) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                AICompanionMod.LOGGER.error(
                    "[EventBus] Listener error for {}: {}", event.type, e.getMessage()
                );
            }
        }
    }

    /**
     * 清空指定类型的所有监听器（用于测试或热重载）。
     */
    public void clear(EventType type) {
        listeners.remove(type);
    }

    /** 清空所有监听器 */
    public void clearAll() {
        listeners.clear();
    }
}
