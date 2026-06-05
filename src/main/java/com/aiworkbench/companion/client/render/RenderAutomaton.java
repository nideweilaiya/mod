package com.aiworkbench.companion.client.render;

import com.aiworkbench.companion.client.CompanionSkinManager;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;

/**
 * AutomatonEntity 渲染器
 * 支持动态皮肤：默认/URL/玩家名称
 */
public class RenderAutomaton extends LivingEntityRenderer<AutomatonEntity, HumanoidModel<AutomatonEntity>> {

    public RenderAutomaton(EntityRendererProvider.Context context) {
        // 使用标准玩家 slim 模型层
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5f);

        // 盔甲渲染（使用 Forge 1.20.4 构造签名：Renderer + innerModel + outerModel + ModelManager）
        this.addLayer(new HumanoidArmorLayer<>(this,
            new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
            new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
            context.getModelManager()));

        // 手持物品渲染（剑、镐、斧等）
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
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
