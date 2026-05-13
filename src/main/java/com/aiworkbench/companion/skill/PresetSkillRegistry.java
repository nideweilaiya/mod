package com.aiworkbench.companion.skill;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.building.BlueprintLibrary;
import com.aiworkbench.companion.building.BuildingBlueprint;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.atomic.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 预制技能注册表 —— 注册开箱即用的验证技能。
 * <p>
 * Phase 1 只注册基础测试技能，用于验证 SkillEngine 框架。
 * Phase 2 将添加实用技能（收集木材、挖矿等）。
 */
public final class PresetSkillRegistry {

    private PresetSkillRegistry() {}

    /**
     * 注册所有预制技能。
     */
    public static void registerAll(SkillLibrary library) {
        // Phase 1: 测试技能
        registerMoveForward(library);
        registerLookAtOwner(library);

        // Phase 2: 实用技能
        registerCollectWood(library);
        registerMineStone(library);
        registerMineCoalOre(library);
        registerMineIronOre(library);
        registerMineDiamondOre(library);
        registerMineGoldOre(library);
        registerMineEmeraldOre(library);
        registerMineLapisOre(library);
        registerMineRedstoneOre(library);
        registerMineCopperOre(library);
        registerFightZombie(library);
        registerCollectDrops(library);
        registerCraftStick(library);
        registerCraftWoodenPickaxe(library);
        registerCraftStonePickaxe(library);
        registerCraftIronPickaxe(library);
        registerCraftFurnace(library);
        registerBuildShelter(library);
        registerSmeltIronIngot(library);

        AICompanionMod.LOGGER.info("[PresetSkills] Registered {} preset skills", library.getAllPresets().size());
    }

