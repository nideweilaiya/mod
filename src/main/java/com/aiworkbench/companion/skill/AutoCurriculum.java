package com.aiworkbench.companion.skill;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.ai.PerceptionEngine;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * 自动课程生成器 —— 根据环境感知数据主动提议下一步任务。
 * <p>
 * Voyager 架构的关键组件：不依赖玩家指令，同伴自主评估环境和自身状态，
 * 主动提议最有价值的下一步行动（"课程"）。
 * <p>
 * 初始版本使用规则引擎实现（不依赖 LLM），基于以下优先级决策：
 * <ol>
 *   <li>生存优先：低血量 → 躲避/回血，夜晚 → 建造庇护所</li>
 *   <li>资源采集：检测到矿石 → 提议挖矿，检测到树木 → 提议砍树</li>
 *   <li>背包管理：背包快满 → 提议回城存储</li>
 *   <li>自我提升：可以合成更好的工具 → 提议合成</li>
 * </ol>
 * <p>
 * 使用方式：
 * <pre>
 *   CurriculumProposal proposal = AutoCurriculum.proposeNextTask(entity);
 *   if (proposal != null) { entity.showDialogue(proposal.taskDescription, 60); }
 * </pre>
 * thread-safety: 仅在服务器主线程调用。
 */
public class AutoCurriculum {

    /** 背包满的阈值（超过此比例视为满载） */
    private static final double INVENTORY_FULL_THRESHOLD = 0.85;
    /** 低血量阈值 */
    private static final float LOW_HEALTH_THRESHOLD = 20.0f;
    /** 危机血量阈值 */
    private static final float CRITICAL_HEALTH_THRESHOLD = 10.0f;
    /** 资源提议冷却时间（tick）—— 防止重复提议 */
    private static final int PROPOSAL_COOLDOWN_TICKS = 600; // 30 秒

    /** 记录每个同伴上次提议的 tick 数，用于冷却控制 */
    private static final Map<UUID, Long> lastProposalTick = new HashMap<>();

    // ================ 公开方法 ================

    /**
     * 根据同伴当前状态和感知环境，提议下一步任务。
     * <p>
     * 决策流程（按优先级从高到低）：
     * <ol>
     *   <li>生存危机检测（血量 < 阈值、岩浆、敌对生物）</li>
     *   <li>时间敏感任务（夜晚 → 建庇护所）</li>
     *   <li>背包管理（快满 → 提议存储）</li>
     *   <li>资源采集（矿石/树木 → 挖矿/砍树）</li>
     *   <li>工具升级（可合成更高级工具）</li>
     * </ol>
     *
     * @param entity 同伴实体
     * @return 提议的下一步任务，如果无需提议则返回 null
     */
    public static CurriculumProposal proposeNextTask(AutomatonEntity entity) {
        if (entity == null || !entity.isAlive() || entity.level().isClientSide) {
            return null;
        }

        // 冷却检查：避免过于频繁的提议
        UUID companionId = entity.getUUID();
        long currentTick = entity.level().getGameTime();
        Long lastTick = lastProposalTick.get(companionId);
        if (lastTick != null && currentTick - lastTick < PROPOSAL_COOLDOWN_TICKS) {
            return null;
        }

        // 如果同伴已有技能在执行，不打断
        if (entity.isSkillActive()) {
            return null;
        }

        // 收集感知数据
        PerceptionEngine.PerceptionData perception = PerceptionEngine.gatherPerception(entity);
        if (perception == null) {
            return null;
        }

        CurriculumProposal proposal = null;

        // ===== 第1层：生存危机（最高优先级） =====
        proposal = evaluateSurvival(entity, perception);
        if (proposal != null) {
            lastProposalTick.put(companionId, currentTick);
            return proposal;
        }

        // ===== 第2层：时间敏感任务 =====
        proposal = evaluateTimeSensitive(entity);
        if (proposal != null) {
            lastProposalTick.put(companionId, currentTick);
            return proposal;
        }

        // ===== 第3层：背包管理 =====
        proposal = evaluateInventory(entity);
        if (proposal != null) {
            lastProposalTick.put(companionId, currentTick);
            return proposal;
        }

        // ===== 第4层：资源采集 =====
        proposal = evaluateResources(entity, perception);
        if (proposal != null) {
            lastProposalTick.put(companionId, currentTick);
            return proposal;
        }

        // ===== 第5层：工具升级 =====
        proposal = evaluateToolUpgrade(entity);
        if (proposal != null) {
            lastProposalTick.put(companionId, currentTick);
            return proposal;
        }

        return null; // 无需提议
    }

