package com.aiworkbench.companion.client.render;

import com.aiworkbench.companion.client.CompanionSkinManager;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * AutomatonEntity 渲染器
 * 支持动态皮肤：默认/URL/玩家名称
 */
public class RenderAutomaton extends LivingEntityRenderer<AutomatonEntity, HumanoidModel<AutomatonEntity>> {

    public RenderAutomaton(EntityRendererProvider.Context context) {
        // 使用标准玩家 slim 模型层
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5f);
    }

    @Override
    public ResourceLocation getTextureLocation(AutomatonEntity entity) {
        int skinType = entity.getSkinType();
        String skinValue = entity.getSkinValue();

        switch (skinType) {
            case AutomatonEntity.SKIN_TYPE_URL:
                if (skinValue != null && !skinValue.isEmpty()) {
                    return CompanionSkinManager.loadLocalSkin(skinValue);
                }
                break;
            case AutomatonEntity.SKIN_TYPE_PLAYER:
                if (skinValue != null && !skinValue.isEmpty()) {
                    return CompanionSkinManager.loadLocalSkin(skinValue);
                }
                break;
            case AutomatonEntity.SKIN_TYPE_DEFAULT:
            default:
                return CompanionSkinManager.getDefaultSkin();
        }

        return CompanionSkinManager.getDefaultSkin();
    }
}
