package com.aiworkbench.companion;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages companion configuration persistence.
 * Stores AI model settings and other per-player/per-companion config.
 */
public class CompanionConfig {
    private static final String CONFIG_FILE = "aicompanion/config.json";
    private static Map<String, String> config = new HashMap<>();
    private static boolean loaded = false;

    // Default AI model
    public static final String DEFAULT_MODEL = "llama3.2:latest";

    /**
     * Load config from file
     */
    public static synchronized void load(MinecraftServer server) {
        if (loaded) return;

        try {
            Path configPath = server.getWorldPath(LevelResource.ROOT).resolve(CONFIG_FILE);
            File file = configPath.toFile();

            if (file.exists()) {
                String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                // Simple parse: "key":"value" pairs
                parseConfig(content);
                AICompanionMod.LOGGER.info("[CompanionConfig] Loaded config from " + file.getAbsolutePath());
            } else {
                AICompanionMod.LOGGER.info("[CompanionConfig] No config file, using defaults");
            }
        } catch (Exception e) {
            AICompanionMod.LOGGER.error("[CompanionConfig] Failed to load config: " + e.getMessage());
        }

        loaded = true;
    }

    /**
     * Save config to file
     */
    public static void save(MinecraftServer server) {
        try {
            Path configPath = server.getWorldPath(LevelResource.ROOT).resolve(CONFIG_FILE);
            File file = configPath.toFile();
            file.getParentFile().mkdirs();

            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<String, String> entry : config.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append("  \"").append(escapeJson(entry.getKey())).append("\": \"")
                  .append(escapeJson(entry.getValue())).append("\"");
            }
            sb.append("\n}\n");

            Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            AICompanionMod.LOGGER.info("[CompanionConfig] Saved config to " + file.getAbsolutePath());
        } catch (Exception e) {
            AICompanionMod.LOGGER.error("[CompanionConfig] Failed to save config: " + e.getMessage());
        }
    }

    /**
     * Get AI model for a player
     */
    public static String getModel(UUID playerUUID) {
        return config.getOrDefault("model_" + playerUUID.toString(), DEFAULT_MODEL);
    }

    /**
     * Set AI model for a player
     */
    public static void setModel(UUID playerUUID, String model) {
        config.put("model_" + playerUUID.toString(), model);
        AICompanionMod.LOGGER.info("[CompanionConfig] Set model for " + playerUUID + " to " + model);
    }

    /**
     * Get default AI model
     */
    public static String getDefaultModel() {
        return config.getOrDefault("default_model", DEFAULT_MODEL);
    }

    /**
     * Set default AI model
     */
    public static void setDefaultModel(String model) {
        config.put("default_model", model);
        AICompanionMod.LOGGER.info("[CompanionConfig] Set default model to " + model);
    }

    private static void parseConfig(String content) {
        config.clear();
        content = content.trim();
        if (!content.startsWith("{") || !content.endsWith("}")) return;

        content = content.substring(1, content.length() - 1);
        int depth = 0;
        StringBuilder current = new StringBuilder();
        String key = null;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (depth == 0 && c == ':' && key == null) {
                key = current.toString().trim();
                current = new StringBuilder();
            } else if (depth == 0 && c == ',') {
                if (key != null) {
                    addConfigEntry(key, current.toString());
                    key = null;
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }
        if (key != null) {
            addConfigEntry(key, current.toString());
        }
    }

    private static void addConfigEntry(String key, String value) {
        key = key.trim();
        value = value.trim();
        if (key.startsWith("\"") && key.endsWith("\"")) {
            key = key.substring(1, key.length() - 1);
        }
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (!key.isEmpty()) {
            config.put(key, unescapeJson(value));
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                 .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String unescapeJson(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
    }
}
