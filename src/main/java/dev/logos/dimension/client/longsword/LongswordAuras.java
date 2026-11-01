package dev.logos.dimension.client.longsword;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.SurfaceAuras;
import dev.logos.mixin.ItemStackRenderStateAccessor;
import dev.logos.mixin.LayerRenderStateAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;

/** 复用 Logos 公开的物品表面入口，只将原版物品表面定位到正在运动的剑骨骼。 */
public final class LongswordAuras {
    private static final ItemStackRenderState FIRST_PERSON=new ItemStackRenderState();
    private LongswordAuras() {}

    public static void firstPerson(LivingEntity holder, LongswordRig.Sample sample, PoseStack pose, SubmitNodeCollector collector) {
        var context=holder.getMainArm()==net.minecraft.world.entity.HumanoidArm.LEFT
                ? ItemDisplayContext.FIRST_PERSON_LEFT_HAND : ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        Minecraft.getInstance().getItemModelResolver().updateForLiving(FIRST_PERSON,holder.getMainHandItem(),context,holder);
        submit(FIRST_PERSON,sample,pose,collector,.58f);
    }
    public static void submit(ItemStackRenderState item, LongswordRig.Sample sample, PoseStack pose, SubmitNodeCollector collector, float scale) {
        if(item==null || item.isEmpty())return;
        var access=(ItemStackRenderStateAccessor)item;
        if(access.logos$activeLayerCount()==0)return;
        var layer=access.logos$layers()[0];
        var surface=(LayerRenderStateAccessor)layer;
        var display=surface.logos$itemTransform();var local=new Matrix4f(surface.logos$localTransform());
        // 这里仅画特效，剑已由骨架提交；临时去掉原版拿法，结束后完整恢复原渲染状态。
        // NO_TRANSFORM 仍会将物品原点平移 -0.5；抵消它，使表面与原始模型像素严格对齐。
        layer.setItemTransform(ItemTransform.NO_TRANSFORM);layer.setLocalTransform(new Matrix4f().translation(.5f,.5f,.5f));
        pose.pushPose();
        try {
            pose.mulPose(sample.bones().get("sword_root"));
            pose.translate(0,1,0);pose.scale(scale,scale,scale);pose.translate(-8,-1,-8);pose.scale(16,16,16);
            SurfaceAuras.submitItem((SurfaceAuras.Carrier)item,pose,collector);
        } finally {
            pose.popPose();layer.setItemTransform(display);layer.setLocalTransform(local);
        }
    }
}