    /**
     * 获取向主人展示的提议文本。
     */
    public static String formatProposal(CurriculumProposal proposal) {
        if (proposal == null) return null;
        String priorityLabel = switch (proposal.priority) {
            case HIGH -> "!! ";
            case MEDIUM -> "! ";
            case LOW -> "";
        };
        return priorityLabel + proposal.taskDescription + " (" + proposal.reason + ")";
    }

    // ================ 决策层实现 ================

    /**
     * 第1层：生存危机评估。
     * 检测血量过低、岩浆威胁、敌对生物过近。
     */
    private static CurriculumProposal evaluateSurvival(AutomatonEntity entity,
                                                        PerceptionEngine.PerceptionData perception) {
        // 危机血量：立即建议躲避
        if (perception.health <= CRITICAL_HEALTH_THRESHOLD) {
            return new CurriculumProposal(
                    "血量危急！建议立即撤离并回血",
                    Priority.HIGH,
                    "血量低于" + (int) CRITICAL_HEALTH_THRESHOLD + "点",
                    "retreat"
            );
        }

        // 低血量 + 有敌对生物 = 高风险
        if (perception.health <= LOW_HEALTH_THRESHOLD && perception.dangerHostile) {
            return new CurriculumProposal(
                    "血量偏低且有敌人，建议先回血再战斗",
                    Priority.HIGH,
                    "低血量(" + (int) perception.health + "HP) + 敌对生物",
                    "retreat_heal"
            );
        }

        // 岩浆威胁
        if (perception.dangerLava) {
            return new CurriculumProposal(
                    "附近有岩浆！建议远离危险区域",
                    Priority.HIGH,
                    "检测到岩浆",
                    "avoid_lava"
            );
        }

        return null;
    }

    /**
     * 第2层：时间敏感任务（夜晚 → 建庇护所）。
     */
    private static CurriculumProposal evaluateTimeSensitive(AutomatonEntity entity) {
        // 检查是否夜晚
        if (!entity.level().isDay() && entity.level().canSeeSky(entity.blockPosition())) {
            // 已经处于守护模式 → 不需要额外提议
            if (entity.isGuardModeEnabled()) return null;

            return new CurriculumProposal(
                    "天黑了，建议建造临时庇护所或开启守护模式",
                    Priority.HIGH,
                    "夜晚露天，可能刷怪",
                    "night_shelter"
            );
        }

        return null;
    }

    /**
     * 第3层：背包管理。
     */
    private static CurriculumProposal evaluateInventory(AutomatonEntity entity) {
        int used = 0;
        int total = entity.getInventorySize();
        for (int i = 0; i < total; i++) {
            if (!entity.getItem(i).isEmpty()) used++;
        }

        double usageRatio = (double) used / total;

        if (usageRatio >= INVENTORY_FULL_THRESHOLD) {
            return new CurriculumProposal(
                    "背包快满了(" + used + "/" + total + ")，建议回城整理",
                    Priority.MEDIUM,
                    "背包使用率" + (int)(usageRatio * 100) + "%",
                    "inventory_full"
            );
        }

        return null;
    }

