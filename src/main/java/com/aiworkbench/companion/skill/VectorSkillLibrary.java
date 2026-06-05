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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 向量技能库 —— 基于 Ollama embedding API 的语义检索系统。
 * <p>
 * 为每个技能的 description 生成 embedding 向量（存储在 JSON 中），
 * 支持根据自然语言任务描述进行语义相似度检索。
 * <p>
 * 目录结构（与现有 SkillLibrary 共享 skills/ 根目录）：
 * <pre>
 *   world/skills/{player_uuid}.json           — 现有 SkillLibrary 技能列表
 *   world/skills/{player_uuid}_embeddings.json — 本类管理的向量索引
 * </pre>
 * thread-safety: ConcurrentHashMap，可安全多线程访问。
 */
public class VectorSkillLibrary {

    private static final String EMBED_MODEL = "nomic-embed-text";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 玩家技能向量索引（玩家UUID → 技能名 → 向量） */
    private final Map<UUID, Map<String, List<Double>>> playerEmbeddings = new ConcurrentHashMap<>();

    /** 全局技能向量缓存（技能名 → 向量），用于预制技能的快速检索 */
    private final Map<String, List<Double>> globalEmbeddingCache = new ConcurrentHashMap<>();

    /** 向量索引文件所在目录 */
    @Nullable
    private File skillsDir;

    public VectorSkillLibrary() {}

    // ================ 目录管理 ================

    /**
     * 设置世界目录，与 SkillLibrary 共享 skills/ 子目录。
     */
    public void setWorldDirectory(File worldDir) {
        this.skillsDir = new File(worldDir, "skills");
        if (!this.skillsDir.exists()) {
            this.skillsDir.mkdirs();
        }
        loadAll();
    }

    // ================ Embedding 生成 ================

    /**
     * 调用 Ollama embedding API 为文本生成向量。
     *
     * @param text 输入文本（技能的 description）
     * @return 向量列表，失败返回空列表
     */
    public List<Double> generateEmbedding(String text) {
        if (AICompanionMod.LLM_DISABLED) return Collections.emptyList();
        if (text == null || text.isBlank()) {
            AICompanionMod.LOGGER.warn("[VectorSkillLib] Cannot generate embedding for empty text");
            return Collections.emptyList();
        }

        return com.aiworkbench.companion.ai.OllamaClient.embed(EMBED_MODEL, text);
    }

    /**
     * 从 Ollama embed API 响应中提取向量。
     * Ollama /api/embed 返回: {"embeddings": [[...]]}
     */
    @SuppressWarnings("unchecked")
    private List<Double> extractEmbedding(Map<String, Object> respMap) {
        if (respMap == null) return Collections.emptyList();

        Object embeddingsObj = respMap.get("embeddings");
        if (embeddingsObj instanceof List<?> embeddingsList && !embeddingsList.isEmpty()) {
            Object firstEmbedding = embeddingsList.get(0);
            if (firstEmbedding instanceof List<?> vecList) {
                List<Double> result = new ArrayList<>();
                for (Object val : vecList) {
                    if (val instanceof Number num) {
                        result.add(num.doubleValue());
                    }
                }
                if (!result.isEmpty()) {
                    AICompanionMod.LOGGER.debug("[VectorSkillLib] Generated embedding with {} dimensions",
                            result.size());
                    return result;
                }
            }
        }
        AICompanionMod.LOGGER.warn("[VectorSkillLib] Could not extract embedding from response");
        return Collections.emptyList();
    }

    // ================ 技能索引 ================

    /**
     * 为单个技能建立向量索引。
     * 同时更新全局缓存和玩家级索引。
     *
     * @param skill     技能对象
     * @param playerUuid 所属玩家（null 表示全局预制技能）
     */
    public void indexSkill(Skill skill, @Nullable UUID playerUuid) {
        if (skill == null || skill.getDescription() == null || skill.getDescription().isBlank()) {
            AICompanionMod.LOGGER.warn("[VectorSkillLib] Cannot index skill with empty description: {}",
                    skill != null ? skill.getName() : "null");
            return;
        }

        String skillName = skill.getName();
        String desc = skill.getDescription();

        // 检查是否已有缓存
        if (globalEmbeddingCache.containsKey(skillName)) {
            AICompanionMod.LOGGER.debug("[VectorSkillLib] Skill '{}' already indexed, reusing cache", skillName);
        } else {
            List<Double> embedding = generateEmbedding(desc);
            if (embedding.isEmpty()) {
                AICompanionMod.LOGGER.warn("[VectorSkillLib] Failed to index skill '{}'", skillName);
                return;
            }
            globalEmbeddingCache.put(skillName, embedding);
            AICompanionMod.LOGGER.info("[VectorSkillLib] Indexed skill '{}' ({} dims)", skillName, embedding.size());
        }

        // 如果指定了玩家，同步更新玩家级索引并持久化
        if (playerUuid != null) {
            Map<String, List<Double>> playerMap = playerEmbeddings.computeIfAbsent(
                    playerUuid, k -> new ConcurrentHashMap<>());
            playerMap.put(skillName, globalEmbeddingCache.get(skillName));
            savePlayerEmbeddings(playerUuid);
        }
    }

