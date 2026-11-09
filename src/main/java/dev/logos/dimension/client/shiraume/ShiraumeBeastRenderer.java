package dev.logos.dimension.client.shiraume;

import com.geckolib.cache.model.GeoBone;
import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import dev.logos.dimension.client.NpcRenderer;
import dev.logos.dimension.client.dissolve.Dissolves;
import dev.logos.dimension.shiraume.ShiraumeBeast;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * 白梅狐形的渲染：流火横叼在嘴里（{@code Weapon} 骨骼在 Head 下、上下颌之间，刀尖指向右边），
 * 出招前双眼红光（眼睛是 {@code face_left_eye} / {@code face_right_eye} 两片）。狂暴（八分之一血以下）时眼光更大。
 */
public class ShiraumeBeastRenderer<R extends LivingEntityRenderState & GeoRenderState> extends NpcRenderer<ShiraumeBeast, R> {
    private static final DataTicket<Float> EYES = DataTicket.create("logos_dimension_shiraume_beast_eyes", Float.class);
    private static final DataTicket<Boolean> RAGE = DataTicket.create("logos_dimension_shiraume_beast_rage", Boolean.class);
    private static final DataTicket<Integer> ID = DataTicket.create("logos_dimension_shiraume_beast_id", Integer.class);
    private static final DataTicket<ItemStackRenderState> FIRE = DataTicket.create("logos_dimension_shiraume_beast_fire", ItemStackRenderState.class);
    /** 咬在刀镡下一点的柄上：网格原点相对 Weapon 枢轴（上下颌之间，tools/shiraume_assets.py 的 BEAST_MOUTH）的偏移，网格单位。 */
    private static final Vector3f BITE = new Vector3f(0, 1.4f, 0);
    /** 光在眼睛骨骼枢轴前面一点（模型像素，相对枢轴）。 */
    private static final Vector3f LEFT_EYE = new Vector3f(0, 0, -.7f), RIGHT_EYE = new Vector3f(0, 0, -.7f);

    public ShiraumeBeastRenderer(EntityRendererProvider.Context context) {
        super(context, "shiraume_beast");
        this.shadowRadius = 1.6f;
    }

    /** 狐形没有手，也不拿东西：手持物的层指向嘴前的挂点（不然 GeckoLib 每帧报找不到手部骨骼），什么都不画。 */
    @Override
    protected com.geckolib.renderer.layer.builtin.ItemInHandGeoLayer<ShiraumeBeast, Void, R> handLayer(EntityRendererProvider.Context context) {
        return new com.geckolib.renderer.layer.builtin.ItemInHandGeoLayer<>(context, this, "MouthLocator", "MouthLocator") {
            @Override
            protected com.geckolib.renderer.layer.builtin.BlockAndItemGeoLayer.RenderData renderDataForHand(String bone, net.minecraft.world.entity.HumanoidArm arm,
                                                                                                      ShiraumeBeast beast, R state) {
                return null;
            }
        };
    }

    /** 封印之间里很暗（杀生石和底座的灯都拆掉了）：Boss 最低按方块光 {@value #MIN_LIGHT} 画，看得清招式。 */
    static final int MIN_LIGHT = 11;

    @Override
    protected int getBlockLightLevel(ShiraumeBeast boss, net.minecraft.core.BlockPos pos) {
        return Math.max(MIN_LIGHT, super.getBlockLightLevel(boss, pos));
    }

    @Override
    public void addRenderData(ShiraumeBeast beast, Void relatedObject, R state, float partialTick) {
        super.addRenderData(beast, relatedObject, state, partialTick);
        state.addGeckolibData(EYES, ShiraumeClient.eyes(beast.getId(), partialTick));
        state.addGeckolibData(RAGE, beast.enraged());
        state.addGeckolibData(ID, beast.getId());
        if (!beast.isDeadOrDying()) state.addGeckolibData(FIRE, RyukaOnBone.fire(beast));
    }

    @Override
    public void adjustRenderPose(RenderPassInfo<R> info) {
        super.adjustRenderPose(info);
        var bones = info.model().boneLookup().get();
        GeoBone weapon = bones.get("Weapon");
        if (weapon != null) info.addPerBoneRender(weapon, this::submitSword);
        if (info.getOrDefaultGeckolibData(EYES, -1f) >= 0) {
            GeoBone left = bones.get("face_left_eye"), right = bones.get("face_right_eye");
            if (left != null) info.addPerBoneRender(left, (i, b, out) -> eye(i, LEFT_EYE));
            if (right != null) info.addPerBoneRender(right, (i, b, out) -> eye(i, RIGHT_EYE));
        }
    }

    private void submitSword(RenderPassInfo<R> info, GeoBone bone, SubmitNodeCollector out) {
        RyukaOnBone.submit(info.poseStack(), out, info.packedLight(), BITE, -1, info.getGeckolibData(FIRE),
                "shiraume_beast" + info.getOrDefaultGeckolibData(ID, 0));
    }

    private void eye(RenderPassInfo<R> info, Vector3f at) {
        if (Dissolves.shadowPass()) return;
        float age = info.getOrDefaultGeckolibData(EYES, -1f);
        if (age < 0) return;
        Vector3f p = info.poseStack().last().pose().transformPosition(at.x / 16, at.y / 16, at.z / 16, new Vector3f());
        ShiraumeRenderer.eyeGlint(new Vec3(p.x, p.y, p.z), age, info.getOrDefaultGeckolibData(RAGE, false) ? 1.6f : 1.25f);
    }
}