    /**
     * 第4层：资源采集 —— 根据感知到的资源提议采集任务。
     * <p>
     * 参考 AutomatonEntity.evaluateSituation() 中的主动提醒逻辑：
     * 检测到铁矿 → 提议挖铁矿，检测到树 → 提议砍树等。
     */
    private static CurriculumProposal evaluateResources(AutomatonEntity entity,
                                                         PerceptionEngine.PerceptionData perception) {
        if (perception.resources.isEmpty()) {
            return null;
        }

        // 资源分类并计数
        int oreCount = 0;
        int logCount = 0;
        String bestOre = null;
        int bestOrePriority = 0;

        for (String resource : perception.resources) {
            String name = resource.toLowerCase();

            if (isOre(name)) {
                oreCount++;
                int priority = getOrePriority(name);
                if (priority > bestOrePriority) {
                    bestOrePriority = priority;
                    bestOre = extractOreName(name);
                }
            } else if (isLog(name)) {
                logCount++;
            }
        }

        // 发现资源 → 统一提议采集模式（"gather" 触发智能采集Goal）
        if (bestOre != null && bestOrePriority >= 2) {
            String taskDesc = mapOreToTaskDescription(bestOre);
            return new CurriculumProposal(
                    taskDesc + "（含" + oreCount + "种矿石" + (logCount > 0 ? " + " + logCount + "棵树" : "") + "）",
                    bestOrePriority >= 5 ? Priority.HIGH : Priority.MEDIUM,
                    "发现" + bestOre + (logCount > 0 ? "等资源" : ""),
                    "gather"
            );
        }

        // 仅有树木
        if (logCount > 0) {
            return new CurriculumProposal(
                    "发现树木，要采集木材吗？",
                    Priority.MEDIUM,
                    "附近有" + logCount + "棵树木",
                    "gather"
            );
        }

        return null;
    }

    /**
     * 第5层：工具升级建议。
     * 检查背包中是否有材料可以合成更高级工具。
     */
    private static CurriculumProposal evaluateToolUpgrade(AutomatonEntity entity) {
        boolean hasSticks = false;
        boolean hasPlanks = false;
        int cobblestoneCount = 0;
        int ironCount = 0;
        boolean hasWoodenPickaxe = false;
        boolean hasStonePickaxe = false;

        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack stack = entity.getItem(i);
            if (stack.isEmpty()) continue;

            String itemId = stack.getItem().builtInRegistryHolder().key().location().toString();

            if (itemId.equals("minecraft:stick")) hasSticks = true;
            if (itemId.contains("_planks")) hasPlanks = true;
            if (itemId.equals("minecraft:cobblestone")) cobblestoneCount += stack.getCount();
            if (itemId.equals("minecraft:iron_ingot")) ironCount += stack.getCount();
            if (itemId.equals("minecraft:wooden_pickaxe")) hasWoodenPickaxe = true;
            if (itemId.equals("minecraft:stone_pickaxe")) hasStonePickaxe = true;
        }

        // 检查手持工具
        ItemStack mainHand = entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        String mainHandId = mainHand.isEmpty() ? "" :
                mainHand.getItem().builtInRegistryHolder().key().location().toString();

        // 如果有木板+木棍但没有木镐 → 提议合成木镐
        if (hasPlanks && hasSticks && !hasWoodenPickaxe && !hasStonePickaxe
                && !mainHandId.contains("pickaxe")) {
            return new CurriculumProposal(
                    "可以合成木镐了，要合成吗？",
                    Priority.LOW,
                    "木板+木棍 → 木镐",
                    "craftWoodenPickaxe"
            );
        }

        // 如果有足够的圆石+木棍 → 提议合成石镐
        if (cobblestoneCount >= 3 && hasSticks && !hasStonePickaxe
                && (hasWoodenPickaxe || mainHandId.contains("wooden_pickaxe"))) {
            return new CurriculumProposal(
                    "可以合成石镐了，要合成吗？",
                    Priority.LOW,
                    "圆石+木棍 → 石镐",
                    "craftStonePickaxe"
            );
        }

        // 如果有铁锭 → 提议合成铁镐
        if (ironCount >= 3 && hasSticks) {
            return new CurriculumProposal(
                    "有铁锭了！要合成铁镐吗？",
                    Priority.MEDIUM,
                    "铁锭+木棍 → 铁镐",
                    "craftIronPickaxe"
            );
        }

