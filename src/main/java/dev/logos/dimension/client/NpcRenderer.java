package dev.logos.dimension.client;

import java.util.HashSet;
import java.util.Set;

import com.geckolib.animatable.GeoEntity;
import com.geckolib.animation.state.BoneSnapshot;
import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.cache.model.cuboid.CuboidGeoBone;
import com.geckolib.cache.model.cuboid.GeoCube;
import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.model.DefaultedEntityGeoModel;
import com.geckolib.renderer.GeoEntityRenderer;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.builtin.ItemInHandGeoLayer;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.npc.NpcAnimated;
import dev.logos.dimension.npc.NpcAnimations;
import dev.logos.dimension.npc.NpcPartsHolder;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;

/**
 * 附属模组 NPC 的通用 GeckoLib 渲染器，即车万女仆 / YSM 的渲染风格（docs/NPC_MODELS.md 第 5 节）：
 * alpha 裁切、不剔除背面，原版实体光照与世界光照，不用自定义着色器。
 *
 * <p>与具体角色无关：按资源名读取 {@code geckolib/models/entity/<名>.geo.json}、
 * {@code textures/entity/<名>.png} 与 {@code geckolib/animations/entity/<名>.animation.json}。
 * 模型须使用车万女仆骨架命名；本类只用到 {@link #HEAD_BONE}、两个手部挂点与发光骨骼前缀 {@link #GLOW_PREFIX}。
 */
