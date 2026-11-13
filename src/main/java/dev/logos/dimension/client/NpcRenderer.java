package dev.logos.dimension.client;

import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
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
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.npc.NpcAnimated;
import dev.logos.dimension.npc.NpcAnimations;
import dev.logos.dimension.npc.NpcPartsHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.Nullable;

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
    /** 双臂的共同父骨骼（肩线中点）；瞄准时随视线俯仰。 */
    public static final String ARMS_BONE = "Arm";
    /** 整个身体（不含根骨骼）；瞄准时整个人转向视线的水平方向。 */
    public static final String BODY_BONE = "AllBody";
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
    /** 本帧隐藏的骨骼，下标见 {@link NpcMesh#id}（外观里没选中的零件，见 {@link NpcPartsHolder}）。 */
    private static final DataTicket<boolean[]> HIDDEN_BONES = DataTicket.create("logos_dimension_npc_hidden_bones", boolean[].class);

    private NpcMesh mesh;

    public NpcRenderer(EntityRendererProvider.Context context, String asset) {
        this(context, LogosDimension.id(asset));
    }

    public NpcRenderer(EntityRendererProvider.Context context, Identifier asset) {
        this(context, new DefaultedEntityGeoModel<>(asset));
    }

    /** 自带模型对象的角色（例如灵猫按毛色换贴图）；模型的 {@code getTextureResource(null)} 须给出默认贴图。 */
    protected NpcRenderer(EntityRendererProvider.Context context, com.geckolib.model.GeoModel<T> model) {
        super(context, model);
        withScale(MODEL_SCALE);
        withRenderLayer(handLayer(context));
        // 坐在星辰花琴凳上演奏：画乐器、不画手里的（排在手持物品层之后）
        withRenderLayer(new dev.logos.dimension.client.music.NpcMusician.Layer<>(this));
        this.shadowRadius = 0.35f;
    }

    /** 手持物品的绘制层；角色可以换成自己的（例如神狐不画手里的刀，刀由模型骨骼画）。 */
    protected ItemInHandGeoLayer<T, Void, R> handLayer(EntityRendererProvider.Context context) {
        return new ItemInHandGeoLayer<>(context, this, RIGHT_HAND_BONE, LEFT_HAND_BONE);
    }

    /** 这个模型预先算好的网格；资源重载后模型换了就重建。 */
    NpcMesh mesh(BakedGeoModel model) {
        if (mesh == null || mesh.model != model) {
            // 贴图只在这里读一次，用来判断哪些方块透明；读不到就退回不跳过背面，只是慢一点。
            try (var texture = Minecraft.getInstance().getResourceManager().getResourceOrThrow(getGeoModel().getTextureResource(null)).open();
                 var image = NativeImage.read(texture)) {
                mesh = new NpcMesh(model, GLOW_PREFIX, image);
            } catch (IOException | RuntimeException error) {
                LogUtils.getLogger().warn("NPC 贴图读不到，不跳过背面", error);
                mesh = new NpcMesh(model, GLOW_PREFIX, null);
            }
        }
        return mesh;
    }

    /** 被实心方块完全挡住的不画，见 {@link NpcOcclusion}。 */
    @Override
    public boolean shouldRender(T entity, Frustum frustum, double cameraX, double cameraY, double cameraZ) {
        return super.shouldRender(entity, frustum, cameraX, cameraY, cameraZ) && !NpcOcclusion.hidden(entity, cameraX, cameraY, cameraZ);
    }

    /** 不看渲染状态取网格：NPC 的模型资源是固定的，不随状态变。 */
    NpcMesh defaultMesh() {
        var geo = getGeoModel();
        return mesh(geo.getBakedModel(geo.getModelResource(null)));
    }

    /** 有可换零件的 NPC：把这个外观（以及当前表情）要隐藏的骨骼交给渲染状态。外观不变时直接取缓存。 */
    @Override
    public void addRenderData(T animatable, Void relatedObject, R state, float partialTick) {
        super.addRenderData(animatable, relatedObject, state, partialTick);
        NpcAnimated npc = animatable instanceof NpcAnimated a ? a : null;
        if (animatable instanceof NpcPartsHolder holder) {
            var key = new NpcMesh.LookKey(holder.npcAppearance(), npc == null ? null : npc.npcExpression(), holder.npcHiddenExtras());
            var geo = getGeoModel();
            state.addGeckolibData(HIDDEN_BONES, mesh(geo.getBakedModel(geo.getModelResource(state))).hidden(key, holder.npcRace()));
        }
        state.addGeckolibData(AIMING, npc != null && npc.npcAiming());
        state.addGeckolibData(ANIMATED_DEATH, animatedDeath(animatable));
    }

    /** 隐藏的骨骼不算动画：一只神狐身上大多数零件都是隐藏的，它们的叠加层动画白算。 */
    @Override
    public void applyAnimationControllers(RenderPassInfo<R> info, BoneSnapshots snapshots) {
        boolean[] hidden = info.getOrDefaultGeckolibData(HIDDEN_BONES, null);
        if (hidden == null) {
            super.applyAnimationControllers(info, snapshots);
            return;
        }
        NpcMesh mesh = mesh(info.model());
        super.applyAnimationControllers(info, name -> {
            int id = mesh.id(name);
            return id >= 0 && hidden[id] ? Optional.empty() : snapshots.get(name);
        });
    }

    /** 走预先算好的网格，不走 GeckoLib 逐方块的绘制（见 {@link NpcMesh}）。 */
    @Override
    public void submitRenderTasks(RenderPassInfo<R> info, OrderedSubmitNodeCollector tasks, @Nullable RenderType type) {
        if (type == null || info.model().isMissingno()) {
            super.submitRenderTasks(info, tasks, type);
            return;
        }
        int light = info.packedLight(), overlay = info.packedOverlay(), color = info.renderColor();
        boolean[] hidden = info.getOrDefaultGeckolibData(HIDDEN_BONES, null);
        NpcMesh mesh = mesh(info.model());
        tasks.submitCustomGeometry(info.poseStack(), type, (pose, vertices) -> {
            PoseStack stack = info.poseStack();
            stack.pushPose();
            stack.last().set(pose);
            info.renderPosed(() -> mesh.render(info, stack, hidden, vertices, light, overlay, color));
            stack.popPose();
        });
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
        for (GeoBone bone : mesh(info.model()).glowBones()) info.addPerBoneRender(bone, this::submitGlow);
    }

    /**
     * 头部跟随视线：在动画姿势之上叠加俯仰与相对身体的偏航。
     * 瞄准时整个人（{@link #BODY_BONE}）转向视线的水平方向、头不再单独偏转，双臂（{@link #ARMS_BONE}）跟着俯仰，
     * 弓和施法的手指向目标；身体的侧转只来自动画本身（拉弓时上身侧转不超过 45°）。
     * 发光骨骼：常规绘制里跳过它自身的方块，改由 {@link #submitGlow} 以满亮度补画；被动画隐藏的保持隐藏。
     */
    @Override
    public void adjustModelBonesForRender(RenderPassInfo<R> info, BoneSnapshots snapshots) {
        super.adjustModelBonesForRender(info, snapshots);
        R state = info.renderState();
        // 原版俯仰向下为正，GeckoLib 骨骼绕 X 轴为正时朝前的部分向上抬，所以取反
        // 死亡动画自己摆头，不再叠加视线
        boolean aiming = info.getOrDefaultGeckolibData(AIMING, false);
        if (!info.getOrDefaultGeckolibData(ANIMATED_DEATH, false)) snapshots.ifPresent(HEAD_BONE, head -> {
            head.setRotX(head.getRotX() - state.xRot * Mth.DEG_TO_RAD);
            if (!aiming) head.setRotY(head.getRotY() + state.yRot * Mth.DEG_TO_RAD);
        });
        if (aiming) {
            snapshots.ifPresent(BODY_BONE, body -> body.setRotY(body.getRotY() + state.yRot * Mth.DEG_TO_RAD));
            snapshots.ifPresent(ARMS_BONE, arms -> arms.setRotX(arms.getRotX() - state.xRot * Mth.DEG_TO_RAD));
        }
        // 坐在星辰花琴凳上演奏：坐下、两臂够到手位（在动画和视线之后）
        dev.logos.dimension.client.music.NpcMusician.pose(info, snapshots);
        NpcMesh mesh = mesh(info.model());
        GeoBone[] glow = mesh.glowBones();
        if (glow.length == 0) return;
        boolean[] hidden = info.getOrDefaultGeckolibData(HIDDEN_BONES, null);
        Set<String> lit = new HashSet<>();
        for (GeoBone bone : glow) {
            // 在没选中的零件里的发光骨骼也不补画（补画不经过常规的子骨骼跳过）
            if (hidden != null && hidden[mesh.id(bone.name())]) continue;
            BoneSnapshot snapshot = snapshots.get(bone);
            if (snapshot == null || snapshot.isHidden()) continue;
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

}
