package com.aiworkbench.companion.personality;

import net.minecraft.nbt.CompoundTag;

/**
 * 同伴性格档案 —— 4 维特质 + 说话风格 + 外貌。
 * <p>
 * 特质范围 0.0-1.0，默认 0.5（均衡）。
 * 性格影响：对话语气、行为偏好（非危机决策）、自发对话频率。
 */
public class CompanionPersonality {

    // ===== 4 维特质 =====
    public float curiosity;     // 好奇心：探索意愿、采集热情
    public float bravery;       // 勇敢：战斗倾向、风险承受
    public float sociability;   // 社交性：对话频率、表达丰富度
    public float carefulness;   // 细心：安全检查、背包整理

    // ===== 表达风格 =====
    public String speechStyle;  // 说话风格描述（注入系统提示词）
    public String appearance;   // 外貌描述

    // ===== 元数据 =====
    public String generatedFrom; // 生成来源（玩家原始描述）
    public boolean isGenerated;  // 是否由LLM生成（false=默认均衡性格）

    public CompanionPersonality() {
        this.curiosity = 0.5f;
        this.bravery = 0.5f;
        this.sociability = 0.5f;
        this.carefulness = 0.5f;
        this.speechStyle = "友好自然";
        this.appearance = "";
        this.generatedFrom = "";
        this.isGenerated = false;
    }

    /** 用特定特质值创建 */
    public CompanionPersonality(float curiosity, float bravery, float sociability, float carefulness,
                                 String speechStyle, String appearance) {
        this.curiosity = clamp(curiosity);
        this.bravery = clamp(bravery);
        this.sociability = clamp(sociability);
        this.carefulness = clamp(carefulness);
        this.speechStyle = speechStyle;
        this.appearance = appearance;
        this.isGenerated = true;
    }

    // ===== 特质描述（中文） =====

    public String describeTraits() {
        StringBuilder sb = new StringBuilder();
        if (curiosity > 0.7f) sb.append("好奇心旺盛");
        else if (curiosity < 0.3f) sb.append("不爱探索");
        if (bravery > 0.7f) sb.append("、勇敢无畏");
        else if (bravery < 0.3f) sb.append("、胆小谨慎");
        if (sociability > 0.7f) sb.append("、活泼话多");
        else if (sociability < 0.3f) sb.append("、沉默寡言");
        if (carefulness > 0.7f) sb.append("、细心周到");
        else if (carefulness < 0.3f) sb.append("、粗心莽撞");
        if (sb.isEmpty()) sb.append("性格均衡");
        return sb.toString();
    }

    /** 简短标签（HUD用） */
    public String shortLabel() {
        if (curiosity > 0.7f && bravery > 0.7f) return "冒险家";
        if (bravery > 0.7f && carefulness < 0.3f) return "莽撞";
        if (carefulness > 0.7f && bravery < 0.3f) return "谨慎";
        if (sociability > 0.7f) return "开朗";
        if (sociability < 0.3f) return "沉默";
        if (bravery > 0.7f) return "勇敢";
        return "均衡";
    }

    // ===== NBT 持久化 =====

    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("curiosity", curiosity);
        tag.putFloat("bravery", bravery);
        tag.putFloat("sociability", sociability);
        tag.putFloat("carefulness", carefulness);
        tag.putString("speechStyle", speechStyle != null ? speechStyle : "");
        tag.putString("appearance", appearance != null ? appearance : "");
        tag.putString("generatedFrom", generatedFrom != null ? generatedFrom : "");
        tag.putBoolean("isGenerated", isGenerated);
        return tag;
    }

    public static CompanionPersonality fromNBT(CompoundTag tag) {
        CompanionPersonality p = new CompanionPersonality();
        p.curiosity = clamp(tag.getFloat("curiosity"));
        p.bravery = clamp(tag.getFloat("bravery"));
        p.sociability = clamp(tag.getFloat("sociability"));
        p.carefulness = clamp(tag.getFloat("carefulness"));
        p.speechStyle = tag.getString("speechStyle");
        p.appearance = tag.getString("appearance");
        p.generatedFrom = tag.contains("generatedFrom") ? tag.getString("generatedFrom") : "";
        p.isGenerated = tag.getBoolean("isGenerated");
        return p;
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    @Override
    public String toString() {
        return String.format("Personality{c=%.1f,b=%.1f,s=%.1f,ca=%.1f, speech=%s}",
            curiosity, bravery, sociability, carefulness, speechStyle);
    }
}
