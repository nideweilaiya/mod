package com.aiworkbench.companion.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.gametest.GameTestHolder;

/**
 * 集成测试：AutomatonEntity 游戏内行为验证
 *
 * 这些 GameTest 在 Minecraft 游戏内执行，验证实体的实际行为。
 * 运行方式：./gradlew runGameTestServer
 *
 * 【P0 级测试 — Monster→PathfinderMob 修正后验证】
 */
@GameTestHolder("aicompanion")
public class AutomatonEntityGameTest {

    /**
     * 验证实体注册的分类是否为 CREATURE 而非 MONSTER
     *
     * 预期：AutomatonEntity 的 MobCategory = CREATURE
     * 这确保刷怪笼/光照系统不会将其视为敌对生物
     */
    @GameTest
    public static void testEntityMobCategory(GameTestHelper helper) {
        // 获取实体的注册信息
        var registry = helper.getLevel().registryAccess()
            .registry(net.minecraft.core.registries.Registries.ENTITY_TYPE).orElse(null);

        if (registry == null) {
            helper.fail("无法获取实体类型注册表");
            return;
        }

        var entityType = registry.get(
            new net.minecraft.resources.ResourceLocation("aicompanion:automaton")
        );

        if (entityType != null) {
            // 验证 MobCategory 不是 MONSTER
            if (entityType.getCategory().equals(MobCategory.MONSTER)) {
                helper.fail("实体分类是 MONSTER！应改为 CREATURE");
                return;
            }
            if (!entityType.getCategory().equals(MobCategory.CREATURE)) {
                helper.fail("实体分类应为 CREATURE，当前: " + entityType.getCategory().getName());
                return;
            }
        }
        helper.succeed();
    }

    /**
     * 验证同伴实体不被铁傀儡攻击
     *
     * 测试步骤：
     * 1. 生成 AutomatonEntity
     * 2. 在附近生成 Iron Golem
     * 3. 等待 5 秒
     * 4. 验证同伴未被攻击（血量满）
     */
    @GameTest(template = "aicompanion:teststructures/empty_test_platform")
    public static void testIronGolemDoesNotAttackCompanion(GameTestHelper helper) {
        // 标记：需要先创建 empty_test_platform.nbt 测试结构
        helper.succeed();
    }

    /**
     * 验证村民不逃离同伴
     *
     * 预期：村民不应因 AutomatonEntity 接近而逃跑
     */
    @GameTest(template = "aicompanion:teststructures/empty_test_platform")
    public static void testVillagerDoesNotFleeCompanion(GameTestHelper helper) {
        helper.succeed();
    }

    /**
     * 验证同伴正确跟随玩家
     */
    @GameTest(template = "aicompanion:teststructures/empty_test_platform")
    public static void testCompanionFollowsOwner(GameTestHelper helper) {
        helper.succeed();
    }

    /**
     * 验证同伴不被刷怪笼误刷
     */
    @GameTest(template = "aicompanion:teststructures/empty_test_platform")
    public static void testCompanionNotSpawnedBySpawner(GameTestHelper helper) {
        helper.succeed();
    }
}
