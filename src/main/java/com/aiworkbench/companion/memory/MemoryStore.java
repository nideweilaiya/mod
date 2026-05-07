package com.aiworkbench.companion.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记忆存储 — JSON 文件读写，按同伴 UUID 分目录。
 * <p>
 * 目录结构：
 *   memory/{uuid}/
 *     profile.json         — L3 语义记忆（玩家画像）
 *     summary_1.json       — L2 情景摘要块 #1
 *     summary_2.json       — L2 情景摘要块 #2
 *     session_log.jsonl    — 对话日志（追加）
 */
public class MemoryStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path baseDir;

    public MemoryStore(Path worldDir) {
        this.baseDir = worldDir.resolve("companion_memory");
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ==================== 路径 ====================

    private Path companionDir(String uuid) {
        Path dir = baseDir.resolve(uuid);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dir;
    }

    // ==================== L2 对话日志 (JSONL) ====================

    public void appendConversation(String uuid, String role, String content) {
        Path logFile = companionDir(uuid).resolve("session_log.jsonl");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", System.currentTimeMillis());
        entry.put("role", role);
        entry.put("content", content);
        String line = GSON.toJson(entry) + "\n";
        try {
            Files.write(logFile, line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    public List<Map<String, Object>> readRecentConversations(String uuid, int max) {
        Path logFile = companionDir(uuid).resolve("session_log.jsonl");
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.exists(logFile)) return result;

        try (BufferedReader reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
            String line;
            LinkedList<Map<String, Object>> buffer = new LinkedList<>();
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    try {
                        Map<String, Object> entry = GSON.fromJson(line, Map.class);
                        buffer.add(entry);
                        if (buffer.size() > max) buffer.removeFirst();
                    } catch (Exception ignored) {}
                }
            }
            result.addAll(buffer);
        } catch (IOException ignored) {}
        return result;
    }

    // ==================== L2 情景摘要块 ====================

    public void saveSummaryBlock(String uuid, int blockIndex, String summary) {
        Path file = companionDir(uuid).resolve("summary_" + blockIndex + ".json");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("block", blockIndex);
        data.put("timestamp", System.currentTimeMillis());
        data.put("summary", summary);
        try {
            Files.write(file, GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
    }

    public List<Map<String, Object>> loadAllSummaries(String uuid) {
        List<Map<String, Object>> summaries = new ArrayList<>();
        Path dir = companionDir(uuid);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "summary_*.json")) {
            for (Path file : stream) {
                try {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    Map<String, Object> data = GSON.fromJson(content, Map.class);
                    summaries.add(data);
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}
        summaries.sort(Comparator.comparingInt(m -> ((Number) m.getOrDefault("block", 0)).intValue()));
        return summaries;
    }

    public int getNextSummaryBlockIndex(String uuid) {
        return loadAllSummaries(uuid).size();
    }

    // ==================== L3 语义记忆（玩家画像） ====================

    public Map<String, Object> loadProfile(String uuid) {
        Path file = companionDir(uuid).resolve("profile.json");
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return GSON.fromJson(content, new TypeToken<LinkedHashMap<String, Object>>(){}.getType());
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public void saveProfile(String uuid, Map<String, Object> profile) {
        Path file = companionDir(uuid).resolve("profile.json");
        try {
            Files.write(file, GSON.toJson(profile).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
    }

    // ==================== 关键词检索 ====================

    public String searchMemories(String uuid, String keyword) {
        StringBuilder result = new StringBuilder();
        for (Map<String, Object> summary : loadAllSummaries(uuid)) {
            String text = (String) summary.getOrDefault("summary", "");
            if (text.contains(keyword)) {
                result.append("[").append(summary.get("block")).append("] ").append(text).append("\n");
            }
        }

        Map<String, Object> profile = loadProfile(uuid);
        for (Map.Entry<String, Object> entry : profile.entrySet()) {
            String val = String.valueOf(entry.getValue());
            if (val.contains(keyword)) {
                result.append("[画像] ").append(entry.getKey()).append(": ").append(val).append("\n");
            }
        }

        return result.isEmpty() ? null : result.toString().trim();
    }
}
