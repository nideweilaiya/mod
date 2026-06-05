package com.aiworkbench.companion.building;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.*;

/**
 * 蓝图注册表 —— 预设建筑蓝图，按名称/标签检索。
 * <p>
 * 当前包含 7 种预设蓝图：地板/小屋/柱子/瞭望塔/桥/围墙/房间。
 * 后续可扩展从 JSON 文件加载。
 */
public class BlueprintLibrary {

    private static final Map<String, BuildingBlueprint> BLUEPRINTS = new LinkedHashMap<>();

    static {
        register(floor3x3());
        register(hut3x3());
        register(pillar5());
        register(watchtower5x5());
        register(bridge3xN());
        register(wall5x2());
        register(room5x5());
        register(farmPlot());
    }

    private static void register(BuildingBlueprint bp) {
        BLUEPRINTS.put(bp.name, bp);
    }

    // ==================== 预设蓝图定义 ====================

    private static BuildingBlueprint floor3x3() {
        BuildingBlueprint bp = new BuildingBlueprint("floor_3x3", 3, 1, 3, List.of("floor", "simple"));
        Block b = Blocks.COBBLESTONE;
        for (int x = 0; x < 3; x++)
            for (int z = 0; z < 3; z++)
                bp.add(x, 0, z, b);
        return bp;
    }

    private static BuildingBlueprint hut3x3() {
        BuildingBlueprint bp = new BuildingBlueprint("hut_3x3", 3, 3, 3, List.of("shelter", "simple", "survival"));
        Block w = Blocks.COBBLESTONE;
        // 地板
        for (int x = 0; x < 3; x++)
            for (int z = 0; z < 3; z++)
                bp.add(x, 0, z, w);
        // 角柱 y=1,2
        for (int y = 1; y <= 2; y++)
            for (int x : new int[]{0, 2})
                for (int z : new int[]{0, 2})
                    bp.add(x, y, z, w);
        // 墙壁 y=1
        bp.add(1, 1, 0, w); bp.add(0, 1, 1, w); bp.add(1, 1, 1, w);
        bp.add(2, 1, 1, w); bp.add(1, 1, 2, w);
        // 墙壁 y=2
        bp.add(1, 2, 0, w); bp.add(0, 2, 1, w); bp.add(1, 2, 1, w);
        bp.add(2, 2, 1, w); bp.add(1, 2, 2, w);
        return bp;
    }

    private static BuildingBlueprint pillar5() {
        BuildingBlueprint bp = new BuildingBlueprint("pillar_5", 1, 5, 1, List.of("pillar", "simple"));
        for (int y = 0; y < 5; y++) bp.add(0, y, 0, Blocks.COBBLESTONE);
        return bp;
    }

    private static BuildingBlueprint watchtower5x5() {
        BuildingBlueprint bp = new BuildingBlueprint("watchtower_5x5", 5, 8, 5,
            List.of("tower", "defense", "advanced"));
        Block p = Blocks.OAK_PLANKS;
        Block f = Blocks.OAK_FENCE;
        // 地板 (y=0)
        for (int x = 0; x < 5; x++)
            for (int z = 0; z < 5; z++)
                bp.add(x, 0, z, p);
        // 角柱 y=1..6
        for (int y = 1; y <= 6; y++)
            for (int x : new int[]{0, 4})
                for (int z : new int[]{0, 4})
                    bp.add(x, y, z, p);
        // 中层地板 (y=3) 部分
        for (int x = 0; x < 5; x++)
            for (int z = 0; z < 5; z++)
                bp.add(x, 3, z, p);
        // 顶层地板 (y=6)
        for (int x = 0; x < 5; x++)
            for (int z = 0; z < 5; z++)
                bp.add(x, 6, z, p);
        // 顶层围栏 (y=7)
        for (int x = 0; x < 5; x++) {
            bp.add(x, 7, 0, f);
            bp.add(x, 7, 4, f);
        }
        for (int z = 1; z < 4; z++) {
            bp.add(0, 7, z, f);
            bp.add(4, 7, z, f);
        }
        return bp;
    }

    private static BuildingBlueprint bridge3xN() {
        BuildingBlueprint bp = new BuildingBlueprint("bridge_3xN", 3, 1, 5,
            List.of("bridge", "transport"));
        for (int x = 0; x < 3; x++)
            for (int z = 0; z < 5; z++)
                bp.add(x, 0, z, Blocks.OAK_PLANKS);
        return bp;
    }

    private static BuildingBlueprint wall5x2() {
        BuildingBlueprint bp = new BuildingBlueprint("wall_5x2", 5, 2, 1,
            List.of("wall", "defense", "simple"));
        for (int x = 0; x < 5; x++)
            for (int y = 0; y < 2; y++)
                bp.add(x, y, 0, Blocks.COBBLESTONE);
        return bp;
    }

    private static BuildingBlueprint room5x5() {
        BuildingBlueprint bp = new BuildingBlueprint("room_5x5", 5, 4, 5,
            List.of("room", "building", "advanced"));
        Block w = Blocks.OAK_PLANKS;
        // 地板
        for (int x = 0; x < 5; x++)
            for (int z = 0; z < 5; z++)
                bp.add(x, 0, z, w);
        // 墙壁 y=1,2 (y=3 由屋顶覆盖，不重复放置)
        for (int y = 1; y <= 2; y++) {
            for (int x = 0; x < 5; x++) {
                // 北墙 — x=2 留空作为门洞
                if (x != 2) bp.add(x, y, 0, w);
                // 南墙 — 不留门洞
                bp.add(x, y, 4, w);
            }
            for (int z = 1; z < 4; z++) {
                bp.add(0, y, z, w);  // 西墙
                bp.add(4, y, z, w);  // 东墙
            }
        }
        // 屋顶 (y=3, 同时覆盖墙壁顶部)
        for (int x = 0; x < 5; x++)
            for (int z = 0; z < 5; z++)
                bp.add(x, 3, z, w);
        return bp;
    }

    private static BuildingBlueprint farmPlot() {
        BuildingBlueprint bp = new BuildingBlueprint("farm_plot", 5, 1, 5,
            List.of("farm", "simple"));
        // 农田外围栅栏
        Block f = Blocks.OAK_FENCE;
        for (int x = 0; x < 5; x++) { bp.add(x, 0, 0, f); bp.add(x, 0, 4, f); }
        for (int z = 1; z < 4; z++) { bp.add(0, 0, z, f); bp.add(4, 0, z, f); }
        return bp;
    }

    // ==================== 查询 ====================

    public static BuildingBlueprint get(String name) {
        return BLUEPRINTS.get(name);
    }

    public static List<BuildingBlueprint> getByTag(String tag) {
        List<BuildingBlueprint> result = new ArrayList<>();
        for (BuildingBlueprint bp : BLUEPRINTS.values()) {
            if (bp.tags.contains(tag)) result.add(bp);
        }
        return result;
    }

    public static Set<String> getNames() { return BLUEPRINTS.keySet(); }

    public static List<String> listAll() {
        List<String> list = new ArrayList<>();
        for (BuildingBlueprint bp : BLUEPRINTS.values()) {
            list.add(String.format("  %s — %dx%dx%d (%d块, %s)",
                bp.name, bp.width, bp.height, bp.depth, bp.totalBlocks(), String.join(",", bp.tags)));
        }
        return list;
    }
}
