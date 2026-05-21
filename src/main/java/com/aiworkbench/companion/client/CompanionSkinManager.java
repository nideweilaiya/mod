package com.aiworkbench.companion.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages companion skins from local files.
 * Skins are loaded from {world}/aicompanion/skins/ folder (统一到世界存档目录).
 * Supported formats: PNG (64x32 or 64x64)
 */
public class CompanionSkinManager {
    private static final Map<String, ResourceLocation> skinCache = new ConcurrentHashMap<>();
    private static final String SKINS_FOLDER = "aicompanion/skins";

    /** 获取皮肤目录：优先世界存档目录，fallback到.minecraft目录 */
    private static Path getSkinsPath() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return Paths.get(mc.gameDirectory.getPath(), SKINS_FOLDER);

        // 单人游戏：使用世界存档目录
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            return Paths.get(mc.getSingleplayerServer()
                .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .toUri()).resolve(SKINS_FOLDER);
        }
        // 多人游戏/fallback：使用.minecraft目录
        return Paths.get(mc.gameDirectory.getPath(), SKINS_FOLDER);
    }

    /** 加载本地皮肤文件（不含扩展名） */
    public static ResourceLocation loadLocalSkin(String skinId) {
        if (skinCache.containsKey(skinId)) {
            return skinCache.get(skinId);
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return getDefaultSkin();

        File skinFile = getSkinsPath().resolve(skinId + ".png").toFile();

        try {
            if (skinFile.exists() && skinFile.isFile()) {
                ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("aicompanion", "skin/" + skinId);
                DynamicTexture tex = new DynamicTexture(NativeImage.read(Files.readAllBytes(skinFile.toPath())));
                mc.getTextureManager().register(rl, tex);
                skinCache.put(skinId, rl);
                return rl;
            }
        } catch (Exception e) {
            // 静默失败，使用默认皮肤
        }

        return getDefaultSkin();
    }

    /** 获取默认同伴皮肤 (Steve slim) */
    public static ResourceLocation getDefaultSkin() {
        return ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/slim/steve.png");
    }

    /** 列出皮肤目录下所有可用皮肤 */
    public static String[] getAvailableSkins() {
        Path skinsPath = getSkinsPath();
        if (!Files.exists(skinsPath)) return new String[0];

        File[] files = skinsPath.toFile().listFiles((dir, name) -> name.endsWith(".png"));
        if (files == null) return new String[0];

        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            names[i] = files[i].getName().replace(".png", "");
        }
        return names;
    }

    /**
     * Clear skin cache for a specific skin.
     */
    public static void clearCache(String skinId) {
        skinCache.remove(skinId);
    }

    /**
     * Clear all cached skins.
     */
    public static void clearAllCache() {
        skinCache.clear();
    }
}
