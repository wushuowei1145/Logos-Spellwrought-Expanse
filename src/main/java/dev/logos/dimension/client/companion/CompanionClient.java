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
import dev.logos.dimension.companion.ElfPrincessCompanion;
import dev.logos.dimension.companion.Katashiro;
import dev.logos.dimension.companion.KohakuCompanion;
import dev.logos.dimension.companion.KohakuOutfit;
import dev.logos.dimension.companion.LacertaCompanion;
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
        event.registerEntityRenderer(Companions.ELF_PRINCESS.get(), PrincessRenderer::new);
        event.registerEntityRenderer(Companions.KOHAKU.get(), KohakuRenderer::new);
        event.registerEntityRenderer(Companions.LACERTA.get(), LacertaRenderer::new);
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

    /**
     * 精灵公主（docs/ELF_PRINCESS_COMPANION.md）：NPC 的通用渲染（手里的世界树之弓画在手部挂点上，拉弓时两臂跟着视线抬）。
     * 入场最初几刻不画；睡着、撤退时不叠加视线。入场的荷花苞（{@code SummonBud}）只在入场、撤退时画：平时它在动画里已经缩到 0，
     * 这里连带子骨骼一起跳过，省下那几十块方块。
     */
    public static class PrincessRenderer<R extends LivingEntityRenderState & GeoRenderState> extends NpcRenderer<ElfPrincessCompanion, R> {
        private static final DataTicket<String> MISC = DataTicket.create("logos_dimension_princess_misc", String.class);

        public PrincessRenderer(EntityRendererProvider.Context context) {
            super(context, "elf_princess_companion");
        }

        @Override
        public boolean shouldRender(ElfPrincessCompanion entity, Frustum frustum, double x, double y, double z) {
            return !entity.hiddenForSummon() && super.shouldRender(entity, frustum, x, y, z);
        }

        @Override
        public void addRenderData(ElfPrincessCompanion animatable, Void relatedObject, R state, float partialTick) {
            super.addRenderData(animatable, relatedObject, state, partialTick);
            if (animatable.curled()) {
                state.xRot = 0;
                state.yRot = 0;
            }
            String misc = animatable.npcMiscAnimation();
            state.addGeckolibData(MISC, misc == null ? "" : misc);
        }

        @Override
        public void adjustModelBonesForRender(RenderPassInfo<R> info, BoneSnapshots snapshots) {
            super.adjustModelBonesForRender(info, snapshots);
            String misc = info.getOrDefaultGeckolibData(MISC, "");
            if (!"summon".equals(misc) && !"withdraw".equals(misc)) {
                snapshots.ifPresent("SummonBud", s -> {
                    s.skipRender(true);
                    s.skipChildrenRender(true);
                });
            }
        }
    }

    /**
     * 琥珀（docs/KOHAKU_COMPANION.md）：NPC 的通用渲染，长杖画在手部挂点上。法阵还在转开时不画（她还没出来）；睡着、倒下时不叠加视线。
     * 模型里有四套装束（{@code outfit_<id>_*}），按她穿的那一套（{@link KohakuOutfit}）把别的几套连同子骨骼都不画。
     */
    public static class KohakuRenderer<R extends LivingEntityRenderState & GeoRenderState> extends NpcRenderer<KohakuCompanion, R> {
        /** 她穿哪一套；每套要隐藏的组（别的几套的 outfit_* 顶层组），按模型缓存（资源重载后模型换了就重算）。 */
        private static final DataTicket<KohakuOutfit> OUTFIT = DataTicket.create("logos_dimension_kohaku_outfit", KohakuOutfit.class);
        private BakedGeoModel outfitModel;
        private final Map<KohakuOutfit, List<String>> hidden = new EnumMap<>(KohakuOutfit.class);

        public KohakuRenderer(EntityRendererProvider.Context context) {
            super(context, "kohaku_companion");
        }

        /** 她这一帧藏着没有（法阵还在转开）、炼成阵转开了多少刻（-1 没有）、一共多久、是不是来卷走她的；此刻的时钟。 */
        private static final DataTicket<Boolean> HIDDEN = DataTicket.create("logos_dimension_kohaku_hidden", Boolean.class);
        private static final DataTicket<Float> CIRCLE = DataTicket.create("logos_dimension_kohaku_circle", Float.class);
        private static final DataTicket<Integer> CIRCLE_LENGTH = DataTicket.create("logos_dimension_kohaku_circle_length", Integer.class);
        private static final DataTicket<Boolean> WITHDRAW = DataTicket.create("logos_dimension_kohaku_withdraw", Boolean.class);
        private static final DataTicket<Float> CLOCK = DataTicket.create("logos_dimension_kohaku_clock", Float.class);
        /** 凝辉的样子（{@link VesperglassOnBone#state}，-1 没有）、她的实体 ID（凝聚、消散的随机种子）。 */
        private static final DataTicket<Float> SWORD = DataTicket.create("logos_dimension_kohaku_sword", Float.class);
        private static final DataTicket<Integer> ID = DataTicket.create("logos_dimension_kohaku_id", Integer.class);
        /** 伞撑开了多少（{@link KohakuUmbrella#openness}，0 没有）、是不是大伞。 */
        private static final DataTicket<Float> UMBRELLA = DataTicket.create("logos_dimension_kohaku_umbrella", Float.class);
        private static final DataTicket<Boolean> UMBRELLA_WIDE = DataTicket.create("logos_dimension_kohaku_umbrella_wide", Boolean.class);

        @Override
        public boolean shouldRender(KohakuCompanion entity, Frustum frustum, double x, double y, double z) {
            // 法阵还在转开时她不画，阵照样要画
            return entity.circleAge(0) >= 0 || !entity.hiddenForSummon() && super.shouldRender(entity, frustum, x, y, z);
        }

        @Override
        public void addRenderData(KohakuCompanion animatable, Void relatedObject, R state, float partialTick) {
            super.addRenderData(animatable, relatedObject, state, partialTick);
            if (animatable.curled()) {
                state.xRot = 0;
                state.yRot = 0;
            }
            state.addGeckolibData(HIDDEN, animatable.hiddenForSummon());
            state.addGeckolibData(CIRCLE, animatable.circleAge(partialTick));
            state.addGeckolibData(CIRCLE_LENGTH, animatable.circleLength());
            state.addGeckolibData(WITHDRAW, animatable.circleWithdraws());
            state.addGeckolibData(CLOCK, animatable.tickCount + partialTick);
            float sword = -1;
            var move = animatable.npcCombatMove();
            if (move != null && "conjure_sweep".equals(move.animation()) && !KohakuCompanion.bladeStaff(animatable.getMainHandItem())) {
                sword = VesperglassOnBone.state((animatable.level().getGameTime() - move.start() + partialTick) / 20f);
            }
            state.addGeckolibData(SWORD, sword);
            state.addGeckolibData(ID, animatable.getId());
            float umbrella = 0;
            if (move != null && animatable.umbrella() > 0) {
                umbrella = KohakuUmbrella.openness(move.animation(), (animatable.level().getGameTime() - move.start() + partialTick) / 20f);
            }
            state.addGeckolibData(UMBRELLA, umbrella);
            state.addGeckolibData(UMBRELLA_WIDE, animatable.umbrella() == 2);
            state.addGeckolibData(OUTFIT, animatable.outfit());
        }

        @Override
        public void adjustRenderPose(RenderPassInfo<R> info) {
            super.adjustRenderPose(info);
            boolean sword = info.getOrDefaultGeckolibData(SWORD, -1f) >= 0, umbrella = info.getOrDefaultGeckolibData(UMBRELLA, 0f) > 0;
            if (!sword && !umbrella) return;
            GeoBone hand = info.model().boneLookup().get().get("RightHand");
            if (hand == null) return;
            if (sword) info.addPerBoneRender(hand, (i, bone, out) -> VesperglassOnBone.submit(i.poseStack(), out, i.packedLight(),
                    i.getOrDefaultGeckolibData(SWORD, -1f), "kohaku" + i.getOrDefaultGeckolibData(ID, 0)));
            // 下雨天的伞面：画在杖头上（阴影那一遍不画）
            if (umbrella) info.addPerBoneRender(hand, (i, bone, out) -> {
                if (!dev.logos.dimension.client.dissolve.Dissolves.shadowPass()) KohakuUmbrella.submit(i.poseStack(), out,
                        i.getOrDefaultGeckolibData(UMBRELLA, 0f), i.getOrDefaultGeckolibData(UMBRELLA_WIDE, false), i.getOrDefaultGeckolibData(CLOCK, 0f));
            });
        }

        @Override
        public void submit(R state, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
            if (!state.getOrDefaultGeckolibData(HIDDEN, false)) super.submit(state, pose, out, camera);
            float age = state.getOrDefaultGeckolibData(CIRCLE, -1f);
            if (age < 0) return;
            boolean withdraw = state.getOrDefaultGeckolibData(WITHDRAW, false);
            // 传进来的是以她为原点的；移回世界原点，阵按世界坐标画（光柱要朝着镜头）
            pose.pushPose();
            pose.translate(-state.x, -state.y, -state.z);
            KohakuCircle.submit(pose, out, camera, new Vec3(state.x, state.y, state.z), withdraw ? KohakuCompanion.WITHDRAW_RADIUS
                    : KohakuCompanion.CIRCLE_RADIUS, age, state.getOrDefaultGeckolibData(CIRCLE_LENGTH, 1), withdraw,
                    state.getOrDefaultGeckolibData(CLOCK, 0f));
            pose.popPose();
        }

        @Override
        public void adjustModelBonesForRender(RenderPassInfo<R> info, BoneSnapshots snapshots) {
            super.adjustModelBonesForRender(info, snapshots);
            if (info.model() != outfitModel) {
                outfitModel = info.model();
                hidden.clear();
            }
            KohakuOutfit outfit = info.getOrDefaultGeckolibData(OUTFIT, KohakuOutfit.CARDIGAN);
            List<String> groups = hidden.computeIfAbsent(outfit, o -> {
                List<String> out = new ArrayList<>();
                for (GeoBone bone : info.model().boneLookup().get().values()) {
                    boolean top = bone.parent() == null || !bone.parent().name().startsWith(CompanionOutfit.PREFIX);
                    if (top && o.hides(bone.name())) out.add(bone.name());
                }
                return List.copyOf(out);
            });
            for (String group : groups) {
                snapshots.ifPresent(group, s -> {
                    s.skipRender(true);
                    s.skipChildrenRender(true);
                });
            }
        }
    }

    /**
     * 拉塞塔（docs/LACERTA_COMPANION.md）：NPC 的通用渲染。入场最初几刻不画；睡着、倒下时不叠加视线。
     * 炽焰长剑画在 {@code Weapon} 骨骼上（{@link CrimsonOnBone}）：拔剑时凝出来，收剑后散掉。
     */
    public static class LacertaRenderer<R extends LivingEntityRenderState & GeoRenderState> extends NpcRenderer<LacertaCompanion, R> {
        /** 剑：进度（-1 完整、0–1 凝聚或消散、-2 不画）、是不是在凝；她的实体 ID（凝聚、消散的随机种子）。 */
        private static final DataTicket<Float> SWORD = DataTicket.create("logos_dimension_lacerta_sword", Float.class);
        private static final DataTicket<Boolean> APPEAR = DataTicket.create("logos_dimension_lacerta_sword_appear", Boolean.class);
        private static final DataTicket<Integer> ID = DataTicket.create("logos_dimension_lacerta_id", Integer.class);

        public LacertaRenderer(EntityRendererProvider.Context context) {
            super(context, "lacerta_companion");
        }

        @Override
        public boolean shouldRender(LacertaCompanion entity, Frustum frustum, double x, double y, double z) {
            return !entity.hiddenForSummon() && super.shouldRender(entity, frustum, x, y, z);
        }

        @Override
        public void addRenderData(LacertaCompanion animatable, Void relatedObject, R state, float partialTick) {
            super.addRenderData(animatable, relatedObject, state, partialTick);
            if (animatable.curled()) {
                state.xRot = 0;
                state.yRot = 0;
            }
            float[] sword = CrimsonOnBone.state(animatable, partialTick);
            state.addGeckolibData(SWORD, sword == null ? -2f : sword[0]);
            state.addGeckolibData(APPEAR, sword != null && sword[1] > 0);
            state.addGeckolibData(ID, animatable.getId());
        }

        @Override
        public void adjustRenderPose(RenderPassInfo<R> info) {
            super.adjustRenderPose(info);
            if (info.getOrDefaultGeckolibData(SWORD, -2f) <= -2) return;
            GeoBone weapon = info.model().boneLookup().get().get("Weapon");
            if (weapon != null) info.addPerBoneRender(weapon, (i, bone, out) -> CrimsonOnBone.submit(i.poseStack(), out, i.packedLight(),
                    i.getOrDefaultGeckolibData(SWORD, -1f), i.getOrDefaultGeckolibData(APPEAR, true), "lacerta" + i.getOrDefaultGeckolibData(ID, 0)));
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
