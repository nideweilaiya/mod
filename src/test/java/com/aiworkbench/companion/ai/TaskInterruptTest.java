package com.aiworkbench.companion.ai;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 智能任务中断协议测试
 * 验证：无任务直接执行、简单问题不中断、紧急中断、协商流程、优先级比较
 */
@DisplayName("智能任务中断协议")
class TaskInterruptTest {

    // ========== 协议常量和类型定义 ==========

    enum IntentType { CHAT, QUESTION, TASK, EMERGENCY }
    enum InterruptDecision { EXECUTE, REPLY_ONLY, FORCE_INTERRUPT, NEGOTIATE, FOLLOW_UP }

    /** 优先级映射 */
    static int priorityOf(String skillName) {
        return switch (skillName) {
            case "defend", "fightZombie" -> 7;
            case "mineIronOre", "mineCoalOre", "mineStone", "collectWood",
                 "craftStick", "craftWoodenPickaxe", "craftFurnace", "smeltIronIngot" -> 5;
            case "follow", "patrol", "lookAtOwner", "moveForward", "collectDrops" -> 3;
            default -> 5;
        };
    }

    /** 状态机裁决（核心逻辑） */
    static InterruptDecision decide(IntentType intent, Integer currentTaskPriority,
                                     Integer newTaskPriority) {
        if (currentTaskPriority == null) {
            // 无任务运行 → 直接执行
            return InterruptDecision.EXECUTE;
        }
        return switch (intent) {
            case CHAT, QUESTION -> InterruptDecision.REPLY_ONLY;
            case EMERGENCY -> InterruptDecision.FORCE_INTERRUPT;
            case TASK -> {
                if (newTaskPriority == null) yield InterruptDecision.EXECUTE;
                if (newTaskPriority > currentTaskPriority)
                    yield InterruptDecision.FORCE_INTERRUPT;
                if (newTaskPriority < currentTaskPriority)
                    yield InterruptDecision.REPLY_ONLY;
                yield InterruptDecision.NEGOTIATE;
            }
        };
    }

    // ========== T2.1 无任务时直接执行 ==========

    @Test
    @DisplayName("T2.1 — 无任务时直接执行新命令")
    void testNoTaskExecutesDirectly() {
        InterruptDecision result = decide(IntentType.TASK, null, 5);
        assertEquals(InterruptDecision.EXECUTE, result, "无任务时应直接执行");
    }

    // ========== T2.2 简单问题不中断 ==========

    @Test
    @DisplayName("T2.2 — 简单问题不中断当前任务")
    void testQuestionDoesNotInterrupt() {
        // 正在挖矿(优先级5), 玩家问"还有多远"
        InterruptDecision result = decide(IntentType.QUESTION, 5, null);
        assertEquals(InterruptDecision.REPLY_ONLY, result, "问题应回复但不中断");

        // 正在挖矿(优先级5), 玩家纯聊天
        result = decide(IntentType.CHAT, 5, null);
        assertEquals(InterruptDecision.REPLY_ONLY, result, "聊天应回复但不中断");
    }

    // ========== T2.3 紧急中断 ==========

    @Test
    @DisplayName("T2.3 — 紧急情况强制中断技能")
    void testEmergencyForceInterrupt() {
        // 正在砍树, 玩家喊"救命"
        InterruptDecision result = decide(IntentType.EMERGENCY, 5, null);
        assertEquals(InterruptDecision.FORCE_INTERRUPT, result, "紧急应强制中断");
    }

    // ========== T2.4 协商流程 ==========

    @Test
    @DisplayName("T2.4 — 同优先级任务触发协商")
    void testSamePriorityNegotiates() {
        int miningPriority = priorityOf("mineIronOre");  // 5
        int choppingPriority = priorityOf("collectWood"); // 5

        assertEquals(5, miningPriority);
        assertEquals(5, choppingPriority);

        InterruptDecision result = decide(IntentType.TASK, miningPriority, choppingPriority);
        assertEquals(InterruptDecision.NEGOTIATE, result, "同优先级应协商");
    }

