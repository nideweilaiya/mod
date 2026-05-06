package com.aiworkbench.companion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

/**
 * 单元测试：CompanionConfig JSON 解析
 *
 * 【测试驱动开发】
 * 当前 CompanionConfig 使用手写 JSON 解析器（parseConfig），
 * 测试覆盖边界情况确保其正确性。
 *
 * 测试分类：
 * - 正常解析：标准 JSON 格式
 * - 边界情况：空值、特殊字符、嵌套
 * - 转义处理：JSON 字符串转义
 */
class CompanionConfigTest {

    // ==================== Helper: 通过反射调用 parseConfig ====================

    /**
     * 使用反射调用私有方法 CompanionConfig.parseConfig(String)
     */
    private void invokeParseConfig(String content) throws Exception {
        java.lang.reflect.Method method = CompanionConfig.class.getDeclaredMethod("parseConfig", String.class);
        method.setAccessible(true);
        method.invoke(null, content);
    }

    /**
     * 使用反射调用私有方法 CompanionConfig.escapeJson(String)
     */
    private String invokeEscapeJson(String s) throws Exception {
        java.lang.reflect.Method method = CompanionConfig.class.getDeclaredMethod("escapeJson", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, s);
    }

    /**
     * 使用反射调用私有方法 CompanionConfig.unescapeJson(String)
     */
    private String invokeUnescapeJson(String s) throws Exception {
        java.lang.reflect.Method method = CompanionConfig.class.getDeclaredMethod("unescapeJson", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, s);
    }

    // ==================== 正常解析测试 ====================

    @Nested
    @DisplayName("正常 JSON 解析")
    class NormalParsing {
        @Test
        @DisplayName("单键值对解析")
        void testSingleKeyValue() throws Exception {
            invokeParseConfig("{\"key\":\"value\"}");
            // 配置已加载，验证默认值
            assertNotNull(CompanionConfig.getDefaultModel());
        }

        @Test
        @DisplayName("模型配置读写")
        void testModelConfig() {
            UUID testId = UUID.randomUUID();
            CompanionConfig.setModel(testId, "llama3.2:latest");
            assertEquals("llama3.2:latest", CompanionConfig.getModel(testId));
        }

        @Test
        @DisplayName("默认模型配置")
        void testDefaultModel() {
            CompanionConfig.setDefaultModel("qwen3.5:latest");
            assertEquals("qwen3.5:latest", CompanionConfig.getDefaultModel());
            // 恢复默认
            CompanionConfig.setDefaultModel("llama3.2:latest");
        }
    }

    // ==================== JSON 转义测试 ====================

    @Nested
    @DisplayName("JSON 转义处理")
    class JsonEscaping {
        @Test
        @DisplayName("双引号转义")
        void testEscapeQuotes() throws Exception {
            assertEquals("test\\\"value", invokeEscapeJson("test\"value"));
        }

        @Test
        @DisplayName("反斜杠转义")
        void testEscapeBackslash() throws Exception {
            assertEquals("test\\\\path", invokeEscapeJson("test\\path"));
        }

        @Test
        @DisplayName("换行符转义")
        void testEscapeNewline() throws Exception {
            assertEquals("line1\\nline2", invokeEscapeJson("line1\nline2"));
        }

        @Test
        @DisplayName("转义还原双引号")
        void testUnescapeQuotes() throws Exception {
            assertEquals("test\"value", invokeUnescapeJson("test\\\"value"));
        }

        @Test
        @DisplayName("转义还原反斜杠")
        void testUnescapeBackslash() throws Exception {
            assertEquals("test\\path", invokeUnescapeJson("test\\\\path"));
        }
    }

    // ==================== 边界情况测试 ====================

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {
        @Test
        @DisplayName("空 JSON 对象")
        void testEmptyJson() throws Exception {
            assertDoesNotThrow(() -> invokeParseConfig("{}"));
        }

        @Test
        @DisplayName("空白 JSON 对象")
        void testWhitespaceJson() throws Exception {
            assertDoesNotThrow(() -> invokeParseConfig("  {  }  "));
        }

        @Test
        @DisplayName("null 测试：默认模型不为 null")
        void testDefaultModelNotNull() {
            assertNotNull(CompanionConfig.DEFAULT_MODEL);
            assertFalse(CompanionConfig.DEFAULT_MODEL.isEmpty());
        }
    }

    // ==================== UUID 模型测试 ====================

    @Nested
    @DisplayName("Per-Player 模型配置")
    class PerPlayerModel {
        @Test
        @DisplayName("不同玩家不同模型")
        void testDifferentModels() {
            UUID player1 = UUID.randomUUID();
            UUID player2 = UUID.randomUUID();

            CompanionConfig.setModel(player1, "llama3.2:latest");
            CompanionConfig.setModel(player2, "qwen3.5:latest");

            assertEquals("llama3.2:latest", CompanionConfig.getModel(player1));
            assertEquals("qwen3.5:latest", CompanionConfig.getModel(player2));
        }

        @Test
        @DisplayName("未配置玩家使用默认模型")
        void testUnconfiguredPlayer() {
            UUID newPlayer = UUID.randomUUID();
            assertEquals(CompanionConfig.DEFAULT_MODEL, CompanionConfig.getModel(newPlayer));
        }
    }
}
