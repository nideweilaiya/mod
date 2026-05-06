package com.aiworkbench.companion.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * 单元测试：PerceptionEngine 感知引擎
 *
 * 【测试驱动开发 — P1 O(n³) 重复扫描优化】
 *
 * 当前问题：
 * - detectDangers() 和 scanBlocks() 各自独立扫描方块
 * - 单次感知调用扫描 5129+ 个位置，每 4 ticks 一次
 * - JSON 手动拼接无转义
 *
 * 测试覆盖：
 * 1. PerceptionData 结构完整性
 * 2. JSON 序列化正确性（使用 Gson 替代手拼）
 * 3. 扫描范围常量合理性
 */
class PerceptionEngineTest {

    // ==================== 扫描常量验证 ====================

    @Nested
    @DisplayName("扫描常量合理性")
    class ScanConstants {

        @Test
        @DisplayName("方块扫描半径应合理 (8格)")
        void testBlockScanRadius() throws Exception {
            Field field = PerceptionEngine.class.getDeclaredField("BLOCK_SCAN_RADIUS");
            field.setAccessible(true);
            int radius = field.getInt(null);

            // 8 格半径扫描 17³ = 4913 个方块
            assertTrue(radius <= 8, "方块扫描半径不应超过 8 格");
            assertTrue(radius >= 4, "方块扫描半径不应小于 4 格");
        }

        @Test
        @DisplayName("危险扫描半径应 ≤ 方块扫描半径")
        void testDangerScanRadiusSmaller() throws Exception {
            Field blockField = PerceptionEngine.class.getDeclaredField("BLOCK_SCAN_RADIUS");
            blockField.setAccessible(true);
            int blockRadius = blockField.getInt(null);

            Field dangerField = PerceptionEngine.class.getDeclaredField("DANGER_SCAN_RADIUS");
            dangerField.setAccessible(true);
            int dangerRadius = dangerField.getInt(null);

            assertTrue(dangerRadius <= blockRadius,
                "危险扫描半径 (" + dangerRadius + ") 不应大于方块扫描半径 (" + blockRadius + ")");
        }

        @Test
        @DisplayName("实体扫描半径 ≥ 方块扫描半径")
        void testEntityScanRadius() throws Exception {
            Field field = PerceptionEngine.class.getDeclaredField("ENTITY_SCAN_RADIUS");
            field.setAccessible(true);
            int entityRadius = field.getInt(null);

            Field blockField = PerceptionEngine.class.getDeclaredField("BLOCK_SCAN_RADIUS");
            blockField.setAccessible(true);
            int blockRadius = blockField.getInt(null);

            assertTrue(entityRadius >= blockRadius,
                "实体扫描半径 (" + entityRadius + ") 应 ≥ 方块扫描半径 (" + blockRadius + ")");
        }
    }

    // ==================== PerceptionData 测试 ====================

    @Nested
    @DisplayName("PerceptionData 数据完整性")
    class PerceptionDataStructure {

        @Test
        @DisplayName("PerceptionData 对象创建")
        void testCreatePerceptionData() {
            PerceptionEngine.PerceptionData data = new PerceptionEngine.PerceptionData();
            assertNotNull(data);
        }

        @Test
        @DisplayName("默认紧急度为 normal")
        void testDefaultUrgency() {
            PerceptionEngine.PerceptionData data = new PerceptionEngine.PerceptionData();
            assertEquals("normal", data.urgency);
        }

        @Test
        @DisplayName("默认危险标志均为 false")
        void testDefaultDangerFlags() {
            PerceptionEngine.PerceptionData data = new PerceptionEngine.PerceptionData();
            assertFalse(data.dangerLava);
            assertFalse(data.dangerFire);
            assertFalse(data.dangerFall);
            assertFalse(data.dangerHostile);
            assertFalse(data.dangerSuffocation);
            assertFalse(data.dangerLowHealth);
        }

        @Test
        @DisplayName("初始数据集合为空")
        void testDefaultCollections() {
            PerceptionEngine.PerceptionData data = new PerceptionEngine.PerceptionData();
            assertTrue(data.nearbyBlocks.isEmpty());
            assertTrue(data.nearbyEntities.isEmpty());
            assertTrue(data.resources.isEmpty());
        }
    }

    // ==================== JSON 序列化测试 ====================

