package dev.logos.dimension.client.companion;

import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.model.DefaultedEntityGeoModel;
import com.geckolib.renderer.GeoEntityRenderer;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.NpcRenderer;
import dev.logos.dimension.client.shiraume.FoxfireRenderer;
import dev.logos.dimension.client.shiraume.RyukaOnBone;
import dev.logos.dimension.companion.CompanionBlade;
import dev.logos.dimension.companion.CompanionOutfit;
import dev.logos.dimension.companion.Companions;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import dev.logos.dimension.companion.Katashiro;
import dev.logos.dimension.companion.ShiraumeCompanion;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Vector3f;

/** 白梅同伴与形代的渲染（docs/SHIRAUME_COMPANION.md）。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class CompanionClient {
    private CompanionClient() {}

    @SubscribeEvent
    static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Companions.SHIRAUME.get(), CompanionRenderer::new);
        event.registerEntityRenderer(Companions.KATASHIRO.get(), KatashiroRenderer::new);
    }

    /**
     * 白梅：NPC 的通用渲染。入场最初几刻不画（{@code misc} 控制器有 2 刻过渡，免得先看到原尺寸的她）；
     * 蜷着睡、跪坐、泡温泉时不叠加视线，头不跟着人转。
     * 流火画在 {@code Weapon} 骨骼上（同 Boss 的 {@link RyukaOnBone}，按她的个子缩小）：凝出来的 {@link #MANIFEST_TICKS} 刻里从柄往尖长出，
     * 散掉时倒过来缩回去。立契前后在动画上叠一层发抖（{@link #tremble}）；狐火灯是她身边绕着飘的一团淡粉狐火（同狐火弹的画法）。
     */
    public static class CompanionRenderer<R extends LivingEntityRenderState & GeoRenderState> extends NpcRenderer<ShiraumeCompanion, R> {
        /** 刀的样子：-2 不画，-1 完整，0–1 凝聚（或消散）的进度。 */
        private static final DataTicket<Float> SWORD = DataTicket.create("logos_dimension_companion_sword", Float.class);
        private static final DataTicket<Integer> ID = DataTicket.create("logos_dimension_companion_id", Integer.class);
        private static final DataTicket<ItemStackRenderState> FIRE = DataTicket.create("logos_dimension_companion_fire", ItemStackRenderState.class);
        /** 此刻的时间（刻）、立契前后发抖的幅度（0–1）、狐火灯有多亮（0–1）。 */
        private static final DataTicket<Float> CLOCK = DataTicket.create("logos_dimension_companion_clock", Float.class);
        private static final DataTicket<Float> TREMBLE = DataTicket.create("logos_dimension_companion_tremble", Float.class);
        private static final DataTicket<Float> LANTERN = DataTicket.create("logos_dimension_companion_lantern", Float.class);
        /** 当前装束。 */
        private static final DataTicket<CompanionOutfit> OUTFIT = DataTicket.create("logos_dimension_companion_outfit", CompanionOutfit.class);
        /** 每套装束下要隐藏的组（别的装束的 outfit_* 顶层组），按模型缓存（资源重载后模型换了就重算）。 */
        private BakedGeoModel outfitModel;
        private final Map<CompanionOutfit, List<String>> hiddenGroups = new EnumMap<>(CompanionOutfit.class);
        /** 网格单位 → 模型像素，与模型 tools/companion_ryuka.py 的 WEAPON_SCALE 相同。 */
        static final float SWORD_SCALE = .93f;
        static final int MANIFEST_TICKS = CompanionBlade.MANIFEST;
        /** 狐火灯是狐火弹的几分之一大、白芯淡到几成（淡粉为主，不是一团白光）；发抖时耳朵往后压多少度。 */
        static final float LANTERN_SIZE = .42f, LANTERN_CORE = .45f, EARS_BACK = 16;
        private static final float TAU = (float) (Math.PI * 2);

        public CompanionRenderer(EntityRendererProvider.Context context) {
            super(context, "shiraume_companion");
        }

        @Override
        public boolean shouldRender(ShiraumeCompanion entity, Frustum frustum, double x, double y, double z) {
            return !entity.hiddenForSummon() && super.shouldRender(entity, frustum, x, y, z);
        }

        @Override
        public void addRenderData(ShiraumeCompanion animatable, Void relatedObject, R state, float partialTick) {
            super.addRenderData(animatable, relatedObject, state, partialTick);
            if (animatable.curled()) {
                state.xRot = 0;
                state.yRot = 0;
            }
            float sword = sword(animatable, partialTick);
            state.addGeckolibData(SWORD, sword);
            state.addGeckolibData(ID, animatable.getId());
            if (sword == -1 && !animatable.isDeadOrDying()) state.addGeckolibData(FIRE, RyukaOnBone.fire(animatable));
            state.addGeckolibData(CLOCK, animatable.tickCount + partialTick);
            state.addGeckolibData(TREMBLE, animatable.tremble(partialTick));
            state.addGeckolibData(LANTERN, animatable.lanternGlow(partialTick));
            state.addGeckolibData(OUTFIT, animatable.outfit());
        }

        @Override
        public void adjustModelBonesForRender(RenderPassInfo<R> info, BoneSnapshots snapshots) {
            super.adjustModelBonesForRender(info, snapshots);
            float k = info.getOrDefaultGeckolibData(TREMBLE, 0f);
            if (k > 0) tremble(snapshots, k, info.getOrDefaultGeckolibData(CLOCK, 0f) / 20);
            // 别的装束的组连同子骨骼都不画
            for (String group : hidden(info.model(), info.getOrDefaultGeckolibData(OUTFIT, CompanionOutfit.MIKO))) {
                snapshots.ifPresent(group, s -> {
                    s.skipRender(true);
                    s.skipChildrenRender(true);
                });
            }
        }

        /** 这套装束下要隐藏的组：名字是别的装束的 outfit_* 前缀、父骨骼不是 outfit_* 的那些（子树一起隐藏）。 */
        private List<String> hidden(BakedGeoModel model, CompanionOutfit outfit) {
            if (model != outfitModel) {
                outfitModel = model;
                hiddenGroups.clear();
            }
            return hiddenGroups.computeIfAbsent(outfit, o -> {
                List<String> out = new ArrayList<>();
                for (GeoBone bone : model.boneLookup().get().values()) {
                    boolean top = bone.parent() == null || !bone.parent().name().startsWith(CompanionOutfit.PREFIX);
                    if (top && o.hides(bone.name())) out.add(bone.name());
                }
                return List.copyOf(out);
            });
        }

        /**
         * 发抖（刚被打败，跪坐着等人、说话的时候）：肩背、头、两臂细细地颤，几种频率叠在一起，不像机器振动；耳朵往后压着，跟着颤。
         * {@code k} 是幅度（0–1），{@code s} 是时间（秒）。
         */
        static void tremble(BoneSnapshots bones, float k, float s) {
            float a = k * Mth.DEG_TO_RAD;
            float back = Mth.sin(s * TAU * 9.1f) * .7f + Mth.sin(s * TAU * 13.3f + 1.7f) * .3f;
            bones.ifPresent("UpBody", b -> {
                b.setRotX(b.getRotX() + a * 1.6f * back);
                b.setRotZ(b.getRotZ() + a * .9f * Mth.sin(s * TAU * 7.7f + .4f));
            });
            bones.ifPresent(HEAD_BONE, b -> {
                b.setRotX(b.getRotX() + a * .9f * Mth.sin(s * TAU * 11.9f + 2.3f));
                b.setRotZ(b.getRotZ() + a * .8f * Mth.sin(s * TAU * 8.3f + 1.1f));
            });
            bones.ifPresent("LeftArm", b -> b.setRotZ(b.getRotZ() + a * 1.4f * Mth.sin(s * TAU * 10.7f)));
            bones.ifPresent("RightArm", b -> b.setRotZ(b.getRotZ() + a * 1.4f * Mth.sin(s * TAU * 10.7f + 2.6f)));
            bones.ifPresent("Left_ear", b -> b.setRotX(b.getRotX() + a * (EARS_BACK + 2.5f * Mth.sin(s * TAU * 12.1f))));
            bones.ifPresent("Right_ear", b -> b.setRotX(b.getRotX() + a * (EARS_BACK + 2.5f * Mth.sin(s * TAU * 12.1f + 1.9f))));
        }

        /** 狐火灯：她身边绕着飘的一团淡粉狐火，亮起、熄灭时淡入淡出，像火一样跳动。 */
        @Override
        public void submit(R state, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
            super.submit(state, pose, out, camera);
            float glow = state.getOrDefaultGeckolibData(LANTERN, 0f);
            if (glow <= .01f) return;
            float age = state.getOrDefaultGeckolibData(CLOCK, 0f);
            Vec3 at = ShiraumeCompanion.lanternOffset(age);
            float flicker = .86f + .14f * Mth.sin(age * 1.7f) * Mth.sin(age * .63f + 1.3f);
            FoxfireRenderer.flame(pose, out, VfxParts.View.of(camera), at, new Vec3(state.x, state.y, state.z).add(at), age,
                    LANTERN_SIZE * (.6f + .4f * glow), age * .12f, glow * flicker, LANTERN_CORE);
        }

        static float sword(ShiraumeCompanion c, float partialTick) {
            float age = c.level().getGameTime() - c.bladeChangedAt() + partialTick;
            if (c.bladeShown()) return age >= MANIFEST_TICKS ? -1 : Math.max(0, age / MANIFEST_TICKS);
            return age >= MANIFEST_TICKS ? -2 : 1 - Math.max(0, age / MANIFEST_TICKS);
        }

        @Override
        public void adjustRenderPose(RenderPassInfo<R> info) {
            super.adjustRenderPose(info);
            GeoBone weapon = info.model().boneLookup().get().get("Weapon");
            if (weapon != null && info.getOrDefaultGeckolibData(SWORD, -2f) > -2) info.addPerBoneRender(weapon, this::submitSword);
        }

        private void submitSword(RenderPassInfo<R> info, GeoBone bone, SubmitNodeCollector out) {
            float sword = info.getOrDefaultGeckolibData(SWORD, -2f);
            RyukaOnBone.submit(info.poseStack(), out, info.packedLight(), new Vector3f(), sword >= 0 ? sword : -1,
                    info.getGeckolibData(FIRE), "companion" + info.getOrDefaultGeckolibData(ID, 0), SWORD_SCALE);
        }
    }

    /** 形代：和她同样 2 倍制作、缩放 0.5，透明像素剪出轮廓，正反两面都画。 */
    public static class KatashiroRenderer<R extends EntityRenderState & GeoRenderState> extends GeoEntityRenderer<Katashiro, R> {
        public KatashiroRenderer(EntityRendererProvider.Context context) {
            super(context, new DefaultedEntityGeoModel<>(LogosDimension.id("shiraume_katashiro")));
            withScale(NpcRenderer.MODEL_SCALE);
            this.shadowRadius = 0;
        }

        @Override
        public RenderType getRenderType(R state, Identifier texture) {
            return RenderTypes.entityCutout(texture);
        }
    }
}