public class NpcRenderer<T extends Mob & GeoEntity, R extends LivingEntityRenderState & GeoRenderState>
        extends GeoEntityRenderer<T, R> {
    /** NPC 按常规女仆模型的 2 倍制作，游戏内整体缩放 0.5。 */
    public static final float MODEL_SCALE = 0.5f;
    /** 随视线转动的头部骨骼。 */
    public static final String HEAD_BONE = "Head";
    /** 双臂的共同父骨骼（肩线中点）；瞄准时随视线转动。 */
    public static final String ARMS_BONE = "Arm";
    /** 手持物品的挂点骨骼。 */
    public static final String RIGHT_HAND_BONE = "RightHandLocator";
    public static final String LEFT_HAND_BONE = "LeftHandLocator";
    /**
     * 发光骨骼的名称前缀，沿用 YSM 与车万女仆的约定：名字以它开头的骨骼，自身方块按满亮度绘制，
     * 子骨骼不受影响。颜色仍来自贴图，只是不随环境变暗。
     */
    public static final String GLOW_PREFIX = "ysmGlow";
    /** 本帧需要以满亮度补画的发光骨骼（动画隐藏的不在其中）。 */
    @SuppressWarnings("unchecked")
    private static final DataTicket<Set<String>> GLOWING =
            DataTicket.create("logos_dimension_npc_glowing", (Class<Set<String>>) (Class<?>) Set.class);
    /** 本帧是否在播放自带的死亡动画（动画文件里有 {@code death} 且 NPC 正在死亡）。 */
    private static final DataTicket<Boolean> ANIMATED_DEATH = DataTicket.create("logos_dimension_npc_animated_death", Boolean.class);
    /** 本帧是否在瞄准（{@link NpcAnimated#npcAiming()}）。 */
    private static final DataTicket<Boolean> AIMING = DataTicket.create("logos_dimension_npc_aiming", Boolean.class);
    /** 本帧要整组隐藏的零件组（外观里没选中的零件，见 {@link NpcPartsHolder}）。 */
    @SuppressWarnings("unchecked")
    private static final DataTicket<Set<String>> HIDDEN_PARTS =
            DataTicket.create("logos_dimension_npc_hidden_parts", (Class<Set<String>>) (Class<?>) Set.class);

    public NpcRenderer(EntityRendererProvider.Context context, String asset) {
        this(context, LogosDimension.id(asset));
    }

    public NpcRenderer(EntityRendererProvider.Context context, Identifier asset) {
        super(context, new DefaultedEntityGeoModel<>(asset));
        withScale(MODEL_SCALE);
        withRenderLayer(new ItemInHandGeoLayer<>(context, this, RIGHT_HAND_BONE, LEFT_HAND_BONE));
        this.shadowRadius = 0.35f;
    }

    /** 有可换零件的 NPC：把没选中的零件组（以及当前表情）交给渲染状态。 */
    @Override
    public void addRenderData(T animatable, Void relatedObject, R state, float partialTick) {
        super.addRenderData(animatable, relatedObject, state, partialTick);
        NpcAnimated npc = animatable instanceof NpcAnimated a ? a : null;
        if (animatable instanceof NpcPartsHolder holder)
            state.addGeckolibData(HIDDEN_PARTS, holder.npcRace().hiddenGroups(holder.npcAppearance(), npc == null ? null : npc.npcExpression()));
        state.addGeckolibData(AIMING, npc != null && npc.npcAiming());
        state.addGeckolibData(ANIMATED_DEATH, animatedDeath(animatable));
    }

    private static boolean animatedDeath(Mob mob) {
        return mob.isDeadOrDying() && mob instanceof NpcAnimated npc
                && NpcAnimations.available(npc.npcAnimationAsset()).contains(NpcAnimations.DEATH);
    }

    /** 有自己的死亡动画时不按原版向侧面倒下，姿势全交给动画。 */
    @Override
    protected float getDeathMaxRotation(GeoRenderState state) {
        return state.getOrDefaultGeckolibData(ANIMATED_DEATH, false) ? 0 : super.getDeathMaxRotation(state);
    }

    /** 有自己的死亡动画时不染红（原版受伤与死亡共用的红色覆盖）；平时受伤照常变红。 */
    @Override
    public int getPackedOverlay(T animatable, Void relatedObject, float u, float partialTick) {
        return animatedDeath(animatable) ? OverlayTexture.NO_OVERLAY : super.getPackedOverlay(animatable, relatedObject, u, partialTick);
    }

    /** 26.1 的 {@code entityCutout} 即旧版的 entityCutoutNoCull：薄发片与裙摆内侧两面可见。 */
    @Override
    public RenderType getRenderType(R state, Identifier texture) {
        return RenderTypes.entityCutout(texture);
    }

    /** 为每根发光骨骼登记补画任务；是否真的补画由本帧的骨骼状态决定。 */
    @Override
    public void adjustRenderPose(RenderPassInfo<R> info) {
        super.adjustRenderPose(info);
        for (GeoBone bone : glowBones(info.model())) info.addPerBoneRender(bone, this::submitGlow);
    }

    /**
     * 头部跟随视线：在动画姿势之上叠加俯仰与相对身体的偏航；瞄准时双臂（{@link #ARMS_BONE}）也一样，弓和施法的手指向目标。
     * 发光骨骼：常规绘制里跳过它自身的方块，改由 {@link #submitGlow} 以满亮度补画；被动画隐藏的保持隐藏。
     */
    @Override
    public void adjustModelBonesForRender(RenderPassInfo<R> info, BoneSnapshots snapshots) {
        super.adjustModelBonesForRender(info, snapshots);
        R state = info.renderState();
        // 原版俯仰向下为正，GeckoLib 骨骼绕 X 轴为正时朝前的部分向上抬，所以取反
        // 死亡动画自己摆头，不再叠加视线
        if (!info.getOrDefaultGeckolibData(ANIMATED_DEATH, false)) snapshots.ifPresent(HEAD_BONE, head -> {
            head.setRotX(head.getRotX() - state.xRot * Mth.DEG_TO_RAD);
            head.setRotY(head.getRotY() + state.yRot * Mth.DEG_TO_RAD);
        });
        if (info.getOrDefaultGeckolibData(AIMING, false)) snapshots.ifPresent(ARMS_BONE, arms -> {
            arms.setRotX(arms.getRotX() - state.xRot * Mth.DEG_TO_RAD);
            arms.setRotY(arms.getRotY() + state.yRot * Mth.DEG_TO_RAD);
        });
        // 没选中的零件：整组连同子骨骼都不画
        Set<String> hidden = info.getOrDefaultGeckolibData(HIDDEN_PARTS, Set.of());
        for (String group : hidden) snapshots.ifPresent(group, s -> s.skipRender(true).skipChildrenRender(true));
        Set<String> lit = new HashSet<>();
        for (GeoBone bone : glowBones(info.model())) {
            BoneSnapshot snapshot = snapshots.get(bone);
            if (snapshot == null || snapshot.isHidden() || insideHidden(bone, hidden)) continue;
            snapshot.skipRender(true);
            lit.add(bone.name());
        }
        state.addGeckolibData(GLOWING, lit);
    }

    /** 以满亮度（方块光与天空光均为 15）绘制发光骨骼自身的方块，渲染类型与贴图同常规绘制。 */
    private void submitGlow(RenderPassInfo<R> info, GeoBone bone, SubmitNodeCollector out) {
        if (!(bone instanceof CuboidGeoBone cuboid) || !info.getOrDefaultGeckolibData(GLOWING, Set.of()).contains(bone.name())) return;
        RenderType type = getRenderType(info.renderState(), getGeoModel().getTextureResource(info.renderState()));
        int overlay = info.packedOverlay(), color = info.renderColor();
        out.submitCustomGeometry(info.poseStack(), type, (pose, vertices) -> {
            PoseStack ps = new PoseStack();
            ps.last().set(pose);
            cuboid.translateAwayFromPivotPoint(ps);
            for (GeoCube cube : cuboid.cubes) cube.render(ps, vertices, LightCoordsUtil.FULL_BRIGHT, overlay, color);
        });
    }

    /** 发光骨骼在被隐藏的零件组里时也不补画（补画不经过常规的子骨骼跳过）。 */
    private static boolean insideHidden(GeoBone bone, Set<String> hidden) {
        for (GeoBone b = bone; b != null; b = b.parent()) if (hidden.contains(b.name())) return true;
        return false;
    }

    private static Iterable<GeoBone> glowBones(BakedGeoModel model) {
        return model.boneLookup().get().values().stream().filter(b -> b.name().startsWith(GLOW_PREFIX)).toList();
    }
}
