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
 * Skins are loaded from .minecraft/aicompanion/skins/ folder.
 * Supported formats: PNG (64x32 or 64x64)
 */
public class CompanionSkinManager {
    private static final Map<String, ResourceLocation> skinCache = new ConcurrentHashMap<>();
    private static final String SKINS_FOLDER = "aicompanion/skins";

    /**
     * Load a skin from local file (without extension).
     * File should be at: .minecraft/aicompanion/skins/{skinId}.png
     */
    public static ResourceLocation loadLocalSkin(String skinId) {
        if (skinCache.containsKey(skinId)) {
            return skinCache.get(skinId);
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return getDefaultSkin();

        Path skinsPath = Paths.get(mc.gameDirectory.getPath(), SKINS_FOLDER);
        File skinFile = skinsPath.resolve(skinId + ".png").toFile();

        try {
            if (skinFile.exists() && skinFile.isFile()) {
                ResourceLocation rl = new ResourceLocation("aicompanion", "skin/" + skinId);
                DynamicTexture tex = new DynamicTexture(NativeImage.read(Files.readAllBytes(skinFile.toPath())));
                mc.getTextureManager().register(rl, tex);
                skinCache.put(skinId, rl);
                // Show in-game message
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§a[OK] Skin loaded: " + skinId), false);
                return rl;
            } else {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§c[FAIL] Skin not found at: " + skinFile.getAbsolutePath()), false);
            }
        } catch (Exception e) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§c[ERROR] " + e.getMessage()), false);
        }

        return getDefaultSkin();
    }

    /**
     * Get the default companion skin (Steve slim).
     */
    public static ResourceLocation getDefaultSkin() {
        return new ResourceLocation("minecraft", "textures/entity/player/slim/steve.png");
    }

    /**
     * Get list of available local skins from the skins folder.
     */
    public static String[] getAvailableSkins() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return new String[0];

        Path skinsPath = Paths.get(mc.gameDirectory.getPath(), SKINS_FOLDER);
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
