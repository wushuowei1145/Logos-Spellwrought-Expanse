package dev.logos.dimension.client;

import com.geckolib.cache.model.GeoBone;
import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.builtin.BlockAndItemGeoLayer;
import com.geckolib.renderer.layer.builtin.ItemInHandGeoLayer;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.SurfaceAuras;
import dev.logos.dimension.item.UchigatanaItem;
import dev.logos.dimension.npc.DivineFox;
import dev.logos.mixin.ItemStackRenderStateAccessor;
import dev.logos.mixin.LayerRenderStateAccessor;
import dev.logos.runtime.SurfaceAura;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

/**
 * 神狐的渲染：刀由模型里的 {@code Weapon} / {@code Sheath} 骨骼画（打刀动作直接驱动），所以手里的刀不再按物品画一遍；
 * 缠着狐火缠绕时，刀身与手里的弓燃起幽蓝的狐火——复用 Logos 的物品表面火焰（{@code /alchemy aura fire}），色相转到鬼火的蓝，
 * 并换掉 Logos 按奥秘元素画的缠绕外观。
 */
public class DivineFoxRenderer<R extends LivingEntityRenderState & GeoRenderState> extends NpcRenderer<DivineFox, R> {
    /** 火焰色相：原本的橙红转半圈，成为幽蓝的鬼火。 */
    public static final float GHOST_HUE = .5f;
    private static final SurfaceAura GHOST_FIRE = new SurfaceAura(SurfaceAura.Style.FIRE, GHOST_HUE);
    /** 刀网格的单位是像素，神狐的刀按 1.6 倍挂（tools/divine_fox_assets.py 的 KATANA_SCALE）。 */
    private static final float KATANA_SCALE = 1.6f;
    /** 本帧要在刀身上画的狐火（物品渲染状态，已换成狐火），没有为空。 */
    private static final DataTicket<ItemStackRenderState> BLADE_FIRE = DataTicket.create("logos_dimension_fox_blade_fire", ItemStackRenderState.class);
    /** 这只神狐的刀装（刀身骨骼 {@code katana_<配色>_blade}）。 */
    private static final DataTicket<String> BLADE_BONE = DataTicket.create("logos_dimension_fox_blade_bone", String.class);
    /** 客户端测试用：画过几次刀身狐火。 */
    private static int bladeFires;

    public DivineFoxRenderer(EntityRendererProvider.Context context) {
        super(context, "divine_fox");
    }

    public static int bladeFires() {
        return bladeFires;
    }

    @Override
    protected ItemInHandGeoLayer<DivineFox, Void, R> handLayer(EntityRendererProvider.Context context) {
        return new ItemInHandGeoLayer<>(context, this, RIGHT_HAND_BONE, LEFT_HAND_BONE) {
            @Override
            protected BlockAndItemGeoLayer.RenderData renderDataForHand(String bone, HumanoidArm arm, DivineFox fox, R state) {
                boolean main = arm == fox.getMainArm();
                ItemStack stack = main ? fox.getMainHandItem() : fox.getOffhandItem();
                if (stack.getItem() instanceof UchigatanaItem) return null;
                BlockAndItemGeoLayer.RenderData data = super.renderDataForHand(bone, arm, fox, state);
                if (data != null && main && stack.getItem() instanceof BowItem && fox.wrapped())
                    data.modelState().ifLeft(item -> ghostFire(item, fox.getId()));
                return data;
            }
        };
    }

    /** 物品上只留狐火：去掉 Logos 按元素给出的缠绕外观，换成幽蓝的表面火焰。 */
    private static void ghostFire(ItemStackRenderState item, int owner) {
        ((SurfaceAuras.Carrier) item).logos$aura(GHOST_FIRE, null, owner);
    }

    @Override
    public void addRenderData(DivineFox fox, Void relatedObject, R state, float partialTick) {
        super.addRenderData(fox, relatedObject, state, partialTick);
        // 只在刀拔出来（拿在手上）时画：入鞘的刀身在鞘里，火从鞘口冒出来不好看
        if (fox.wrapped() && fox.getMainHandItem().getItem() instanceof UchigatanaItem && !fox.isDeadOrDying()) {
            ItemStackRenderState item = new ItemStackRenderState();
            ItemStack katana = fox.getMainHandItem();
            Minecraft.getInstance().getItemModelResolver().updateForLiving(item, katana, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, fox);
            ghostFire(item, fox.getId());
            state.addGeckolibData(BLADE_FIRE, item);
            state.addGeckolibData(BLADE_BONE, "katana_" + DivineFox.KATANAS.get(fox.katanaVariant()) + "_blade");
        }
    }

    @Override
    public void adjustRenderPose(RenderPassInfo<R> info) {
        super.adjustRenderPose(info);
        String name = info.getGeckolibData(BLADE_BONE);
        if (info.getGeckolibData(BLADE_FIRE) == null || name == null) return;
        GeoBone bone = info.model().boneLookup().get().get(name);
        if (bone != null) info.addPerBoneRender(bone, this::submitBladeFire);
    }

    /**
     * 刀身上的狐火：在刀身骨骼的姿势里，把刀的立体物品模型（由刀网格的刀身平移 (8, 1, 8) 生成）对准刀网格，交给 Logos 画表面火焰。
     */
    private void submitBladeFire(RenderPassInfo<R> info, GeoBone bone, SubmitNodeCollector out) {
        ItemStackRenderState item = info.getGeckolibData(BLADE_FIRE);
        if (item == null || item.isEmpty()) return;
        var access = (ItemStackRenderStateAccessor) item;
        if (access.logos$activeLayerCount() == 0) return;
        var layer = access.logos$layers()[0];
        var surface = (LayerRenderStateAccessor) layer;
        var display = surface.logos$itemTransform();
        var local = new Matrix4f(surface.logos$localTransform());
        // 只画特效：临时去掉原版拿法，NO_TRANSFORM 仍会把物品原点平移 -0.5，用局部变换抵消
        layer.setItemTransform(ItemTransform.NO_TRANSFORM);
        layer.setLocalTransform(new Matrix4f().translation(.5f, .5f, .5f));
        PoseStack pose = info.poseStack();
        pose.pushPose();
        try {
            // 骨骼空间以格为单位：先换成刀网格的像素（× 1.6 / 16），再从物品模型的像素原点 (8, 1, 8) 换过来
            pose.scale(KATANA_SCALE / 16, KATANA_SCALE / 16, KATANA_SCALE / 16);
            pose.translate(-8, -1, -8);
            pose.scale(16, 16, 16);
            SurfaceAuras.submitItem((SurfaceAuras.Carrier) item, pose, out);
            bladeFires++;
        } finally {
            pose.popPose();
            layer.setItemTransform(display);
            layer.setLocalTransform(local);
        }
    }
}
