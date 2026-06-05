package com.aiworkbench.companion.skill.atomic;

import static org.junit.jupiter.api.Assertions.*;

import com.aiworkbench.companion.task.TaskTarget;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * 验证 BreakBlockAction v2.1 的目标锁定和搜索降频逻辑
 *
 * 注意: 不使用 Blocks.* (会触发 Minecraft 注册表 bootstrap，JUnit 独立运行时失败)。
 *       改用 TaskTarget.mineBlock() 创建目标，该工厂方法不依赖 BlockState。
 */
public class BreakBlockActionTest {

    /**
     * 验证: setTaskTarget 后 hasExternalTarget = true
     */
    @Test
    void testExternalTargetPreventsSearch() throws Exception {
        BreakBlockAction action = new BreakBlockAction(
            BlockMatcher.contains("log"));

        BlockPos targetPos = new BlockPos(10, 64, 10);
        TaskTarget target = TaskTarget.mineBlock(targetPos, "minecraft:oak_log");
        action.setTaskTarget(target);

        var field = BreakBlockAction.class.getDeclaredField("hasExternalTarget");
        field.setAccessible(true);
        assertTrue(field.getBoolean(action), "setTaskTarget后 hasExternalTarget = true");
    }

    /**
     * 验证: reset() 清空所有状态字段
     */
    @Test
    void testResetClearsAllState() throws Exception {
        BreakBlockAction action = new BreakBlockAction(
            BlockMatcher.contains("log"));

        // 设置外部目标
        TaskTarget target = TaskTarget.mineBlock(
            new BlockPos(10, 64, 10), "minecraft:oak_log");
        action.setTaskTarget(target);

        // 模拟修改搜索计时器
        var searchField = BreakBlockAction.class.getDeclaredField("searchTimer");
        searchField.setAccessible(true);
        searchField.setInt(action, 8);

        // reset
        action.reset();

        // 验证: 所有字段归零
        var extField = BreakBlockAction.class.getDeclaredField("hasExternalTarget");
        extField.setAccessible(true);
        assertFalse(extField.getBoolean(action), "hasExternalTarget 归零");

        assertEquals(0, searchField.getInt(action), "searchTimer 归零");
    }

    /**
     * 验证: SEARCH_INTERVAL = 10
     */
    @Test
    void testSearchIntervalIsCorrect() throws Exception {
        var field = BreakBlockAction.class.getDeclaredField("SEARCH_INTERVAL");
        field.setAccessible(true);
        assertEquals(10, field.getInt(null), "SEARCH_INTERVAL = 10");
    }

    /**
     * 验证: 超时值正确
     */
    @Test
    void testTimeoutValue() throws Exception {
        BreakBlockAction action = new BreakBlockAction(
            BlockMatcher.contains("stone"));

        var timeoutField = BreakBlockAction.class.getDeclaredField("TIMEOUT_TICKS");
        timeoutField.setAccessible(true);
        assertEquals(600, timeoutField.getInt(null), "TIMEOUT_TICKS = 600");
    }
}
