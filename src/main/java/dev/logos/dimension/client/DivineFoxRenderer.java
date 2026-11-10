package dev.logos.dimension.client;

import com.geckolib.cache.model.GeoBone;
import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.builtin.BlockAndItemGeoLayer;
import com.geckolib.renderer.layer.builtin.ItemInHandGeoLayer;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.SurfaceAuras;
import dev.logos.dimension.item.UchigatanaItem;
import dev.logos.dimension.item.YumiItem;
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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

/**
 * 神狐的渲染：刀由模型里的 {@code Weapon} / {@code Sheath} 骨骼画（打刀动作直接驱动），和弓由 {@code Yumi} 骨骼下的弓画，
 * 所以手里的刀和和弓不再按物品画一遍；拉和弓时上半身跟着视线俯仰；
 * 缠着狐火缠绕时，刀身与手里的弓燃起幽蓝的狐火——复用 Logos 的物品表面火焰（{@code /alchemy aura fire}），色相转到鬼火的蓝，
 * 并换掉 Logos 按奥秘元素画的缠绕外观。挥刀时刀身扫过的地方留下刀光（{@link FoxBladeTrail}，和弓没有）。
 */
public class DivineFoxRenderer<R extends LivingEntityRenderState & GeoRenderState> extends NpcRenderer<DivineFox, R> {
    /** 火焰色相：原本的橙红转半圈，成为幽蓝的鬼火。 */
    public static final float GHOST_HUE = .5f;
    private static final SurfaceAura GHOST_FIRE = new SurfaceAura(SurfaceAura.Style.FIRE, GHOST_HUE);
    /** 刀网格的单位是像素，神狐的刀按 1.6 倍挂（tools/divine_fox_assets.py 的 KATANA_SCALE）。 */
    private static final float KATANA_SCALE = 1.6f;
    /** 弓网格按 2.3 倍挂（tools/divine_fox_assets.py 的 YUMI_SCALE）。 */
    private static final float YUMI_SCALE = 2.3f;
    /** 拉和弓时上半身最多跟着视线俯仰多少度。 */
    private static final float MAX_AIM = 50;
    /** 这只神狐手里是和弓。 */
    private static final DataTicket<Boolean> HOLDS_YUMI = DataTicket.create("logos_dimension_fox_holds_yumi", Boolean.class);
    /** 本帧要在刀身上画的狐火（物品渲染状态，已换成狐火），没有为空。 */
    private static final DataTicket<ItemStackRenderState> BLADE_FIRE = DataTicket.create("logos_dimension_fox_blade_fire", ItemStackRenderState.class);
    /** 这只神狐的刀装（刀身骨骼 {@code katana_<配色>_blade}）。 */
    private static final DataTicket<String> BLADE_BONE = DataTicket.create("logos_dimension_fox_blade_bone", String.class);
    /** 本帧刀身的刀光（刀拿在手上时才有），见 {@link FoxBladeTrail}。 */
    private static final DataTicket<FoxBladeTrail.Frame> BLADE_TRAIL = DataTicket.create("logos_dimension_fox_blade_trail", FoxBladeTrail.Frame.class);
    /** 刀身骨骼的名字（按刀装下标），每帧用，不拼字符串。 */
    private static final String[] BLADE_BONES = DivineFox.KATANAS.stream().map(v -> "katana_" + v + "_blade").toArray(String[]::new);
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
                if (stack.getItem() instanceof UchigatanaItem || stack.getItem() instanceof YumiItem) return null;
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
        ItemStack held = fox.getMainHandItem();
        boolean yumi = held.getItem() instanceof YumiItem;
        state.addGeckolibData(HOLDS_YUMI, yumi);
        boolean katana = held.getItem() instanceof UchigatanaItem && !fox.isDeadOrDying();
        // 刀光：刀拿在手上才送点（入鞘的刀、和弓都没有）
        if (katana) {
            state.addGeckolibData(BLADE_BONE, BLADE_BONES[fox.katanaVariant()]);
            FoxBladeTrail.Frame trail = FoxBladeTrail.of(fox, partialTick, KATANA_SCALE / 16);
            if (trail != null) state.addGeckolibData(BLADE_TRAIL, trail);
        }
        // 只在刀拔出来（拿在手上）时画：入鞘的刀身在鞘里，火从鞘口冒出来不好看
        if (fox.wrapped() && (yumi || katana) && !fox.isDeadOrDying()) {
            ItemStackRenderState item = new ItemStackRenderState();
            Minecraft.getInstance().getItemModelResolver().updateForLiving(item, held, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, fox);
            ghostFire(item, fox.getId());
            state.addGeckolibData(BLADE_FIRE, item);
            if (yumi) state.addGeckolibData(BLADE_BONE, DivineFox.YUMI_BONE);
        }
    }

    @Override
    public void adjustRenderPose(RenderPassInfo<R> info) {
        super.adjustRenderPose(info);
        String name = info.getGeckolibData(BLADE_BONE);
        if (name == null) return;
        GeoBone bone = info.model().boneLookup().get().get(name);
        if (bone == null) return;
        if (info.getGeckolibData(BLADE_FIRE) != null) info.addPerBoneRender(bone, this::submitBladeFire);
        if (info.getGeckolibData(BLADE_TRAIL) != null) info.addPerBoneRender(bone, DivineFoxRenderer::submitBladeTrail);
    }

    /** 刀光：刀身骨骼此刻的姿势把骨骼坐标换成相对镜头的世界坐标，刀身两端已由 {@link FoxBladeTrail} 换算到骨骼里。 */
    private static <R extends GeoRenderState> void submitBladeTrail(RenderPassInfo<R> info, GeoBone bone, SubmitNodeCollector out) {
        FoxBladeTrail.Frame trail = info.getGeckolibData(BLADE_TRAIL);
        if (trail != null) trail.feed(info.poseStack().last().pose());
    }

    /**
     * 拉和弓时侧身对靶（动作里 Root 转身，靶在 -X 一侧）：上半身连同头、双臂和弓绕腰按视线俯仰，箭跟着视线。
     * 随转身淡入淡出；头已经跟着上半身转了，不再叠视线的俯仰。
     */
    @Override
    public void adjustModelBonesForRender(RenderPassInfo<R> info, BoneSnapshots snapshots) {
        super.adjustModelBonesForRender(info, snapshots);
        if (!info.getOrDefaultGeckolibData(HOLDS_YUMI, false)) return;
        float[] weight = {0};
        snapshots.ifPresent("Root", root -> weight[0] = Math.min(1, Math.abs(root.getRotY()) / Mth.HALF_PI));
        if (weight[0] == 0) return;
        float pitch = Mth.clamp(info.renderState().xRot, -MAX_AIM, MAX_AIM) * weight[0] * Mth.DEG_TO_RAD;
        snapshots.ifPresent("UpBody", up -> up.setRotZ(up.getRotZ() + pitch));
        snapshots.ifPresent(HEAD_BONE, head -> head.setRotX(head.getRotX() + pitch));
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
            // 骨骼空间以格为单位：先换成网格的像素（刀 × 1.6 / 16、弓 × 2.3 / 16），再从物品模型的像素原点换过来
            // （刀 (8, 1, 8)，弓 (8, 8, 8)，tools/import_yumi.py）。弓的物品模型是不拉弦的弓，拉满时弓梢与火略有错位
            boolean yumi = DivineFox.YUMI_BONE.equals(bone.name());
            float scale = (yumi ? YUMI_SCALE : KATANA_SCALE) / 16;
            pose.scale(scale, scale, scale);
            pose.translate(-8, yumi ? -8 : -1, -8);
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