    /**
     * 为技能建立全局向量索引（无玩家关联）。
     * 用于预制技能的批量索引。
     */
    public void indexSkill(Skill skill) {
        indexSkill(skill, null);
    }

    /**
     * 重建所有技能的向量索引。
     * 清空现有缓存并重新生成所有向量。
     *
     * @param skills 完整的技能列表
     */
    public void reindexAll(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) {
            AICompanionMod.LOGGER.warn("[VectorSkillLib] reindexAll called with empty skill list");
            return;
        }

        AICompanionMod.LOGGER.info("[VectorSkillLib] Reindexing {} skills...", skills.size());
        globalEmbeddingCache.clear();

        int indexed = 0;
        for (Skill skill : skills) {
            List<Double> embedding = generateEmbedding(skill.getDescription());
            if (!embedding.isEmpty()) {
                globalEmbeddingCache.put(skill.getName(), embedding);
                indexed++;
            }
        }

        AICompanionMod.LOGGER.info("[VectorSkillLib] Reindexed {}/{} skills successfully", indexed, skills.size());
    }

    // ================ 语义检索 ================

    /**
     * 根据自然语言任务描述，在指定玩家的技能库中进行语义相似度检索。
     * <p>
     * 检索范围：
     * <ol>
     *   <li>玩家已学技能（从 playerEmbeddings 获取）</li>
     *   <li>全局预制技能（从 globalEmbeddingCache 获取，作为补充）</li>
     * </ol>
     *
     * @param taskDescription 自然语言任务描述（如 "帮我挖一些铁矿石"）
     * @param playerUuid      玩家 UUID
     * @param topK            返回前 K 个最相似的技能
     * @return 按相似度降序排列的技能列表，可能为空
     */
    public List<SkillScore> searchSimilar(String taskDescription, UUID playerUuid, int topK) {
        if (taskDescription == null || taskDescription.isBlank()) {
            return Collections.emptyList();
        }
        if (topK <= 0) topK = 1;

        // 生成查询向量
        List<Double> queryVector = generateEmbedding(taskDescription);
        if (queryVector.isEmpty()) {
            AICompanionMod.LOGGER.warn("[VectorSkillLib] Cannot search: failed to generate query embedding");
            return Collections.emptyList();
        }

        // 收集所有候选技能及其向量：玩家技能优先，全局预制技能补充
        Map<String, List<Double>> candidates = new LinkedHashMap<>();

        // 1. 玩家已学技能
        Map<String, List<Double>> playerMap = playerEmbeddings.get(playerUuid);
        if (playerMap != null) {
            candidates.putAll(playerMap);
        }

        // 2. 全局预制技能（补充玩家未索引的技能）
        for (Map.Entry<String, List<Double>> entry : globalEmbeddingCache.entrySet()) {
            candidates.putIfAbsent(entry.getKey(), entry.getValue());
        }

        if (candidates.isEmpty()) {
            AICompanionMod.LOGGER.info("[VectorSkillLib] No candidate skills to search for player {}", playerUuid);
            return Collections.emptyList();
        }

        // 计算余弦相似度
        List<SkillScore> scores = new ArrayList<>();
        for (Map.Entry<String, List<Double>> entry : candidates.entrySet()) {
            double similarity = cosineSimilarity(queryVector, entry.getValue());
            if (similarity > 0.0) {
                scores.add(new SkillScore(entry.getKey(), similarity));
            }
        }

        // 按相似度降序排序，取 topK
        scores.sort((a, b) -> Double.compare(b.similarity, a.similarity));
        int limit = Math.min(topK, scores.size());
        List<SkillScore> result = scores.subList(0, limit);

        AICompanionMod.LOGGER.info("[VectorSkillLib] Search '{}' returned {} results (top score: {})",
                taskDescription, result.size(),
                result.isEmpty() ? "N/A" : String.format("%.3f", result.get(0).similarity));

        return result;
    }

    /**
     * 重载：检索并尝试匹配为 Skill 对象。
     *
     * @param taskDescription 任务描述
     * @param playerUuid      玩家 UUID
     * @param library         技能库（用于根据名称解析 Skill 对象）
     * @param topK            返回数量
     * @return 匹配的 Skill 列表
     */
    public List<Skill> searchSimilarSkills(String taskDescription, UUID playerUuid,
                                           SkillLibrary library, int topK) {
        List<SkillScore> scores = searchSimilar(taskDescription, playerUuid, topK);
        if (scores.isEmpty()) return Collections.emptyList();

        List<Skill> result = new ArrayList<>();
        for (SkillScore score : scores) {
            Skill skill = library.getPreset(score.skillName);
            if (skill == null) {
                // 尝试在玩家已学技能中查找
                skill = library.getPlayerSkill(playerUuid, score.skillName);
            }
            if (skill != null) {
                result.add(skill);
            }
        }
        return result;
    }

    // ================ 相似度计算 ================

    /**
     * 计算两个向量的余弦相似度。
     * 返回值在 [0, 1] 之间（已规范化到非负值）。
     */
    public static double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a.isEmpty() || b.isEmpty() || a.size() != b.size()) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.size(); i++) {
            double va = a.get(i);
            double vb = b.get(i);
            dotProduct += va * vb;
            normA += va * va;
            normB += vb * vb;
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        if (denominator == 0.0) return 0.0;

        double cosine = dotProduct / denominator;
        // 规范化到 [0, 1]，clip 最小值到 0
        return Math.max(0.0, Math.min(1.0, cosine));
    }

    // ================ 持久化 ================

    /**
     * 加载所有玩家的向量索引。
     */
    public void loadAll() {
        if (skillsDir == null || !skillsDir.exists()) return;

        playerEmbeddings.clear();
        File[] files = skillsDir.listFiles((dir, name) -> name.endsWith("_embeddings.json"));
        if (files == null) return;

        Type type = new TypeToken<Map<String, List<Double>>>() {}.getType();
        for (File file : files) {
            try (FileReader reader = new FileReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
                // 文件名格式: {uuid}_embeddings.json
                String uuidStr = file.getName().replace("_embeddings.json", "");
                UUID uuid = UUID.fromString(uuidStr);
                Map<String, List<Double>> data = GSON.fromJson(reader, type);
                if (data != null) {
                    playerEmbeddings.put(uuid, new ConcurrentHashMap<>(data));
                }
            } catch (Exception e) {
                AICompanionMod.LOGGER.warn("[VectorSkillLib] Failed to load embeddings from {}: {}",
                        file.getName(), e.getMessage());
            }
        }
        AICompanionMod.LOGGER.info("[VectorSkillLib] Loaded embeddings for {} players", playerEmbeddings.size());
    }

    /**
     * 保存指定玩家的向量索引到 JSON 文件。
     */
    public void savePlayerEmbeddings(UUID playerUuid) {
        if (skillsDir == null) return;

        File file = new File(skillsDir, playerUuid + "_embeddings.json");
        Map<String, List<Double>> data = playerEmbeddings.getOrDefault(playerUuid,
                Collections.emptyMap());

        // 构建可序列化的结构
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("player_uuid", playerUuid.toString());
        wrapper.put("embeddings", data);
        wrapper.put("model", EMBED_MODEL);
        wrapper.put("updated_at", System.currentTimeMillis());

        try (FileWriter writer = new FileWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
            GSON.toJson(wrapper, writer);
        } catch (IOException e) {
            AICompanionMod.LOGGER.error("[VectorSkillLib] Failed to save embeddings for {}: {}",
                    playerUuid, e.getMessage());
        }
    }

    /**
     * 获取全局向量缓存中的技能名集合。
     */
    public Set<String> getIndexedSkillNames() {
        return Collections.unmodifiableSet(globalEmbeddingCache.keySet());
    }

    /**
     * 获取玩家已索引的技能数。
     */
    public int getPlayerIndexedCount(UUID playerUuid) {
        Map<String, List<Double>> map = playerEmbeddings.get(playerUuid);
        return map != null ? map.size() : 0;
    }

    /**
     * 检查指定技能的向量是否已缓存。
     */
    public boolean isIndexed(String skillName) {
        return globalEmbeddingCache.containsKey(skillName);
    }

    // ================ 内部数据类型 ================

    /**
     * 技能相似度得分，用于语义检索结果排序。
     */
    public static class SkillScore {
        /** 技能名 */
        public final String skillName;
        /** 余弦相似度 [0, 1] */
        public final double similarity;

        public SkillScore(String skillName, double similarity) {
            this.skillName = skillName;
            this.similarity = similarity;
        }

        @Override
        public String toString() {
            return String.format("SkillScore{name='%s', sim=%.3f}", skillName, similarity);
        }
    }
}
