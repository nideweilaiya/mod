package com.aiworkbench.companion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 单元测试：CompanionConfig Gson 序列化与公共 API。
 * <p>
 * 不再使用反射访问私有方法，改为测试公共接口和 Gson 往返一致性。
 */
class CompanionConfigTest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>(){}.getType();

    // ==================== Gson 序列化往返测试 ====================

    @Nested
    @DisplayName("Gson 序列化往返")
    class GsonRoundTrip {
        @Test
        @DisplayName("简单 Map → JSON → Map 往返")
        void testSimpleRoundTrip() {
            Map<String, String> original = new HashMap<>();
            original.put("default_model", "llama3.2:latest");
            original.put("ai_endpoint", "http://localhost:11434/api/generate");

            String json = GSON.toJson(original, MAP_TYPE);
            assertNotNull(json);
            assertTrue(json.contains("default_model"));
            assertTrue(json.contains("llama3.2:latest"));

            Map<String, String> parsed = GSON.fromJson(json, MAP_TYPE);
            assertEquals("llama3.2:latest", parsed.get("default_model"));
            assertEquals("http://localhost:11434/api/generate", parsed.get("ai_endpoint"));
        }

        @Test
        @DisplayName("空 Map 往返")
        void testEmptyMap() {
            Map<String, String> empty = new HashMap<>();
            String json = GSON.toJson(empty, MAP_TYPE);
            Map<String, String> parsed = GSON.fromJson(json, MAP_TYPE);
            assertNotNull(parsed);
            assertTrue(parsed.isEmpty());
        }

        @Test
        @DisplayName("包含特殊字符的值")
        void testSpecialCharacters() {
            Map<String, String> original = new HashMap<>();
            original.put("slash_path", "/path/to/model");
            original.put("colon_value", "model:tag:latest");
            original.put("http_url", "http://host:8080/api");

            String json = GSON.toJson(original, MAP_TYPE);
            Map<String, String> parsed = GSON.fromJson(json, MAP_TYPE);
            assertEquals("/path/to/model", parsed.get("slash_path"));
            assertEquals("model:tag:latest", parsed.get("colon_value"));
            assertEquals("http://host:8080/api", parsed.get("http_url"));
        }

        @Test
        @DisplayName("中文内容往返")
        void testChineseContent() {
            Map<String, String> original = new HashMap<>();
            original.put("display_name", "我的同伴");
            original.put("welcome_msg", "你好，世界！");

            String json = GSON.toJson(original, MAP_TYPE);
            Map<String, String> parsed = GSON.fromJson(json, MAP_TYPE);
            assertEquals("我的同伴", parsed.get("display_name"));
            assertEquals("你好，世界！", parsed.get("welcome_msg"));
        }
    }

    // ==================== 公共 API 测试 ====================

    @Nested
    @DisplayName("公共 API")
    class PublicApi {
        @Test
        @DisplayName("默认模型设置与查询")
        void testDefaultModelSetGet() {
            CompanionConfig.setDefaultModel("qwen3.5:latest");
            assertEquals("qwen3.5:latest", CompanionConfig.getDefaultModel());
            CompanionConfig.setDefaultModel("llama3.2:latest");
            assertEquals("llama3.2:latest", CompanionConfig.getDefaultModel());
        }

        @Test
        @DisplayName("不同玩家不同模型")
        void testPerPlayerModel() {
            UUID p1 = UUID.randomUUID();
            UUID p2 = UUID.randomUUID();

            CompanionConfig.setModel(p1, "llama3.2:latest");
            CompanionConfig.setModel(p2, "qwen3.5:latest");

            assertEquals("llama3.2:latest", CompanionConfig.getModel(p1));
            assertEquals("qwen3.5:latest", CompanionConfig.getModel(p2));
        }

        @Test
        @DisplayName("未配置玩家使用默认模型")
        void testUnconfiguredPlayerUsesDefault() {
            UUID newPlayer = UUID.randomUUID();
            String model = CompanionConfig.getModel(newPlayer);
            assertNotNull(model);
            assertFalse(model.isEmpty());
            // 默认模型应为 DEFAULT_MODEL 或设置的默认模型
            assertTrue(model.equals(CompanionConfig.DEFAULT_MODEL)
                || model.equals(CompanionConfig.getDefaultModel()));
        }

        @Test
        @DisplayName("DEFAULT_MODEL 常量不为空")
        void testDefaultModelConstant() {
            assertNotNull(CompanionConfig.DEFAULT_MODEL);
            assertFalse(CompanionConfig.DEFAULT_MODEL.isEmpty());
            assertEquals("llama3.2:latest", CompanionConfig.DEFAULT_MODEL);
        }
    }

    // ==================== 高级配置查询 ====================

    @Nested
    @DisplayName("高级配置查询")
    class AdvancedConfig {
        @Test
        @DisplayName("AI 端点默认值")
        void testAiEndpointDefault() {
            String endpoint = CompanionConfig.getAiEndpoint();
            assertNotNull(endpoint);
            assertTrue(endpoint.startsWith("http"));
        }

        @Test
        @DisplayName("AI 超时默认值合理")
        void testAiTimeoutDefault() {
            int timeout = CompanionConfig.getAiTimeoutMs();
            assertTrue(timeout >= 1000, "超时至少1秒");
            assertTrue(timeout <= 300000, "超时不超过5分钟");
        }

        @Test
        @DisplayName("感知间隔默认值合理")
        void testPerceptionInterval() {
            int interval = CompanionConfig.getPerceptionInterval();
            assertTrue(interval >= 1 && interval <= 100,
                "感知间隔应在1-100 tick之间");
        }

        @Test
        @DisplayName("拾取半径默认值合理")
        void testPickupRadiusDefault() {
            double radius = CompanionConfig.getDefaultPickupRadius();
            assertTrue(radius >= 1.0 && radius <= 16.0,
                "拾取半径应在1-16之间");
        }

        @Test
        @DisplayName("贵重拾取默认关闭")
        void testValuablePickupDefault() {
            assertFalse(CompanionConfig.getDefaultPickupValuableOnly(),
                "默认应拾取所有物品");
        }
    }
}
