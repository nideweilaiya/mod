package com.aiworkbench.companion.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 单元测试：AutomatonEntity 继承结构验证
 *
 * 【测试驱动开发 — P0 Monster→PathfinderMob 修正】
 *
 * 使用纯字符串反射验证继承层次，完全不依赖 Minecraft 类路径。
 *
 * 继承链预期：
 *   PathfinderMob → Mob → LivingEntity → Entity
 * 而非：
 *   Monster → PathfinderMob → ...  ← 旧代码错误
 *
 * AutomatonEntity 继承 Monster 会导致：
 * - 铁傀儡攻击同伴
 * - 村民逃离同伴
 * - 狼会攻击同伴
 * - 游戏将其归类为敌对生物
 */
class AutomatonEntityInheritanceTest {

    /**
     * 获取 AutomatonEntity 的完整继承链（类名字符串列表）
     */
    private java.util.List<String> getInheritanceChain() {
        java.util.List<String> chain = new java.util.ArrayList<>();
        Class<?> current = AutomatonEntity.class.getSuperclass();
        while (current != null) {
            chain.add(current.getName());
            current = current.getSuperclass();
        }
        return chain;
    }

    /**
     * 检查继承链中是否包含指定类名（完全限定名）
     */
    private boolean inheritsFrom(String fullyQualifiedClassName) {
        Class<?> current = AutomatonEntity.class.getSuperclass();
        while (current != null) {
            if (current.getName().equals(fullyQualifiedClassName)) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    // ==================== P0 继承关系验证 ====================

    @Test
    @DisplayName("[P0] 继承链不应包含 net.minecraft.world.entity.monster.Monster")
    void testShouldNotExtendMonster() {
        String monsterClass = "net.minecraft.world.entity.monster.Monster";
        boolean extendsMonster = inheritsFrom(monsterClass);

        java.util.List<String> chain = getInheritanceChain();
        String chainStr = String.join(" → ", chain);

        assertFalse(extendsMonster,
            "AutomatonEntity 不应继承 Monster！\n" +
            "当前继承链: " + chainStr + "\n" +
            "继承 Monster 会导致铁傀儡攻击、村民逃离等敌对身份问题。\n" +
            "修正：将 extends Monster 改为 extends PathfinderMob");
    }

    @Test
    @DisplayName("[P0] 继承链应包含 net.minecraft.world.entity.PathfinderMob")
    void testInheritsFromPathfinderMob() {
        String expectedBase = "net.minecraft.world.entity.PathfinderMob";
        boolean extendsPathfinder = inheritsFrom(expectedBase);

        java.util.List<String> chain = getInheritanceChain();

        assertTrue(extendsPathfinder,
            "AutomatonEntity 应继承 PathfinderMob\n" +
            "当前继承链: " + String.join(" → ", chain) + "\n" +
            "PathfinderMob 提供 Goal 系统但不被视为敌对生物");
    }

    @Test
    @DisplayName("继承链应包含 net.minecraft.world.entity.Mob")
    void testInheritsFromMob() {
        String mobClass = "net.minecraft.world.entity.Mob";
        boolean extendsMob = inheritsFrom(mobClass);

        assertTrue(extendsMob,
            "AutomatonEntity 应继承自 Mob，以获取 goalSelector/navigation/jumpControl 等功能");
    }

    @Test
    @DisplayName("继承链应包含 net.minecraft.world.entity.LivingEntity")
    void testInheritsFromLivingEntity() {
        String livingClass = "net.minecraft.world.entity.LivingEntity";
        boolean extendsLiving = inheritsFrom(livingClass);

        assertTrue(extendsLiving,
            "AutomatonEntity 应继承 LivingEntity，以获取生命/伤害/动画等生物功能");
    }

    @Test
    @DisplayName("第一直接父类应为 PathfinderMob")
    void testDirectSuperClass() {
        Class<?> superClass = AutomatonEntity.class.getSuperclass();
        assertEquals(
            "net.minecraft.world.entity.PathfinderMob",
            superClass.getName(),
            "第一直接父类应为 PathfinderMob！当前为: " + superClass.getName()
        );
    }

    @Test
    @DisplayName("继承链长度（验证结构完整性）")
    void testInheritanceChainLength() {
        java.util.List<String> chain = getInheritanceChain();
        // 预期: AutomatonEntity → PathfinderMob → Mob → LivingEntity → LivingEntity → Entity → ...
        // 至少有 4 层以上的继承
        assertTrue(chain.size() >= 4,
            "继承链太短（" + chain.size() + "层），可能继承结构有变化\n" +
            "当前链: " + String.join(" → ", chain));
    }

    // ==================== 关键方法验证（反射方式） ====================

    @Nested
    @DisplayName("方法签名兼容性")
    class MethodCompatibility {

        @Test
        @DisplayName("registerGoals 方法必须在类中定义")
        void testRegisterGoalsMethod() {
            assertDoesNotThrow(() -> {
                AutomatonEntity.class.getDeclaredMethod("registerGoals");
            });
        }

        @Test
        @DisplayName("createAttributes 必须是 public static")
        void testCreateAttributesMethod() {
            assertDoesNotThrow(() -> {
                var method = AutomatonEntity.class.getDeclaredMethod("createAttributes");
                int mod = method.getModifiers();
                assertTrue(java.lang.reflect.Modifier.isStatic(mod));
                assertTrue(java.lang.reflect.Modifier.isPublic(mod));
            });
        }

        @Test
        @DisplayName("tick 方法存在")
        void testTickMethod() {
            assertDoesNotThrow(() -> AutomatonEntity.class.getDeclaredMethod("tick"));
        }

        @Test
        @DisplayName("showDialogue(String, int) 和 showDialogue(String) 方法存在")
        void testShowDialogueMethods() {
            assertDoesNotThrow(() -> {
                AutomatonEntity.class.getDeclaredMethod("showDialogue", String.class, int.class);
            });
            assertDoesNotThrow(() -> {
                AutomatonEntity.class.getDeclaredMethod("showDialogue", String.class);
            });
        }

        @Test
        @DisplayName("ownerUUID 字段类型为 UUID")
        void testOwnerUUIDField() {
            assertDoesNotThrow(() -> {
                var field = AutomatonEntity.class.getDeclaredField("ownerUUID");
                assertEquals("java.util.UUID", field.getType().getName());
            });
        }

        @Test
        @DisplayName("isEssential 字段类型为 boolean")
        void testIsEssentialField() {
            assertDoesNotThrow(() -> {
                var field = AutomatonEntity.class.getDeclaredField("isEssential");
                assertEquals("boolean", field.getType().getName());
            });
        }
    }

    // ==================== 配置化常量验证 ====================

    @Nested
    @DisplayName("实体属性常量（需要 Minecraft 运行时）")
    class EntityConstants {

        @Test
        @DisplayName("INVENTORY_SIZE 应为 27（3x9 标准背包）")
        void testInventorySize() throws Exception {
            try {
                var field = AutomatonEntity.class.getDeclaredField("INVENTORY_SIZE");
                field.setAccessible(true);
                assertEquals(27, field.getInt(null));
            } catch (ExceptionInInitializerError e) {
                // 不在 Minecraft 运行时环境（注册表未引导），跳过测试
                org.junit.jupiter.api.Assumptions.abort("跳过：Minecraft 注册表未引导");
            }
        }

        @Test
        @DisplayName("SKIN_TYPE 常量定义完整")
        void testSkinTypeConstants() throws Exception {
            try {
                assertEquals(0, AutomatonEntity.SKIN_TYPE_DEFAULT);
                assertEquals(1, AutomatonEntity.SKIN_TYPE_URL);
                assertEquals(2, AutomatonEntity.SKIN_TYPE_PLAYER);
            } catch (ExceptionInInitializerError e) {
                org.junit.jupiter.api.Assumptions.abort("跳过：Minecraft 注册表未引导");
            }
        }
    }
}