    /**
     * 测试技能：向前移动 5 格。
     * 目标位置在技能启动时根据实体朝向动态计算。
     */
    private static void registerMoveForward(SkillLibrary library) {
        AtomicAction moveForward = new AtomicAction() {
            private BlockPos target;
            private int checkTicks;

            @Override
            public boolean canStart(AutomatonEntity entity) {
                // 计算实体前方 5 格的目标位置
                var look = entity.getLookAngle();
                var pos = entity.blockPosition();
                target = pos.offset(
                    (int) Math.round(look.x * 5),
                    0,
                    (int) Math.round(look.z * 5)
                );
                checkTicks = 0;
                return true;
            }

            @Override
            public boolean tick(AutomatonEntity entity) {
                if (target == null) return true;
                checkTicks++;

                var nav = entity.getNavigation();
                nav.moveTo(target.getX(), target.getY(), target.getZ(), 1.0);

                // 每 10 tick 检查是否到达
                if (checkTicks % 10 == 0) {
                    double distSq = entity.position().distanceToSqr(
                        target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
                    if (distSq <= 9.0) {
                        nav.stop();
                        return true;
                    }
                }

                // 超时保护（10秒后强制完成）
                if (checkTicks > 200) {
                    nav.stop();
                    return true;
                }

                return false;
            }

            @Override
            public void stop(AutomatonEntity entity) {
                entity.getNavigation().stop();
            }

            @Override
            public void reset() {
                target = null;
                checkTicks = 0;
            }

            @Override
            public String getDescription() {
                return "向前移动";
            }
        };

        SkillAction action = new SkillAction(List.of(moveForward), false);
        Skill skill = new Skill(
            "moveForward",
            "向前移动 5 格（测试技能引擎）",
            List.of(),
            action,
            SkillCategory.MOVEMENT,
            false
        );
        library.registerPreset(skill);
    }

    /**
     * 测试技能：看向主人，持续 2 秒。
     */
    private static void registerLookAtOwner(SkillLibrary library) {
        SkillAction action = new SkillAction(List.of(
            LookAtAction.lookAtOwner(40)
        ), false);

        Skill skill = new Skill(
            "lookAtOwner",
            "看向主人 2 秒（测试技能引擎）",
            List.of(),
            action,
            SkillCategory.MOVEMENT,
            false
        );
        library.registerPreset(skill);
    }

    // ==================== Phase 2 实用技能 ====================

    /**
     * 收集木材：装备斧头 → 移动到原木 → 砍伐周围所有原木。
     */
    private static void registerCollectWood(SkillLibrary library) {
        SkillAction action = new SkillAction(List.of(
            EquipItemAction.bestToolFor("log"),
            new MoveToBlockAction(BlockMatcher.contains("log")),
            new BreakBlockAction(BlockMatcher.contains("log"))
        ), false);

        Skill skill = new Skill(
            "collectWood", "收集木材：自动寻找并砍伐周围的树木",
            List.of(), action, SkillCategory.INTERACTION, false
        );
        library.registerPreset(skill);
    }

    /**
     * 挖掘石头：装备镐 → 移动到石头 → 挖掘周围所有石头。
     */
    private static void registerMineStone(SkillLibrary library) {
        SkillAction action = new SkillAction(List.of(
            EquipItemAction.bestToolFor("stone"),
            new MoveToBlockAction(BlockMatcher.exact("stone")),
            new BreakBlockAction(BlockMatcher.exact("stone"))
        ), false);

        Skill skill = new Skill(
            "mineStone", "挖掘石头：自动寻找并挖掘石头",
            List.of(), action, SkillCategory.INTERACTION, false
        );
        library.registerPreset(skill);
    }

    /**
     * 挖掘煤矿：移动到煤矿 → 挖掘周围所有煤矿。
     */
    private static void registerMineCoalOre(SkillLibrary library) {
        SkillAction action = new SkillAction(List.of(
            EquipItemAction.bestToolFor("coal"),
            new MoveToBlockAction(BlockMatcher.contains("coal_ore")),
            new BreakBlockAction(BlockMatcher.contains("coal_ore"))
        ), false);

        Skill skill = new Skill(
            "mineCoalOre", "挖掘煤矿：自动寻找并挖掘煤矿",
            List.of(), action, SkillCategory.INTERACTION, false
        );
        library.registerPreset(skill);
    }

    /**
     * 挖掘铁矿：移动到铁矿 → 挖掘周围所有铁矿。
     */
    private static void registerMineIronOre(SkillLibrary library) {
        SkillAction action = new SkillAction(List.of(
            EquipItemAction.bestToolFor("iron"),
            new MoveToBlockAction(BlockMatcher.contains("iron_ore")),
            new BreakBlockAction(BlockMatcher.contains("iron_ore"))
        ), false);

        Skill skill = new Skill(
            "mineIronOre", "挖掘铁矿：自动寻找并挖掘铁矿",
            List.of(), action, SkillCategory.INTERACTION, false
        );
        library.registerPreset(skill);
    }

    /**
     * 战斗僵尸：攻击附近的僵尸，直到全部消灭或超出时间。
     */
    private static void registerFightZombie(SkillLibrary library) {
        SkillAction action = new SkillAction(List.of(
            AttackEntityAction.ofType(net.minecraft.world.entity.monster.Zombie.class)
        ), false);

        Skill skill = new Skill(
            "fightZombie", "战斗僵尸：自动攻击附近的僵尸",
            List.of(), action, SkillCategory.COMBAT, false
        );
        library.registerPreset(skill);
    }

    /**
     * 收集掉落物：拾取周围所有掉落物品。
     */
    private static void registerCollectDrops(SkillLibrary library) {
        SkillAction action = new SkillAction(List.of(
            CollectItemsAction.all()
        ), false);

        Skill skill = new Skill(
            "collectDrops", "收集掉落物：拾取周围所有掉落物品",
            List.of(), action, SkillCategory.INTERACTION, false
        );
        library.registerPreset(skill);
    }

    /**
     * 合成木棍：消耗 2 个木板 → 产出 4 个木棍。
     */
    private static void registerCraftStick(SkillLibrary library) {
        SkillAction action = new SkillAction(List.of(
            new CraftItemAction(Items.STICK, 1)
        ), false);

        Skill skill = new Skill(
            "craftStick", "合成木棍：消耗木板合成木棍",
            List.of(), action, SkillCategory.CRAFTING, false
        );
        library.registerPreset(skill);
    }

    /**
     * 合成木镐：消耗木板 + 木棍 → 产出木镐。
     */
    private static void registerCraftWoodenPickaxe(SkillLibrary library) {
        SkillAction action = new SkillAction(List.of(
            new CraftItemAction(Items.WOODEN_PICKAXE, 1)
        ), false);

        Skill skill = new Skill(
            "craftWoodenPickaxe", "合成木镐：消耗木板和木棍合成木镐",
            List.of(), action, SkillCategory.CRAFTING, false
        );
        library.registerPreset(skill);
    }

    /**
     * 合成熔炉：消耗圆石 → 产出熔炉。
     */
    private static void registerCraftFurnace(SkillLibrary library) {
        SkillAction action = new SkillAction(List.of(
            new CraftItemAction(Items.FURNACE, 1)
        ), false);

        Skill skill = new Skill(
            "craftFurnace", "合成熔炉：消耗圆石合成熔炉",
            List.of(), action, SkillCategory.CRAFTING, false
        );
        library.registerPreset(skill);
    }

    /**
     * 建造小屋：在当前位置建一个 3×3 简易小屋（用圆石）。
     */
    private static void registerBuildShelter(SkillLibrary library) {
        BuildingBlueprint hut = BlueprintLibrary.get("hut_3x3");
        SkillAction action = new SkillAction(List.of(
            new BuildStructureAction(hut)
        ), false);

        Skill skill = new Skill(
            "buildShelter", "建造小屋：用圆石建造一个 3×3 简易庇护所",
            List.of(), action, SkillCategory.INTERACTION, false
        );
        library.registerPreset(skill);
    }

    /**
     * 挖掘钻石矿：移动到钻石矿 → 挖掘周围所有钻石矿。
     */
    private static void registerMineDiamondOre(SkillLibrary library) {
        SkillAction action = new SkillAction(List.of(
            EquipItemAction.bestToolFor("iron"),
            new MoveToBlockAction(BlockMatcher.contains("diamond_ore")),
            new BreakBlockAction(BlockMatcher.contains("diamond_ore"))
        ), false);

        Skill skill = new Skill(
            "mineDiamondOre", "挖掘钻石矿：自动寻找并挖掘钻石矿",
            List.of(), action, SkillCategory.INTERACTION, false
        );
        library.registerPreset(skill);
    }

    /**
     * 挖掘金矿：移动到金矿 → 挖掘周围所有金矿。
     */
    private static void registerMineGoldOre(SkillLibrary library) {
        SkillAction action = new SkillAction(List.of(
            EquipItemAction.bestToolFor("iron"),
            new MoveToBlockAction(BlockMatcher.contains("gold_ore")),
            new BreakBlockAction(BlockMatcher.contains("gold_ore"))
        ), false);

        Skill skill = new Skill(
            "mineGoldOre", "挖掘金矿：自动寻找并挖掘金矿",
            List.of(), action, SkillCategory.INTERACTION, false
        );
        library.registerPreset(skill);
    }

    /**
     * 挖掘绿宝石矿：移动到绿宝石矿 → 挖掘周围所有绿宝石矿。
     */
    private static void registerMineEmeraldOre(SkillLibrary library) {
        SkillAction action = new SkillAction(List.of(
            EquipItemAction.bestToolFor("iron"),
            new MoveToBlockAction(BlockMatcher.contains("emerald_ore")),
            new BreakBlockAction(BlockMatcher.contains("emerald_ore"))
        ), false);

        Skill skill = new Skill(
            "mineEmeraldOre", "挖掘绿宝石矿：自动寻找并挖掘绿宝石矿",
            List.of(), action, SkillCategory.INTERACTION, false
        );
        library.registerPreset(skill);
    }

    /**
     * 挖掘青金石矿：移动到青金石矿 → 挖掘周围所有青金石矿。
     */
    private static void registerMineLapisOre(SkillLibrary library) {
        SkillAction action = new SkillAction(List.of(
            EquipItemAction.bestToolFor("stone"),
            new MoveToBlockAction(BlockMatcher.contains("lapis_ore")),
            new BreakBlockAction(BlockMatcher.contains("lapis_ore"))
        ), false);

        Skill skill = new Skill(
            "mineLapisOre", "挖掘青金石矿：自动寻找并挖掘青金石矿",
            List.of(), action, SkillCategory.INTERACTION, false
        );
        library.registerPreset(skill);
    }

    /**
     * 挖掘红石矿：移动到红石矿 → 挖掘周围所有红石矿。
     */
    private static void registerMineRedstoneOre(SkillLibrary library) {
        SkillAction action = new SkillAction(List.of(
            EquipItemAction.bestToolFor("iron"),
            new MoveToBlockAction(BlockMatcher.contains("redstone_ore")),
            new BreakBlockAction(BlockMatcher.contains("redstone_ore"))
        ), false);

        Skill skill = new Skill(
            "mineRedstoneOre", "挖掘红石矿：自动寻找并挖掘红石矿",
            List.of(), action, SkillCategory.INTERACTION, false
        );
        library.registerPreset(skill);
    }

    /**
     * 挖掘铜矿：移动到铜矿 → 挖掘周围所有铜矿。
     */
    private static void registerMineCopperOre(SkillLibrary library) {
        SkillAction action = new SkillAction(List.of(
            EquipItemAction.bestToolFor("stone"),
            new MoveToBlockAction(BlockMatcher.contains("copper_ore")),
            new BreakBlockAction(BlockMatcher.contains("copper_ore"))
        ), false);

        Skill skill = new Skill(
            "mineCopperOre", "挖掘铜矿：自动寻找并挖掘铜矿",
            List.of(), action, SkillCategory.INTERACTION, false
        );
        library.registerPreset(skill);
    }

    /**
     * 合成石镐：消耗圆石 + 木棍 → 产出石镐。
     */
    private static void registerCraftStonePickaxe(SkillLibrary library) {
        SkillAction action = new SkillAction(List.of(
            new CraftItemAction(Items.STONE_PICKAXE, 1)
        ), false);

        Skill skill = new Skill(
            "craftStonePickaxe", "合成石镐：消耗圆石和木棍合成石镐",
            List.of(), action, SkillCategory.CRAFTING, false
        );
        library.registerPreset(skill);
    }

    /**
     * 合成铁镐：消耗铁锭 + 木棍 → 产出铁镐。
     */
    private static void registerCraftIronPickaxe(SkillLibrary library) {
        SkillAction action = new SkillAction(List.of(
            new CraftItemAction(Items.IRON_PICKAXE, 1)
        ), false);

        Skill skill = new Skill(
            "craftIronPickaxe", "合成铁镐：消耗铁锭和木棍合成铁镐",
            List.of(), action, SkillCategory.CRAFTING, false
        );
        library.registerPreset(skill);
    }

    /**
     * 冶炼铁锭：自动搜索熔炉 → 放入铁矿石 + 煤炭 → 等待 → 取出铁锭。
     */
    private static void registerSmeltIronIngot(SkillLibrary library) {
        SkillAction action = new SkillAction(List.of(
            SmeltItemsAction.ironIngot()
        ), false);

        Skill skill = new Skill(
            "smeltIronIngot", "冶炼铁锭：自动使用熔炉将铁矿石冶炼成铁锭",
            List.of(), action, SkillCategory.INTERACTION, false
        );
        library.registerPreset(skill);
    }
}
