package com.aiworkbench.companion.skill;

import java.util.*;

/**
 * 配方解析器 —— 查询 Minecraft 合成路径，从现有物品到目标物品。
 * <p>
 * 硬编码常见合成链（覆盖 ~90% 使用场景），复杂配方可通过查询原版配方系统扩展。
 * 每个配方链定义：目标物品 → 所需原料 → 对应的采集/合成技能。
 */
public class RecipeResolver {

    /** 配方链：目标物品名 → 合成步骤列表（从原材料→中间产物→最终产品，按拓扑序排列）*/
    private static final Map<String, List<RecipeStep>> RECIPE_CHAINS = new LinkedHashMap<>();

    static {
        // ===== 木质工具链 =====
        register("wooden_pickaxe", List.of(
            new RecipeStep("collectWood", "原木", 1, "gather", "采集木材"),
            new RecipeStep("craftPlanks", "木板", 4, "craftWoodenPlanks", "原木→木板"),
            new RecipeStep("craftSticks", "木棍", 2, "craftStick", "木板→木棍"),
            new RecipeStep("craftWoodenPickaxe", "木镐", 1, "craftWoodenPickaxe", "木板x3+木棍x2→木镐")
        ));

        register("wooden_axe", List.of(
            new RecipeStep("collectWood", "原木", 1, "gather", "采集木材"),
            new RecipeStep("craftPlanks", "木板", 3, "craftWoodenPlanks", "原木→木板"),
            new RecipeStep("craftSticks", "木棍", 2, "craftStick", "木板→木棍"),
            new RecipeStep("craftWoodenAxe", "木斧", 1, "craftWoodenAxe", "木板x3+木棍x2→木斧")
        ));

        // ===== 石质工具链 =====
        register("stone_pickaxe", List.of(
            new RecipeStep("mineStone", "圆石", 3, "gather", "采集圆石"),
            new RecipeStep("craftSticks", "木棍", 2, "craftStick", "木板→木棍"),
            new RecipeStep("craftStonePickaxe", "石镐", 1, "craftStonePickaxe", "圆石x3+木棍x2→石镐")
        ));

        register("furnace", List.of(
            new RecipeStep("mineStone", "圆石", 8, "gather", "采集圆石"),
            new RecipeStep("craftFurnace", "熔炉", 1, "craftFurnace", "圆石x8→熔炉")
        ));

        // ===== 铁器工具链 =====
        register("iron_pickaxe", List.of(
            new RecipeStep("mineIronOre", "铁矿石", 3, "gather", "采集铁矿石"),
            new RecipeStep("mineCoalOre", "煤炭", 1, "gather", "采集煤炭"),
            new RecipeStep("craftFurnace", "熔炉", 1, "craftFurnace", "圆石x8→熔炉"),
            new RecipeStep("smeltIronIngot", "铁锭", 3, "smeltIronIngot", "铁矿石+煤炭→铁锭"),
            new RecipeStep("craftSticks", "木棍", 2, "craftStick", "木板→木棍"),
            new RecipeStep("craftIronPickaxe", "铁镐", 1, "craftIronPickaxe", "铁锭x3+木棍x2→铁镐")
        ));

        // ===== 铁甲全套 =====
        register("iron_helmet", ironArmorStep("铁头盔", "craftIronHelmet"));
        register("iron_chestplate", ironArmorStep("铁胸甲", "craftIronChestplate"));
        register("iron_leggings", ironArmorStep("铁护腿", "craftIronLeggings"));
        register("iron_boots", ironArmorStep("铁靴子", "craftIronBoots"));

        // 全套铁甲（复合目标，24个铁锭）
        register("iron_armor_set", List.of(
            new RecipeStep("mineIronOre", "铁矿石", 24, "gather", "采集铁矿石x24"),
            new RecipeStep("mineCoalOre", "煤炭", 3, "gather", "采集煤炭x3"),
            new RecipeStep("craftFurnace", "熔炉", 1, "craftFurnace", "圆石x8→熔炉"),
            new RecipeStep("smeltIronIngot", "铁锭", 24, "smeltIronIngot", "铁矿石+煤炭→铁锭"),
            new RecipeStep("craftSticks", "木棍", 2, "craftStick", "木板→木棍"),
            new RecipeStep("craftIronHelmet", "铁头盔", 1, "craftIronHelmet", "铁锭x5→头盔"),
            new RecipeStep("craftIronChestplate", "铁胸甲", 1, "craftIronChestplate", "铁锭x8→胸甲"),
            new RecipeStep("craftIronLeggings", "铁护腿", 1, "craftIronLeggings", "铁锭x7→护腿"),
            new RecipeStep("craftIronBoots", "铁靴子", 1, "craftIronBoots", "铁锭x4→靴子")
        ));

        // ===== 钻石镐 =====
        register("diamond_pickaxe", List.of(
            new RecipeStep("mineDiamondOre", "钻石矿", 3, "gather", "采集钻石矿"),
            new RecipeStep("smeltIronIngot", "铁锭", 3, "smeltIronIngot", "需要铁镐采集钻石"),
            new RecipeStep("craftSticks", "木棍", 2, "craftStick", "木板→木棍"),
            new RecipeStep("craftDiamondPickaxe", "钻石镐", 1, "craftDiamondPickaxe", "钻石x3+木棍x2→钻石镐")
        ));

        // ===== 附魔台 =====
        register("enchanting_table", List.of(
            new RecipeStep("mineDiamondOre", "钻石矿", 2, "gather", "采集钻石矿x2"),
            new RecipeStep("mineObsidian", "黑曜石", 4, "gather", "采集黑曜石x4"),
            new RecipeStep("craftBook", "书", 1, "craftBook", "纸x3+皮革x1→书"),
            new RecipeStep("craftEnchantingTable", "附魔台", 1, "craftEnchantingTable", "黑曜石x4+钻石x2+书x1→附魔台")
        ));
    }

