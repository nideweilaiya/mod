package com.aiworkbench.companion.core.event;

import java.util.Map;

/**
 * 全局事件，通过 {@link EventBus} 发布和订阅。
 *
 * <p>每个事件携带一个类型（{@link EventType}）和一组键值对数据。
 * 使用 {@code data} Map 而非强类型字段，确保事件总线不依赖任何具体模块的类型。
 */
public class Event {

    public final EventType type;
    public final Map<String, Object> data;

    public Event(EventType type, Map<String, Object> data) {
        this.type = type;
        this.data = data;
    }

    /** 快捷构造：单键值事件 */
    public static Event of(EventType type, String key, Object value) {
        return new Event(type, Map.of(key, value));
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) data.get(key);
    }

    @Override
    public String toString() {
        return "Event{" + type + " " + data + "}";
    }
}
