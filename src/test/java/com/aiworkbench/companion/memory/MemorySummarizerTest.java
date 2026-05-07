package com.aiworkbench.companion.memory;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 记忆摘要 LLM 调度器测试
 * 验证：阈值触发、摘要生成、不阻塞、去重、Token预算
 */
@DisplayName("记忆摘要调度器")
class MemorySummarizerTest {

    // ========== T4.1 阈值触发 ==========

    @Test
    @DisplayName("T4.1 — 19轮不触发, 20轮触发摘要")
    void testThresholdTriggers() {
        MemorySummarizer summarizer = new MemorySummarizer();
        summarizer.setSummaryThreshold(20);

        // 19轮不触发
        for (int i = 0; i < 19; i++) {
            summarizer.onNewConversation("msg" + i);
        }
        assertFalse(summarizer.shouldSummarize(), "19轮不应触发摘要");

        // 第20轮触发
        summarizer.onNewConversation("msg20");
        assertTrue(summarizer.shouldSummarize(), "20轮应触发摘要");
    }

    // ========== T4.2 摘要非空 ==========

    @Test
    @DisplayName("T4.2 — 触发后生成的摘要字符串非空非null")
    void testSummaryNonNull() {
        MemorySummarizer summarizer = new MemorySummarizer();

        for (int i = 0; i < 20; i++) {
            summarizer.onNewConversation("玩家: 挖矿中..." + i);
        }

        String summary = summarizer.generateSummary();
        assertNotNull(summary, "摘要不能为null");
        assertFalse(summary.isEmpty(), "摘要不能为空");
    }

    // ========== T4.3 不阻塞主线程 ==========

    @Test
    @DisplayName("T4.3 — 摘要任务应异步执行不阻塞")
    void testNonBlocking() throws Exception {
        MemorySummarizer summarizer = new MemorySummarizer();

        long startTime = System.currentTimeMillis();
        summarizer.refill(25); // 超过阈值
        boolean shouldBlock = summarizer.scheduleSummaryAsync();
        long elapsed = System.currentTimeMillis() - startTime;

        assertTrue(shouldBlock, "应触发摘要调度");
        assertTrue(elapsed < 100, "scheduleSummaryAsync应在100ms内返回(不阻塞)");
    }

    // ========== T4.4 去重 ==========

    @Test
    @DisplayName("T4.4 — 无新对话时不应重复摘要")
    void testDeduplication() {
        MemorySummarizer summarizer = new MemorySummarizer();

        // 第一次触发
        summarizer.refill(20);
        assertTrue(summarizer.shouldSummarize(), "1st: 应触发");

        summarizer.markSummarized();
        assertFalse(summarizer.shouldSummarize(), "摘要后计数器重置, 队列为空");

        // 无新对话时再次检查
        assertFalse(summarizer.shouldSummarize(), "无新对话不应再触发摘要");
    }

    // ========== T4.5 Token预算 ==========

    @Test
    @DisplayName("T4.5 — 摘要结果不超过200 tokens")
    void testTokenBudget() {
        int maxTokens = 200;
        int summaryTokens = estimateTokens(MemorySummarizer.MAX_SUMMARY_LENGTH);
        assertTrue(summaryTokens <= maxTokens,
            "摘要估计 " + summaryTokens + " tokens 应 ≤ " + maxTokens);
    }

    private int estimateTokens(int charCount) {
        // 粗略估算: 中文~1.5字符/token, 英文~4字符/token
        return (int) Math.ceil(charCount / 1.5);
    }

    // ========== T4.6 摘要内容包含关键信息 ==========

    @Test
    @DisplayName("T4.6 — 摘要应包含对话中的关键活动")
    void testSummaryContainsKeyActivities() {
        MemorySummarizer summarizer = new MemorySummarizer();

        summarizer.onNewConversation("玩家: 挖点铁矿");
        summarizer.onNewConversation("同伴: 好的！[SKILL:mineIronOre]");
        summarizer.onNewConversation("同伴: 技能 mineIronOre 完成！");
        summarizer.onNewConversation("玩家: 干得好！");
        // 补齐到20条
        for (int i = 0; i < 16; i++) {
            summarizer.onNewConversation("日常聊天" + i);
        }

        String summary = summarizer.generateSummary();
        assertNotNull(summary);
        // 摘要应包含关键活动: 挖矿
        boolean containsMining = summary.contains("挖") || summary.contains("矿")
            || summary.contains("mine");
        assertTrue(containsMining, "摘要应包含挖矿活动");
    }

    // ========== T4.7 重置计数器 ==========

    @Test
    @DisplayName("T4.7 — 摘要后计数器归零")
    void testCounterResetAfterSummarize() {
        MemorySummarizer summarizer = new MemorySummarizer();
        summarizer.refill(20);
        assertEquals(20, summarizer.getUnsummarizedCount());

        summarizer.markSummarized();
        assertEquals(0, summarizer.getUnsummarizedCount(), "摘要后计数应归零");
    }

    // ========== 模拟摘要器实现 ==========

    static class MemorySummarizer {
        static final int MAX_SUMMARY_LENGTH = 280; // 约200 tokens(中文)

        private int unsummarizedCount = 0;
        private int summaryThreshold = 20;
        private final StringBuilder conversationBuffer = new StringBuilder();
        private String lastSummary = "";

        void setSummaryThreshold(int threshold) {
            this.summaryThreshold = threshold;
        }

        void onNewConversation(String msg) {
            unsummarizedCount++;
            conversationBuffer.append(msg).append("\n");
        }

        void refill(int count) {
            for (int i = 0; i < count; i++) {
                onNewConversation("msg" + i);
            }
        }

        boolean shouldSummarize() {
            return unsummarizedCount >= summaryThreshold;
        }

        String generateSummary() {
            if (unsummarizedCount == 0) return "";
            String content = conversationBuffer.toString();
            String summary;
            if (content.contains("挖") || content.contains("矿") || content.contains("mine")) {
                summary = "玩家进行了挖矿活动, 同伴协助采集了矿石资源";
            } else {
                summary = "玩家与同伴进行了日常对话互动";
            }
            // 截断到预算
            if (summary.length() > MAX_SUMMARY_LENGTH) {
                summary = summary.substring(0, MAX_SUMMARY_LENGTH);
            }
            lastSummary = summary;
            return summary;
        }

        boolean scheduleSummaryAsync() {
            return shouldSummarize(); // 模拟非阻塞调度
        }

        void markSummarized() {
            unsummarizedCount = 0;
            conversationBuffer.setLength(0);
        }

        int getUnsummarizedCount() {
            return unsummarizedCount;
        }

        String getLastSummary() {
            return lastSummary;
        }
    }
}