    private static List<RecipeStep> ironArmorStep(String name, String skill) {
        return List.of(
            new RecipeStep("mineIronOre", "铁矿石", 8, "gather", "采集铁矿石x8"),
            new RecipeStep("mineCoalOre", "煤炭", 1, "gather", "采集煤炭"),
            new RecipeStep("craftFurnace", "熔炉", 1, "craftFurnace", "圆石x8→熔炉"),
            new RecipeStep("smeltIronIngot", "铁锭", 8, "smeltIronIngot", "铁矿石+煤炭→铁锭"),
            new RecipeStep(skill, name, 1, skill, "铁锭→" + name)
        );
    }

    private static void register(String key, List<RecipeStep> steps) {
        RECIPE_CHAINS.put(key, steps);
    }

    // ==================== 公开方法 ====================

    /**
     * 解析目标物品的完整合成链。
     * @param goal 目标物品名（支持中文和英文），如 "铁镐"、"iron_pickaxe"、"铁甲全套"
     * @return 有序的合成步骤列表，如果无法解析返回 null
     */
    public static List<RecipeStep> resolve(String goal) {
        if (goal == null || goal.isEmpty()) return null;
        String key = normalize(goal);
        // 精确匹配
        List<RecipeStep> steps = RECIPE_CHAINS.get(key);
        if (steps != null) return new ArrayList<>(steps);
        // 模糊匹配
        for (Map.Entry<String, List<RecipeStep>> e : RECIPE_CHAINS.entrySet()) {
            if (e.getKey().contains(key) || key.contains(e.getKey())) {
                return new ArrayList<>(e.getValue());
            }
        }
        return null;
    }

    /** 检查是否有对应配方 */
    public static boolean hasRecipe(String goal) {
        return resolve(goal) != null;
    }

    /** 列出所有已知配方名 */
    public static Set<String> getKnownRecipes() {
        return Collections.unmodifiableSet(RECIPE_CHAINS.keySet());
    }

    /** 将中文名映射到配方键 */
    private static String normalize(String input) {
        return input.toLowerCase().trim()
            .replace("铁镐", "iron_pickaxe")
            .replace("铁斧", "iron_axe")
            .replace("石镐", "stone_pickaxe")
            .replace("木镐", "wooden_pickaxe")
            .replace("木斧", "wooden_axe")
            .replace("铁头盔", "iron_helmet")
            .replace("铁胸甲", "iron_chestplate")
            .replace("铁护腿", "iron_leggings")
            .replace("铁靴子", "iron_boots")
            .replace("铁甲全套", "iron_armor_set")
            .replace("铁甲", "iron_armor_set")
            .replace("钻石镐", "diamond_pickaxe")
            .replace("附魔台", "enchanting_table")
            .replace("熔炉", "furnace");
    }

    // ==================== 数据类型 ====================

    /**
     * 配方中的一个合成步骤。
     */
    public static class RecipeStep {
        /** 步骤ID（唯一标识） */
        public final String id;
        /** 产物名称（中文） */
        public final String product;
        /** 产物数量 */
        public final int quantity;
        /** 对应的技能名 */
        public final String skillName;
        /** 简短说明 */
        public final String description;

        RecipeStep(String id, String product, int quantity, String skillName, String description) {
            this.id = id;
            this.product = product;
            this.quantity = quantity;
            this.skillName = skillName;
            this.description = description;
        }

        @Override
        public String toString() {
            return String.format("%s (%s)", product, description);
        }
    }
}
