package com.aiworkbench.companion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 同伴配置持久化管理器 — 基于 Gson 标准序列化。
 * <p>
 * 配置文件位置：{world}/aicompanion/config.json<br>
 * 存储内容：AI模型设置、感知间隔、拾取默认值等。
 */
public class CompanionConfig {
    private static final String CONFIG_FILE = "aicompanion/config.json";
    private static final Logger LOGGER = LogManager.getLogger("CompanionConfig");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>(){}.getType();

    private static final Map<String, String> config = new HashMap<>();
    private static boolean loaded = false;

    // 默认 AI 模型
    public static final String DEFAULT_MODEL = "llama3.2:latest";

    // 可配置的感知和拾取默认值（可通过配置文件覆盖）
    public static final String KEY_DEFAULT_MODEL = "default_model";
    public static final String KEY_AI_ENDPOINT = "ai_endpoint";
    public static final String KEY_AI_TIMEOUT_MS = "ai_timeout_ms";
    public static final String KEY_PERCEPTION_INTERVAL = "perception_interval_ticks";
    public static final String KEY_SITUATION_EVAL_INTERVAL = "situation_eval_interval_ticks";
    public static final String KEY_AUTO_PICKUP_INTERVAL = "auto_pickup_interval_ticks";
    public static final String KEY_DEFAULT_PICKUP_RADIUS = "default_pickup_radius";
    public static final String KEY_DEFAULT_PICKUP_VALUABLE = "default_pickup_valuable_only";

    // 默认值
    private static final String DEFAULT_AI_ENDPOINT = "http://localhost:11434/api/generate";
    private static final String DEFAULT_AI_TIMEOUT_MS = "30000";
    private static final String DEFAULT_PERCEPTION_INTERVAL = "4";
    private static final String DEFAULT_SITUATION_EVAL_INTERVAL = "60";
    private static final String DEFAULT_AUTO_PICKUP_INTERVAL = "10";
    private static final String DEFAULT_PICKUP_RADIUS = "5.0";
    private static final String DEFAULT_PICKUP_VALUABLE = "false";

    /**
     * 从世界目录加载配置文件（Gson 解析）。
     */
    public static synchronized void load(MinecraftServer server) {
        if (loaded) return;

        try {
            Path configPath = server.getWorldPath(LevelResource.ROOT).resolve(CONFIG_FILE);
            File file = configPath.toFile();

            if (file.exists()) {
                String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                Map<String, String> parsed = GSON.fromJson(content, MAP_TYPE);
                if (parsed != null) {
                    config.putAll(parsed);
                }
                LOGGER.info("[CompanionConfig] Loaded {} entries from {}", config.size(), file.getAbsolutePath());
            } else {
                LOGGER.info("[CompanionConfig] No config file, using defaults");
            }
        } catch (Exception e) {
            LOGGER.error("[CompanionConfig] Failed to load config: {}", e.getMessage());
        }

        loaded = true;
    }

    /**
     * 保存配置到 JSON 文件（Gson pretty print）。
     */
    public static void save(MinecraftServer server) {
        try {
            Path configPath = server.getWorldPath(LevelResource.ROOT).resolve(CONFIG_FILE);
            File file = configPath.toFile();
            file.getParentFile().mkdirs();

            String json = GSON.toJson(config, MAP_TYPE);
            Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
            LOGGER.info("[CompanionConfig] Saved {} entries to {}", config.size(), file.getAbsolutePath());
        } catch (Exception e) {
            LOGGER.error("[CompanionConfig] Failed to save config: {}", e.getMessage());
        }
    }

    // ==================== 公共查询 API ====================

    /** 获取某玩家的 AI 模型设置 */
    public static String getModel(UUID playerUUID) {
        return config.getOrDefault("model_" + playerUUID.toString(), getDefaultModel());
    }

    /** 设置某玩家的 AI 模型 */
    public static void setModel(UUID playerUUID, String model) {
        config.put("model_" + playerUUID.toString(), model);
        LOGGER.info("[CompanionConfig] Set model for {}: {}", playerUUID, model);
    }

    /** 获取全局默认 AI 模型 */
    public static String getDefaultModel() {
        return config.getOrDefault(KEY_DEFAULT_MODEL, DEFAULT_MODEL);
    }

    /** 设置全局默认 AI 模型 */
    public static void setDefaultModel(String model) {
        config.put(KEY_DEFAULT_MODEL, model);
        LOGGER.info("[CompanionConfig] Set default model: {}", model);
    }

    // ==================== 高级配置查询 ====================

    /** AI 端点 URL */
    public static String getAiEndpoint() {
        return config.getOrDefault(KEY_AI_ENDPOINT, DEFAULT_AI_ENDPOINT);
    }

    /** AI 超时时间（毫秒） */
    public static int getAiTimeoutMs() {
        return parseInt(KEY_AI_TIMEOUT_MS, DEFAULT_AI_TIMEOUT_MS);
    }

    /** 感知数据推送间隔（tick） */
    public static int getPerceptionInterval() {
        return parseInt(KEY_PERCEPTION_INTERVAL, DEFAULT_PERCEPTION_INTERVAL);
    }

    /** 情境评估间隔（tick） */
    public static int getSituationEvalInterval() {
        return parseInt(KEY_SITUATION_EVAL_INTERVAL, DEFAULT_SITUATION_EVAL_INTERVAL);
    }

    /** 自动拾取间隔（tick） */
    public static int getAutoPickupInterval() {
        return parseInt(KEY_AUTO_PICKUP_INTERVAL, DEFAULT_AUTO_PICKUP_INTERVAL);
    }

    /** 默认拾取半径 */
    public static double getDefaultPickupRadius() {
        try {
            return Double.parseDouble(config.getOrDefault(KEY_DEFAULT_PICKUP_RADIUS, DEFAULT_PICKUP_RADIUS));
        } catch (NumberFormatException e) {
            return 5.0;
        }
    }

    /** 默认是否仅拾取贵重物品 */
    public static boolean getDefaultPickupValuableOnly() {
        return Boolean.parseBoolean(config.getOrDefault(KEY_DEFAULT_PICKUP_VALUABLE, DEFAULT_PICKUP_VALUABLE));
    }

    private static int parseInt(String key, String defaultVal) {
        try {
            return Integer.parseInt(config.getOrDefault(key, defaultVal));
        } catch (NumberFormatException e) {
            return Integer.parseInt(defaultVal);
        }
    }
}
