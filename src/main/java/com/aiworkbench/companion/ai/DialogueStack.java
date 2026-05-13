package com.aiworkbench.companion.ai;

import com.aiworkbench.companion.AICompanionMod;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 对话消息栈 —— 暂存同伴主动提出的问题/提议，等待玩家回应。
 * <p>
 * 与 TaskInterruptProtocol 协作：提议入栈 → 玩家自然语言回应 → 匹配到对应提议 → 执行/拒绝。
 * <p>
 * 特性：
 * <ul>
 *   <li>最多 5 条待处理消息</li>
 *   <li>同类型消息 10s 内去重</li>
 *   <li>生存类消息永不过期，普通消息 60s 过期</li>
 *   <li>按优先级排序（CRITICAL > HIGH > NORMAL > CHAT）</li>
 *   <li>自然语言模糊匹配（"好"/"挖" → 匹配采集提议）</li>
 * </ul>
 */
public class DialogueStack {

    public static final int MAX_SIZE = 5;
    private static final int NORMAL_EXPIRE_SECONDS = 60;
    private static final int DEDUP_WINDOW_MS = 10_000;

    private final PriorityQueue<PendingMessage> messages = new PriorityQueue<>();
    private final Map<String, Long> categoryLastPush = new HashMap<>();

    // ==================== 入栈 ====================

    /**
     * 推入一条待处理消息。同类型去重，超限时淘汰最低优先级。
     */
    public void push(PendingMessage msg) {
        // 同类型去重：10s 内不重复
        long now = System.currentTimeMillis();
        Long last = categoryLastPush.get(msg.category);
        if (last != null && now - last < DEDUP_WINDOW_MS) {
            return;
        }
        categoryLastPush.put(msg.category, now);

        // 超限：移除最低优先级的非紧急消息
        if (messages.size() >= MAX_SIZE) {
            PendingMessage lowest = null;
            for (PendingMessage m : messages) {
                if (m.priority != MessagePriority.CRITICAL) {
                    if (lowest == null || m.priority.ordinal() > lowest.priority.ordinal()) {
                        lowest = m;
                    }
                }
            }
            if (lowest != null) messages.remove(lowest);
        }

        messages.add(msg);
        AICompanionMod.LOGGER.info("[DialogueStack] Push: {} (stack size: {})", msg, messages.size());
    }

    // ==================== 出栈 / 匹配 ====================

    /**
     * 用玩家自然语言匹配最相关的待处理消息。
     * @param playerText 玩家说的话
     * @return 匹配到的消息（同时从栈中移除），null 表示未匹配
     */
    public PendingMessage matchAndPop(String playerText) {
        if (playerText == null || playerText.isEmpty() || messages.isEmpty()) return null;

        String text = playerText.toLowerCase().trim();

        // 确认类关键词 → 弹最高优先级
        if (isConfirm(text)) {
            PendingMessage top = messages.poll();
            AICompanionMod.LOGGER.info("[DialogueStack] Confirmed: {}", top);
            return top;
        }
        // 拒绝类关键词 → 移除最高优先级
        if (isReject(text)) {
            PendingMessage top = messages.poll();
            AICompanionMod.LOGGER.info("[DialogueStack] Rejected: {}", top);
            top.response = "rejected";
            return top;
        }

        // 关键词匹配
        PendingMessage best = null;
        int bestScore = 0;
        for (PendingMessage m : messages) {
            int s = matchScore(text, m);
            if (s > bestScore) { bestScore = s; best = m; }
        }
        if (best != null && bestScore >= 2) {
            messages.remove(best);
            best.response = playerText;
            AICompanionMod.LOGGER.info("[DialogueStack] Matched: {} by '{}' (score={})", best, playerText, bestScore);
            return best;
        }
        return null;
    }

    /**
     * 移除并返回最高优先级消息（用于GUI确认按钮）。
     */
    public PendingMessage popTop() {
        return messages.poll();
    }

    // ==================== 查询 ====================

    public boolean isEmpty() { return messages.isEmpty(); }
    public int size() { return messages.size(); }

    /** 获取所有待处理消息的摘要（用于HUD/GUI显示） */
    public List<PendingMessage> getAll() {
        List<PendingMessage> list = new ArrayList<>(messages);
        list.sort(Comparator.comparingInt(m -> m.priority.ordinal()));
        return list;
    }

    /** 清理所有过期消息。每 tick 调用。 */
    public void expireStale() {
        long now = System.currentTimeMillis();
        messages.removeIf(m -> m.priority != MessagePriority.CRITICAL
            && now - m.createdAt > NORMAL_EXPIRE_SECONDS * 1000L);
        // 紧急消息也清理过期的（120s）
        messages.removeIf(m -> m.priority == MessagePriority.CRITICAL
            && now - m.createdAt > 120_000);
    }

    public void clear() { messages.clear(); }

    // ==================== 匹配逻辑 ====================

    private static final Pattern CONFIRM_PAT = Pattern.compile(
        "好|行|可以|确认|是|嗯|对|yes|ok|去吧|挖吧|做吧");
    private static final Pattern REJECT_PAT = Pattern.compile(
        "不|别|否|算了|继续|不用|不要|cancel|取消|等等|等一下|先不");

    private boolean isConfirm(String text) {
        return CONFIRM_PAT.matcher(text).find() && text.length() <= 5;
    }

    private boolean isReject(String text) {
        return REJECT_PAT.matcher(text).find() && text.length() <= 5;
    }

    /** 计算文本和消息的匹配分数 */
    private int matchScore(String playerText, PendingMessage msg) {
        int score = 0;
        String lower = playerText.toLowerCase();
        // 消息内容关键词匹配
        for (String kw : msg.matchKeywords) {
            if (lower.contains(kw)) score += 3;
        }
        // 动作词匹配
        if (msg.category.equals("gather") && (lower.contains("挖") || lower.contains("采") || lower.contains("矿"))) score += 2;
        if (msg.category.equals("combat") && (lower.contains("打") || lower.contains("战") || lower.contains("杀"))) score += 2;
        if (msg.category.equals("build") && (lower.contains("建") || lower.contains("造") || lower.contains("盖"))) score += 2;
        if (msg.category.equals("follow") && (lower.contains("跟") || lower.contains("走") || lower.contains("回"))) score += 2;
        return score;
    }

    // ==================== 数据类型 ====================

    public enum MessagePriority {
        CRITICAL,  // 生存相关，永不过期
        HIGH,      // 战斗/主人命令
        NORMAL,    // 资源采集提议
        CHAT       // 闲聊
    }

    public static class PendingMessage implements Comparable<PendingMessage> {
        public final String id;
        public final String text;           // 显示文本
        public final String category;       // gather/combat/survival/build/chat
        public final MessagePriority priority;
        public final long createdAt;
        public final List<String> matchKeywords; // 用于自然语言匹配的关键词
        public final String linkedSkill;    // 确认后执行的技能名（可空）
        public String response;             // 玩家回应文本

        public PendingMessage(String id, String text, String category,
                              MessagePriority priority, String linkedSkill, String... keywords) {
            this.id = id;
            this.text = text;
            this.category = category;
            this.priority = priority;
            this.linkedSkill = linkedSkill;
            this.matchKeywords = keywords != null ? Arrays.asList(keywords) : Collections.emptyList();
            this.createdAt = System.currentTimeMillis();
        }

        @Override
        public int compareTo(PendingMessage other) {
            return Integer.compare(this.priority.ordinal(), other.priority.ordinal());
        }

        @Override
        public String toString() {
            return String.format("[%s] %s", priority.name().charAt(0), text);
        }
    }
}
