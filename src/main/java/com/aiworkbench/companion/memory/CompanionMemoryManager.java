package com.aiworkbench.companion.memory;

import com.aiworkbench.companion.AICompanionMod;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 三层记忆管理器 — 协调 L1 工作记忆、L2 情景记忆、L3 语义记忆。
 * <p>
 * L1 工作记忆：Java 内存中的对话历史 + 注入的记忆摘要（~2K tokens）
 * L2 情景记忆：JSON 文件，每 20 轮对话生成一个摘要块
 * L3 语义记忆：JSON 文件，玩家画像（偏好/风格/关系值）
 */
public class CompanionMemoryManager implements AutoCloseable {

    private final MemoryStore store;
    private final MemorySummarizer summarizer;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MemoryScheduler");
        t.setDaemon(true);
        return t;
    });

    // 未摘要的对话计数
    private final Map<String, AtomicInteger> unsummarizedCounts = new ConcurrentHashMap<>();
    private static final int SUMMARY_THRESHOLD = 20;

    public CompanionMemoryManager(java.nio.file.Path worldDir) {
        this.store = new MemoryStore(worldDir);
        this.summarizer = new MemorySummarizer();
    }

    // ==================== 对话日志 ====================

    /**
     * 记录一条对话到 L2 日志。
     */
    public void logConversation(String companionUuid, String role, String content) {
        store.appendConversation(companionUuid, role, content);
        AtomicInteger count = unsummarizedCounts.computeIfAbsent(companionUuid,
            k -> new AtomicInteger(0));
        count.incrementAndGet();
    }

    // ==================== 记忆检索（注入 L1） ====================

    /**
     * 获取应注入 LLM 上下文的记忆摘要。
     */
    public String getMemoryContext(String companionUuid) {
        StringBuilder ctx = new StringBuilder();

        // L3: 玩家画像摘要
        Map<String, Object> profile = store.loadProfile(companionUuid);
        if (!profile.isEmpty()) {
            ctx.append("[关于主人] ");
            profile.forEach((k, v) -> ctx.append(k).append(": ").append(v).append("; "));
            ctx.append("\n");
        }

        // L2: 最近情景摘要块
        List<Map<String, Object>> summaries = store.loadAllSummaries(companionUuid);
        if (!summaries.isEmpty()) {
            int start = Math.max(0, summaries.size() - 3); // 最近 3 个块
            ctx.append("[最近经历]\n");
            for (int i = start; i < summaries.size(); i++) {
                String s = (String) summaries.get(i).getOrDefault("summary", "");
                if (!s.isEmpty()) ctx.append("- ").append(s).append("\n");
            }
        }

        return ctx.toString().trim();
    }

    // ==================== L3 语义记忆 ====================

    /**
     * 更新玩家画像中的某个键值对。
     */
    public void updateProfile(String companionUuid, String key, Object value) {
        Map<String, Object> profile = store.loadProfile(companionUuid);
        profile.put(key, value);
        store.saveProfile(companionUuid, profile);
    }

    /**
     * 增加关系值。
     */
    public void addRelationshipScore(String companionUuid, int delta) {
        Map<String, Object> profile = store.loadProfile(companionUuid);
        int current = ((Number) profile.getOrDefault("relationship", 50)).intValue();
        profile.put("relationship", Math.min(100, Math.max(0, current + delta)));
        store.saveProfile(companionUuid, profile);
    }

    public int getRelationshipScore(String companionUuid) {
        Map<String, Object> profile = store.loadProfile(companionUuid);
        return ((Number) profile.getOrDefault("relationship", 50)).intValue();
    }

    // ==================== 记忆摘要调度 ====================

    /**
     * 检查是否需要触发摘要（在定期 tick 中调用）。
     */
    public void tick() {
        for (Map.Entry<String, AtomicInteger> entry : unsummarizedCounts.entrySet()) {
            if (entry.getValue().get() >= SUMMARY_THRESHOLD) {
                String uuid = entry.getKey();
                // 异步执行摘要，避免阻塞游戏主线程
                scheduler.submit(() -> generateSummary(uuid));
            }
        }
    }

    private void generateSummary(String uuid) {
        int blockIndex = store.getNextSummaryBlockIndex(uuid);
        List<Map<String, Object>> conversations = store.readRecentConversations(uuid, SUMMARY_THRESHOLD);

        if (conversations.isEmpty()) return;

        StringBuilder raw = new StringBuilder();
        for (Map<String, Object> entry : conversations) {
            raw.append(entry.get("role")).append(": ").append(entry.get("content")).append("\n");
        }

        // 调用 LLM 摘要
        String summary = summarizer.summarize(raw.toString());
        if (summary != null && !summary.isEmpty()) {
            store.saveSummaryBlock(uuid, blockIndex, summary);
            AICompanionMod.LOGGER.info("[Memory] Summary block {} generated for companion {}", blockIndex, uuid);
        }

        // 重置计数
        AtomicInteger count = unsummarizedCounts.get(uuid);
        if (count != null) count.set(0);
    }

    // ==================== 初始化/清理 ====================

    /**
     * 在新同伴创建时初始化默认画像。
     */
    public void initializeCompanion(String uuid) {
        Map<String, Object> profile = store.loadProfile(uuid);
        if (profile.isEmpty()) {
            profile.put("relationship", 50);
            profile.put("style", "unknown");
            profile.put("created", System.currentTimeMillis());
            profile.put("total_conversations", 0);
            store.saveProfile(uuid, profile);
        }
    }

    @Override
    public void close() {
        scheduler.shutdown();
    }
}
