package com.aiworkbench.companion.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

/**
 * 单元测试：BridgeClient JSON 消息序列化/反序列化
 *
 * 【测试驱动开发 — P1 JSON 手动拼接问题】
 *
 * 当前问题：BridgeClient 使用 String.format() 手动拼接 JSON，
 * 不使用 Gson 进行结构化序列化。
 */
class BridgeMessageTest {

    private final Gson gson = new GsonBuilder().create();

    // ==================== 消息类型测试 ====================

    @Nested
    @DisplayName("消息类型定义")
    class MessageTypes {

        @Test
        @DisplayName("心跳消息格式")
        void testHeartbeatFormat() {
            String json = String.format(
                "{\"type\":\"heartbeat\",\"timestamp\":%d}",
                System.currentTimeMillis()
            );
            // 使用 Gson 验证可解析性
            Map<String, Object> msg = gson.fromJson(json, Map.class);
            assertEquals("heartbeat", msg.get("type"));
            assertNotNull(msg.get("timestamp"));
        }

        @Test
        @DisplayName("companion_spawned 消息格式")
        void testCompanionSpawnedFormat() {
            String json = String.format(
                "{\"type\":\"companion_spawned\",\"companion_id\":\"%s\",\"owner_name\":\"%s\",\"x\":%d,\"y\":%d,\"z\":%d}",
                "test-uuid-1234", "Player1", 100, 64, 200
            );
            Map<String, Object> msg = gson.fromJson(json, Map.class);
            assertEquals("companion_spawned", msg.get("type"));
            assertEquals("test-uuid-1234", msg.get("companion_id"));
            assertEquals("Player1", msg.get("owner_name"));
            assertEquals(100.0, ((Double) msg.get("x")).doubleValue());
        }

        @Test
        @DisplayName("dialogue 命令消息格式")
        void testDialogueFormat() {
            // 使用 Gson 构建（正确方式）
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("type", "dialogue");
            data.put("companion_id", "test-uuid");
            data.put("text", "你好！");
            data.put("duration", 5);

            String json = gson.toJson(data);
            Map<String, Object> parsed = gson.fromJson(json, Map.class);

            assertEquals("dialogue", parsed.get("type"));
            assertEquals("test-uuid", parsed.get("companion_id"));
            assertEquals("你好！", parsed.get("text"));
        }

        @Test
        @DisplayName("消息中包含特殊字符应正确处理")
        void testSpecialCharactersInMessage() {
            // 同伴名称含特殊字符的情况
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("type", "dialogue");
            data.put("companion_id", "test-uuid");
            data.put("text", "温度: 25°C & 湿度: 50% <正常>");  // 含特殊字符

            String json = gson.toJson(data);
            Map<String, Object> parsed = gson.fromJson(json, Map.class);

            assertEquals("温度: 25°C & 湿度: 50% <正常>", parsed.get("text"));
        }
    }

    // ==================== JSON 手动拼接 vs Gson 对比 ====================

    @Nested
    @DisplayName("String.format 风险验证")
    class StringFormatRisk {

        /**
         * 演示 String.format 拼接 JSON 的风险
         *
         * 如果 companion_id 或 owner_name 包含 " 或 \，
         * String.format 不会转义，生成无效 JSON。
         */
        @Test
        @DisplayName("String.format 含引号时生成无效 JSON")
        void testStringFormatWithSpecialChars() {
            // 模拟玩家名含特殊字符
            String maliciousName = "Player\"OR\"1\"=\"1";  // SQL注入风格的测试名
            String companionId = "test-uuid";

            // 当前方式：String.format 拼接（会生成无效JSON）
            String badJson = String.format(
                "{\"type\":\"companion_spawned\",\"companion_id\":\"%s\",\"owner_name\":\"%s\"}",
                companionId, maliciousName
            );

            // 此 JSON 应无法解析
            final String json = badJson;
            assertThrows(Exception.class, () -> {
                gson.fromJson(json, Map.class);
            }, "String.format 拼接含特殊字符的 JSON 应解析失败");

            // 正确方式：使用 Gson
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("type", "companion_spawned");
            data.put("companion_id", companionId);
            data.put("owner_name", maliciousName);

            String goodJson = gson.toJson(data);
            assertDoesNotThrow(() -> {
                Map<String, Object> parsed = gson.fromJson(goodJson, Map.class);
                assertEquals(maliciousName, parsed.get("owner_name"));
            }, "Gson 序列化应能正确处理特殊字符");
        }
    }
}
