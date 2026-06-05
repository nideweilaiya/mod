package com.aiworkbench.companion.manager;

import com.aiworkbench.companion.AICompanionMod;
import net.minecraft.nbt.CompoundTag;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 角色管理器
 * 管理同伴角色的数据
 */
public class CharacterManager {
    // 角色ID -> 角色数据
    private final Map<String, CharacterData> characters = new HashMap<>();

    /**
     * 创建默认角色
     */
    public CharacterData createDefaultCharacter(String name) {
        CharacterData data = new CharacterData();
        data.name = name;
        data.displayName = "§a同伴 §f" + name;
        data.skinUrl = "";
        data.personality = "friendly";
        data.isActive = true;
        characters.put(name, data);
        AICompanionMod.LOGGER.info("Created character: {}", name);
        return data;
    }

    /**
     * 获取角色
     */
    public CharacterData getCharacter(String characterId) {
        return characters.get(characterId);
    }

    /**
     * 删除角色
     */
    public void deleteCharacter(String characterId) {
        characters.remove(characterId);
        AICompanionMod.LOGGER.info("Deleted character: {}", characterId);
    }

    /**
     * 获取所有角色
     */
    public Map<String, CharacterData> getAllCharacters() {
        return new HashMap<>(characters);
    }

    /**
     * 保存角色到NBT
     */
    public void saveToNBT(CompoundTag tag) {
        CompoundTag charactersTag = new CompoundTag();
        for (Map.Entry<String, CharacterData> entry : characters.entrySet()) {
            charactersTag.put(entry.getKey(), entry.getValue().toNBT());
        }
        tag.put("characters", charactersTag);
    }

    /**
     * 从NBT加载
     */
    public void loadFromNBT(CompoundTag tag) {
        if (tag.contains("characters")) {
            CompoundTag charactersTag = tag.getCompound("characters");
            for (String key : charactersTag.getAllKeys()) {
                CharacterData data = CharacterData.fromNBT(charactersTag.getCompound(key));
                characters.put(key, data);
            }
        }
    }

    /**
     * 角色数据类
     */
    public static class CharacterData {
        public String id = "";
        public String name = "";
        public String displayName = "";
        public String skinUrl = "";
        public String personality = "friendly";
        public boolean isActive = false;

        public CompoundTag toNBT() {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", id);
            tag.putString("name", name);
            tag.putString("displayName", displayName);
            tag.putString("skinUrl", skinUrl);
            tag.putString("personality", personality);
            tag.putBoolean("isActive", isActive);
            return tag;
        }

        public static CharacterData fromNBT(CompoundTag tag) {
            CharacterData data = new CharacterData();
            data.id = tag.getString("id");
            data.name = tag.getString("name");
            data.displayName = tag.getString("displayName");
            data.skinUrl = tag.getString("skinUrl");
            data.personality = tag.getString("personality");
            data.isActive = tag.getBoolean("isActive");
            return data;
        }
    }
}