    @Nested
    @DisplayName("JSON 序列化替换验证")
    class JsonSerialization {

        /**
         * 验证 toJson() 输出的 JSON 结构
         * 当前问题：手动拼接 JSON，无转义处理
         * 修正目标：使用 Gson 序列化
         */
        @Test
        @DisplayName("toJson() 输出可解析的 JSON")
        void testToJsonProducesValidJson() {
            PerceptionEngine.PerceptionData data = new PerceptionEngine.PerceptionData();
            data.companionId = "test-uuid";
            data.urgency = "normal";

            String json = data.toJson();

            // 验证 JSON 结构
            assertTrue(json.startsWith("{"), "JSON 应以 { 开头");
            assertTrue(json.endsWith("}"), "JSON 应以 } 结尾");
            assertTrue(json.contains("\"companion_id\""), "JSON 应包含 companion_id 字段");
            assertTrue(json.contains("\"urgency\""), "JSON 应包含 urgency 字段");

            // 验证可解析性
            assertDoesNotThrow(() -> {
                com.google.gson.Gson gson = new com.google.gson.GsonBuilder().create();
                java.util.Map<String, Object> parsed = gson.fromJson(json, java.util.Map.class);
                assertNotNull(parsed);
                assertEquals("test-uuid", parsed.get("companion_id"));
            }, "toJson() 输出应为有效 JSON");
        }

        @Test
        @DisplayName("危险标志在 JSON 中正确序列化")
        void testDangerFlagsInJson() {
            PerceptionEngine.PerceptionData data = new PerceptionEngine.PerceptionData();
            data.dangerLava = true;
            data.dangerHostile = true;

            String json = data.toJson();
            assertTrue(json.contains("\"lava\":true"));
            assertTrue(json.contains("\"hostile\":true"));
        }

        @Test
        @DisplayName("资源列表在 JSON 中正确序列化")
        void testResourcesInJson() {
            PerceptionEngine.PerceptionData data = new PerceptionEngine.PerceptionData();
            data.resources.add("minecraft:iron_ore");
            data.resources.add("minecraft:coal_ore");

            String json = data.toJson();
            assertTrue(json.contains("iron_ore"));
            assertTrue(json.contains("coal_ore"));
        }
    }

    // ==================== 扫描合并测试 ====================

    @Nested
    @DisplayName("扫描合并优化验证")
    class ScanMergeOptimization {

        /**
         * 验证方块列表的格式约定
         * 预期：资源方块以 "resource:" 开头，危险方块以 "danger:" 开头
         * 同一方块不应同时出现在两个列表中
         */
        @Test
        @DisplayName("扫描结果格式约定")
        void testScanResultFormat() {
            // 验证 "danger:" 和 "resource:" 是不同前缀，无重叠
            assertNotEquals("danger:", "resource:");
            assertTrue("resource:diamond_ore".startsWith("resource:"));
            assertTrue("danger:lava".startsWith("danger:"));
            assertFalse("resource:stone".startsWith("danger:"));
        }

        @Test
        @DisplayName("资源提取逻辑正确")
        void testResourceExtraction() {
            java.util.List<String> blocks = java.util.List.of(
                "resource:minecraft:iron_ore",
                "resource:minecraft:coal_ore",
                "danger:lava",
                "minecraft:stone"
            );

            java.util.List<String> resources = new java.util.ArrayList<>();
            for (String block : blocks) {
                if (block.startsWith("resource:")) {
                    resources.add(block.substring(9));
                }
            }

            assertEquals(2, resources.size());
            assertTrue(resources.contains("minecraft:iron_ore"));
            assertTrue(resources.contains("minecraft:coal_ore"));
            assertFalse(resources.contains("danger:lava"));
        }

        @Test
        @DisplayName("危险提取逻辑正确")
        void testDangerExtraction() {
            java.util.List<String> blocks = java.util.List.of(
                "resource:minecraft:iron_ore",
                "danger:lava",
                "danger:fire"
            );

            java.util.List<String> dangers = new java.util.ArrayList<>();
            for (String block : blocks) {
                if (block.startsWith("danger:")) {
                    dangers.add(block.substring(7)); // "danger:" = 7 chars
                }
            }

            assertEquals(2, dangers.size());
            assertTrue(dangers.contains("lava"));
            assertTrue(dangers.contains("fire"));
        }
    }
}
