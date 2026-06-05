package com.aiworkbench.companion.skill;

import com.aiworkbench.companion.AICompanionMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能库管理器 —— 管理所有玩家的已学技能和预制技能。
 * <p>
 * 预制技能（preset skills）通过 {@link #registerPreset(Skill)} 注册，
 * 是硬编码在 Java 中的开箱即用技能。
 * 玩家的已学技能以 JSON 格式持久化到世界目录。
 * thread-safety: ConcurrentHashMap，可安全多线程访问。
 */
public class SkillLibrary {

    private static final String SKILLS_DIR = "skills";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 向量技能库 —— 语义检索 */
    private final VectorSkillLibrary vectorLib = new VectorSkillLibrary();

    /** 预制技能注册表（技能名 → 技能） */
    private final Map<String, Skill> presets = new ConcurrentHashMap<>();

    /** 玩家已学技能缓存（玩家UUID → 技能名列表） */
    private final Map<UUID, List<String>> learnedSkills = new ConcurrentHashMap<>();

    /** 技能文件所在目录 */
    @Nullable
    private File skillsDir;

    // ================ 预制技能管理 ================

    /**
     * 注册一个预制技能。
     * 预制技能全局可用，所有玩家都可以学习。
     */
    public void registerPreset(Skill skill) {
        if (skill == null || skill.getName() == null || skill.getName().isEmpty()) {
            AICompanionMod.LOGGER.warn("[SkillLibrary] Attempted to register invalid skill");
            return;
        }
        presets.put(skill.getName(), skill);
        // 同时索引到向量库，供语义搜索使用
        vectorLib.indexSkill(skill);
        AICompanionMod.LOGGER.debug("[SkillLibrary] Registered preset skill: '{}'", skill.getName());
    }

    /** 获取所有预制技能 */
    public Collection<Skill> getAllPresets() {
        return presets.values();
    }

    /** 根据名称获取预制技能 */
    @Nullable
    public Skill getPreset(String name) {
        return presets.get(name);
    }

    // ================ 玩家技能管理 ================

    /**
     * 玩家学习一个技能。
     * @return true 如果学习成功
     */
    public boolean learnSkill(UUID playerUuid, String skillName) {
        if (!presets.containsKey(skillName)) {
            AICompanionMod.LOGGER.warn("[SkillLibrary] Skill '{}' not found in presets", skillName);
            return false;
        }

        List<String> playerSkills = learnedSkills.computeIfAbsent(playerUuid, k -> new ArrayList<>());
        if (playerSkills.contains(skillName)) {
            AICompanionMod.LOGGER.info("[SkillLibrary] Player {} already knows skill '{}'", playerUuid, skillName);
            return false;
        }

        // 检查前置技能
        Skill skill = presets.get(skillName);
        for (String prereq : skill.getPrerequisites()) {
            if (!playerSkills.contains(prereq)) {
                AICompanionMod.LOGGER.warn("[SkillLibrary] Missing prerequisite '{}' for skill '{}'", prereq, skillName);
                return false;
            }
        }

        playerSkills.add(skillName);
        savePlayerSkills(playerUuid);
        AICompanionMod.LOGGER.info("[SkillLibrary] Player {} learned skill '{}'", playerUuid, skillName);
        return true;
    }

    /**
     * 玩家遗忘一个技能。
     */
    public boolean forgetSkill(UUID playerUuid, String skillName) {
        List<String> playerSkills = learnedSkills.get(playerUuid);
        if (playerSkills == null || !playerSkills.remove(skillName)) {
            return false;
        }
        savePlayerSkills(playerUuid);
        return true;
    }

    /** 获取玩家已学技能列表 */
    public List<Skill> getPlayerSkills(UUID playerUuid) {
        List<String> names = learnedSkills.get(playerUuid);
        if (names == null || names.isEmpty()) {
            return Collections.emptyList();
        }
        List<Skill> result = new ArrayList<>();
        for (String name : names) {
            Skill preset = presets.get(name);
            if (preset != null) {
                result.add(preset);
            }
        }
        return result;
    }

    /** 获取玩家已学技能的名称列表 */
    public List<String> getPlayerSkillNames(UUID playerUuid) {
        return learnedSkills.getOrDefault(playerUuid, Collections.emptyList());
    }

    /**
     * 根据名称获取玩家的技能定义。
     * 先在已学技能中查找，再在预制技能中查找。
     */
    @Nullable
    public Skill getPlayerSkill(UUID playerUuid, String skillName) {
        List<String> names = learnedSkills.get(playerUuid);
        if (names != null && names.contains(skillName)) {
            return presets.get(skillName);
        }
        return null;
    }

    /** 玩家是否已学某个技能 */
    public boolean hasSkill(UUID playerUuid, String skillName) {
        List<String> names = learnedSkills.get(playerUuid);
        return names != null && names.contains(skillName);
    }

    /** 为所有玩家注册所有预制技能（管理员命令） */
    public void learnAllPresets(UUID playerUuid) {
        for (String skillName : presets.keySet()) {
            List<String> playerSkills = learnedSkills.computeIfAbsent(playerUuid, k -> new ArrayList<>());
            if (!playerSkills.contains(skillName)) {
                playerSkills.add(skillName);
            }
        }
        savePlayerSkills(playerUuid);
    }

    // ================ 持久化 ================

    /**
     * 设置世界目录并加载所有玩家技能。
     */
    public void setWorldDirectory(File worldDir) {
        this.skillsDir = new File(worldDir, SKILLS_DIR);
        if (!this.skillsDir.exists()) {
            this.skillsDir.mkdirs();
        }
        loadAll();
        // 同步设置向量库目录并加载已持久化的嵌入向量
        vectorLib.setWorldDirectory(worldDir);
    }

    /** 加载所有玩家技能文件 */
    public void loadAll() {
        if (skillsDir == null || !skillsDir.exists()) return;

        learnedSkills.clear();
        File[] files = skillsDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;

        Type type = new TypeToken<Map<String, List<String>>>() {}.getType();
        for (File file : files) {
            try (FileReader reader = new FileReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
                Map<String, List<String>> data = GSON.fromJson(reader, type);
                if (data != null && data.containsKey("skills")) {
                    String uuidStr = file.getName().replace(".json", "");
                    UUID uuid = UUID.fromString(uuidStr);
                    learnedSkills.put(uuid, data.get("skills"));
                }
            } catch (Exception e) {
                AICompanionMod.LOGGER.warn("[SkillLibrary] Failed to load skills from {}: {}", file.getName(), e.getMessage());
            }
        }
        AICompanionMod.LOGGER.info("[SkillLibrary] Loaded skills for {} players", learnedSkills.size());
    }

    /** 保存指定玩家的技能到 JSON 文件 */
    public void savePlayerSkills(UUID playerUuid) {
        if (skillsDir == null) return;

        File file = new File(skillsDir, playerUuid + ".json");
        List<String> skills = learnedSkills.getOrDefault(playerUuid, Collections.emptyList());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("player_uuid", playerUuid.toString());
        data.put("skills", skills);

        try (FileWriter writer = new FileWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
            GSON.toJson(data, writer);
        } catch (IOException e) {
            AICompanionMod.LOGGER.error("[SkillLibrary] Failed to save skills for {}: {}", playerUuid, e.getMessage());
        }
    }

    // ================ 语义搜索 ================

    /**
     * 根据自然语言任务描述进行语义相似度检索。
     * 委托给 VectorSkillLibrary 的嵌入向量搜索实现。
     *
     * @param taskDescription 自然语言任务描述（如 "帮我挖一些铁矿石"）
     * @param playerUuid      搜索的玩家 UUID
     * @param topK            返回前 K 个最相似的技能
     * @return 按相似度降序排列的技能列表
     */
    public List<Skill> searchSimilar(String taskDescription, UUID playerUuid, int topK) {
        return vectorLib.searchSimilarSkills(taskDescription, playerUuid, this, topK);
    }

    /**
     * 根据描述查找技能 —— 先精确匹配名称，失败时回退到语义搜索。
     *
     * @param description 技能名称或自然语言描述
     * @param playerUuid  搜索的玩家 UUID
     * @return 最匹配的技能，未找到返回 null
     */
    @Nullable
    public Skill findSkillByDescription(String description, UUID playerUuid) {
        // 先尝试精确名称匹配
        Skill exact = getPreset(description);
        if (exact != null) return exact;
        // 再尝试玩家已学技能名称匹配
        exact = getPlayerSkill(playerUuid, description);
        if (exact != null) return exact;
        // 回退到语义搜索
        List<Skill> results = searchSimilar(description, playerUuid, 1);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 重建所有预制技能的嵌入向量索引。
     * 清空现有向量缓存，重新为所有已注册的预制技能生成嵌入向量。
     */
    public void reindexAllPresets() {
        vectorLib.reindexAll(new ArrayList<>(presets.values()));
        AICompanionMod.LOGGER.info("[SkillLibrary] Reindexed all {} presets into vector library", presets.size());
    }

    /** 获取内部向量技能库（供命令和外部组件使用） */
    public VectorSkillLibrary getVectorLibrary() {
        return vectorLib;
    }
}
