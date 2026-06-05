package com.aiworkbench.companion.ai;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 智能任务中断协议 —— 规则驱动的状态机裁决。
 * <p>
 * LLM 负责意图分类（chat/task/question/emergency），本协议负责裁决是否中断当前任务。
 * 零延迟规则决策，只在复杂理解时走 LLM。
 */
public class TaskInterruptProtocol {

    // ==================== 意图类型 ====================

    public enum Intent {
        CHAT,       // 纯聊天
        QUESTION,   // 简单问题（不涉及新任务）
        TASK,       // 新任务请求
        EMERGENCY   // 紧急情况
    }

    // ==================== 裁决结果 ====================

    public enum Decision {
        EXECUTE,          // 直接执行
        REPLY_ONLY,       // 回复但不中断
        FORCE_INTERRUPT,  // 强制中断
        SUSPEND,          // 挂起当前任务（稍后可恢复）
        RESUME,           // 恢复被挂起的任务
        NEGOTIATE,        // 协商（问玩家是否切换）
        FOLLOW_UP         // 任务完成，主动询问后续
    }

    // ==================== 优先级映射 ====================

    /** 返回技能/任务的优先级（1-10，10最高） */
    public static int priorityOf(String skillName) {
        if (skillName == null) return 5;
        return PRIORITY_MAP.getOrDefault(skillName.toLowerCase(), 5);
    }

    private static final Map<String, Integer> PRIORITY_MAP = Map.ofEntries(
        // 紧急/防御 (10-7)
        Map.entry("defend", 10),
        Map.entry("fightzombie", 7),
        // 核心采集/合成 (5)
        Map.entry("mineironore", 5),
        Map.entry("minecoalore", 5),
        Map.entry("minestone", 5),
        Map.entry("collectwood", 5),
        Map.entry("collectdrops", 5),
        Map.entry("smeltironingot", 5),
        Map.entry("craftstick", 5),
        Map.entry("craftwoodenpickaxe", 5),
        Map.entry("craftfurnace", 5),
        // 建筑/庇护所 (5)
        Map.entry("buildshelter", 5),
        // 休闲 (3)
        Map.entry("follow", 3),
        Map.entry("patrol", 3),
        Map.entry("lookatowner", 3),
        Map.entry("moveforward", 3)
    );

    // ==================== 核心裁决逻辑 ====================

    /**
     * 裁决是否中断当前任务。
     *
     * @param intent              AI 分类的意图
     * @param currentTaskPriority 当前任务的优先级（null = 无任务）
     * @param newTaskPriority     新任务的优先级（null = 未请求任务）
     * @return 裁决结果
     */
    public static Decision decide(Intent intent, Integer currentTaskPriority, Integer newTaskPriority) {
        // 无任务运行 → 直接执行
        if (currentTaskPriority == null) {
            return Decision.EXECUTE;
        }

        return switch (intent) {
            case CHAT, QUESTION -> Decision.REPLY_ONLY;
            case EMERGENCY -> Decision.FORCE_INTERRUPT;
            case TASK -> {
                if (newTaskPriority == null) yield Decision.EXECUTE;
                if (newTaskPriority > currentTaskPriority) yield Decision.FORCE_INTERRUPT;
                if (newTaskPriority < currentTaskPriority) yield Decision.REPLY_ONLY;
                yield Decision.NEGOTIATE; // 同优先级 — 协商
            }
        };
    }

    // ==================== 意图快速分类（无需 LLM） ====================

    // 紧急关键词
    private static final Pattern EMERGENCY_PATTERN = Pattern.compile(
        "救命|救我|快跑|逃|危险|岩浆|熔岩|着火了|快死了|救我一下"
    );

    // 简单问题模式（无需中断任务）
    private static final Pattern QUESTION_PATTERN = Pattern.compile(
        "还有多远|还有多久|快好了吗|好了没|完成了吗|你还好吗|你在哪|在哪|怎么样"
    );

    // 确认/拒绝模式
    private static final Pattern CONFIRM_PATTERN = Pattern.compile(
        "是|好|行|可以|切换|嗯|对|yes|ok|确认"
    );
    private static final Pattern REJECT_PATTERN = Pattern.compile(
        "不|别|否|算了|继续|不用|不要|cancel|取消"
    );

    /**
     * 快速分类用户消息的意图（规则优先，未命中返回 null 需走 LLM）。
     */
    public static Intent classifyQuick(String message) {
        if (message == null || message.isEmpty()) return null;

        if (EMERGENCY_PATTERN.matcher(message).find()) {
            return Intent.EMERGENCY;
        }
        if (QUESTION_PATTERN.matcher(message).find()) {
            return Intent.QUESTION;
        }
        return null; // 需要 LLM 分类
    }

    /**
     * 判断玩家回复是确认还是拒绝（用于协商状态）。
     * @return true=确认, false=拒绝, null=无法判断
     */
    public static Boolean isConfirmation(String message) {
        if (message == null) return null;
        if (CONFIRM_PATTERN.matcher(message).find()) return true;
        if (REJECT_PATTERN.matcher(message).find()) return false;
        return null;
    }
}