        return null;
    }

    // ================ 资源分类工具方法 ================

    /**
     * 检查资源名是否为矿石。
     */
    private static boolean isOre(String resourceName) {
        return resourceName.contains("ore") || resourceName.contains("ancient_debris");
    }

    /**
     * 检查资源名是否为原木。
     */
    private static boolean isLog(String resourceName) {
        return resourceName.contains("_log") || resourceName.contains("_stem");
    }

    /**
     * 获取矿石的采集优先级（数值越高越优先）。
     * 参考 AutomatonEntity.grantMiningXp() 中的价值判断。
     */
    private static int getOrePriority(String resourceName) {
        if (resourceName.contains("ancient_debris") || resourceName.contains("netherite")) return 10;
        if (resourceName.contains("diamond")) return 9;
        if (resourceName.contains("emerald")) return 8;
        if (resourceName.contains("gold")) return 5;
        if (resourceName.contains("lapis")) return 4;
        if (resourceName.contains("redstone")) return 3;
        if (resourceName.contains("deepslate")) return 3; // 深层变种（铁、铜等的深层版本）
        if (resourceName.contains("copper")) return 2;
        if (resourceName.contains("iron")) return 6; // 铁矿价值高（实用性强）
        if (resourceName.contains("coal")) return 1;
        return 0;
    }

    /**
     * 从资源注册表名中提取矿石中文名。
     * 例如 "minecraft:iron_ore" → "铁矿石"
     */
    private static String extractOreName(String resourceName) {
        // 去掉 namespace
        String name = resourceName.contains(":") ? resourceName.split(":")[1] : resourceName;
        // 映射常见矿石
        if (name.contains("diamond")) return "钻石矿";
        if (name.contains("emerald")) return "绿宝石矿";
        if (name.contains("gold")) return "金矿";
        if (name.contains("iron")) return "铁矿";
        if (name.contains("coal")) return "煤矿";
        if (name.contains("lapis")) return "青金石";
        if (name.contains("redstone")) return "红石矿";
        if (name.contains("copper")) return "铜矿";
        if (name.contains("ancient_debris")) return "远古残骸";
        if (name.contains("deepslate")) return "深板岩矿";
        // fallback
        return name.replace('_', ' ');
    }

    /**
     * 将矿石名映射到对应的技能名。
     */
    private static String mapOreToSkill(String oreName) {
        if (oreName.contains("钻石") || oreName.contains("diamond")) return "mineDiamondOre";
        if (oreName.contains("铁") || oreName.contains("iron")) return "mineIronOre";
        if (oreName.contains("金") || oreName.contains("gold")) return "mineGoldOre";
        if (oreName.contains("煤矿") || oreName.contains("coal")) return "mineCoalOre";
        if (oreName.contains("红石") || oreName.contains("redstone")) return "mineRedstoneOre";
        if (oreName.contains("青金") || oreName.contains("lapis")) return "mineLapisOre";
        if (oreName.contains("铜") || oreName.contains("copper")) return "mineCopperOre";
        if (oreName.contains("绿宝石") || oreName.contains("emerald")) return "mineEmeraldOre";
        return "mineStone"; // 默认挖石头
    }

    /**
     * 将矿石名映射到友好的任务描述。
     */
    private static String mapOreToTaskDescription(String oreName) {
        return "发现" + oreName + "！要挖矿吗？";
    }

    // ================ 数据类型 ================

    /**
     * 课程提议优先级。
     */
    public enum Priority {
        /** 高优先级：生存相关，需立即响应 */
        HIGH,
        /** 中优先级：资源采集等有价值任务 */
        MEDIUM,
        /** 低优先级：优化类建议（升级工具等） */
        LOW
    }

    /**
     * 课程提议 —— 同伴主动提议的下一步任务。
     */
    public static class CurriculumProposal {
        /** 任务的自然语言描述（可直接显示给玩家） */
        public final String taskDescription;
        /** 优先级 */
        public final Priority priority;
        /** 提议原因（简短说明为什么推荐此任务） */
        public final String reason;
        /** 建议执行的技能名（匹配 SkillLibrary 中的技能），可为空 */
        public final String suggestedSkill;

        public CurriculumProposal(String taskDescription, Priority priority,
                                   String reason, String suggestedSkill) {
            this.taskDescription = taskDescription;
            this.priority = priority;
            this.reason = reason;
            this.suggestedSkill = suggestedSkill;
        }

        /**
         * 是否可以直接执行（有匹配的预设技能）。
         */
        public boolean hasSkill() {
            return suggestedSkill != null && !suggestedSkill.isEmpty();
        }

        /**
         * 是否高优先级（生存相关）。
         */
        public boolean isHighPriority() {
            return priority == Priority.HIGH;
        }

        @Override
        public String toString() {
            return String.format("CurriculumProposal{task='%s', priority=%s, reason='%s', skill='%s'}",
                    taskDescription, priority, reason,
                    suggestedSkill != null ? suggestedSkill : "无");
        }
    }
}