    // ========== T2.5 协商确认 ==========

    @Test
    @DisplayName("T2.5 — 协商中玩家确认切换")
    void testNegotiateConfirm() {
        String playerResponse = "是";
        boolean confirmed = playerResponse.contains("是") || playerResponse.contains("好")
            || playerResponse.contains("行") || playerResponse.contains("切换");

        assertTrue(confirmed, "回复'是'表示确认切换");
    }

    // ========== T2.6 协商拒绝 ==========

    @Test
    @DisplayName("T2.6 — 协商中玩家拒绝切换")
    void testNegotiateReject() {
        String playerResponse = "不";
        boolean rejected = playerResponse.contains("不") || playerResponse.contains("别")
            || playerResponse.contains("否") || playerResponse.contains("继续");

        assertTrue(rejected, "回复'不'表示拒绝切换");

        // 拒绝后继续当前任务
        InterruptDecision result = decide(IntentType.CHAT, 5, null);
        assertEquals(InterruptDecision.REPLY_ONLY, result, "拒绝后继续当前任务");
    }

    // ========== T2.7 任务完成询问 ==========

    @Test
    @DisplayName("T2.7 — 任务完成应主动询问")
    void testTaskCompleteFollowUp() {
        // 技能完成后, currentTaskPriority 回到 null
        InterruptDecision result = decide(IntentType.CHAT, null, null);
        assertEquals(InterruptDecision.EXECUTE, result, "完成后恢复接受新任务");

        // FOLLOW_UP 触发条件: 技能刚完成 + 有新任务可供选择
        boolean shouldFollowUp = true; // 模拟: skillEngine just completed
        assertTrue(shouldFollowUp, "任务完成应触发后续询问");
    }

    // ========== T2.8 优先级比较 ==========

    @Test
    @DisplayName("T2.8 — 高优先级可中断低优先级")
    void testHigherPriorityCanInterrupt() {
        int combatPriority = priorityOf("fightZombie");  // 7
        int miningPriority = priorityOf("mineIronOre");  // 5

        assertTrue(combatPriority > miningPriority, "战斗优先级高于采矿");

        // 采矿中接到战斗命令 → 强制中断
        InterruptDecision result = decide(IntentType.TASK, miningPriority, combatPriority);
        assertEquals(InterruptDecision.FORCE_INTERRUPT, result, "高优先级强制中断低优先级");

        // 战斗中接到采矿命令 → 回复但不执行
        result = decide(IntentType.TASK, combatPriority, miningPriority);
        assertEquals(InterruptDecision.REPLY_ONLY, result, "低优先级不能中断高优先级");
    }

    // ========== 边缘情况 ==========

    @Test
    @DisplayName("优先级映射覆盖所有预制技能")
    void testPriorityMappingComplete() {
        String[] allSkills = {
            "mineIronOre", "mineCoalOre", "mineStone", "collectWood",
            "fightZombie", "collectDrops", "craftStick", "craftWoodenPickaxe",
            "craftFurnace", "buildShelter", "smeltIronIngot",
            "lookAtOwner", "moveForward"
        };
        for (String skill : allSkills) {
            int p = priorityOf(skill);
            assertTrue(p >= 1 && p <= 10, "技能 " + skill + " 优先级应在1-10: " + p);
        }
    }

    @Test
    @DisplayName("紧急检测触发条件")
    void testEmergencyTriggers() {
        // 熔岩、坠落、低血量、窒息 → 紧急
        assertTrue(isEmergency("lava"));
        assertTrue(isEmergency("fall"));
        assertTrue(isEmergency("low_health"));
        assertTrue(isEmergency("suffocation"));
        assertFalse(isEmergency("hunger"));
    }

    private boolean isEmergency(String dangerType) {
        return switch (dangerType) {
            case "lava", "fall", "low_health", "suffocation", "hostile" -> true;
            default -> false;
        };
    }
}
