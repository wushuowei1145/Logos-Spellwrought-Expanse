package dev.logos.dimension.client.worldtree;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.dissolve.WeaponDissolve;
import dev.logos.dimension.item.WorldtreeBowItem;
import dev.logos.dimension.worldtree.Worldtree;
import dev.logos.dimension.worldtree.WorldtreeDraw;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * 物品模型 {@code logos_dimension:worldtree_arrows}：拉着的世界树之弓上的箭。原版箭的网格（{@code ModelLayers.ARROW}），
 * 搭箭点与箭头的位置按三段拉弓模型各自的那支箭（tools/import_worldtree_bow.py 量出，写在物品定义里）：
 * <ul>
 *   <li>拉满后中间的实箭，从箭尾往箭头凝聚出来；</li>
 *   <li>每锁定一个就多一支虚影箭（半透明自发光），在弓面上上下交替散开，同样凝聚出现（同流火的凝聚，见 {@link WeaponDissolve}）。</li>
 * </ul>
 * 拉弓状态来自弓上的 {@link WorldtreeDraw} 组件，所以别的玩家也看得到；只在手里画，物品栏、掉落物不画。
 */
public final class WorldtreeArrowsModel implements ItemModel {
    public static final Identifier TYPE = LogosDimension.id("worldtree_arrows");
    private static final Identifier SOLID = LogosDimension.id("textures/entity/projectiles/worldtree_arrow.png");
    private static final Identifier PHANTOM = LogosDimension.id("textures/entity/projectiles/worldtree_arrow_phantom.png");
    /** 凝聚的时长（刻）。 */
    public static final float APPEAR_TICKS = 6;
    /** 凝聚边缘的颜色：嫩叶绿。 */
    private static final int EDGE = 0x9CE08A;
    private static final int FULL_BRIGHT = 0xF000F0;
    /** 原版箭的箭羽是箭长的 5/16，放到交付那支箭的长度太宽；横截面按这个比例收窄。 */
    private static final float THIN = .45f;

    public record Stage(Vector3fc nock, Vector3fc tip) {
        static final Codec<Stage> CODEC = RecordCodecBuilder.create(i -> i.group(
                ExtraCodecs.VECTOR3F.fieldOf("nock").forGetter(Stage::nock), ExtraCodecs.VECTOR3F.fieldOf("tip").forGetter(Stage::tip)
        ).apply(i, Stage::new));
    }

    public record Unbaked(Identifier base, List<Float> thresholds, List<Stage> stages) implements ItemModel.Unbaked {
        public static final MapCodec<Unbaked> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Identifier.CODEC.fieldOf("base").forGetter(Unbaked::base),
                Codec.FLOAT.listOf().fieldOf("thresholds").forGetter(Unbaked::thresholds),
                Stage.CODEC.listOf().fieldOf("stages").forGetter(Unbaked::stages)
        ).apply(i, Unbaked::new));

        @Override public MapCodec<Unbaked> type() { return CODEC; }

        @Override public void resolveDependencies(ResolvableModel.Resolver resolver) { resolver.markDependency(base); }

        @Override
        public ItemModel bake(ItemModel.BakingContext context, Matrix4fc transformation) {
            ModelBaker baker = context.blockModelBaker();
            ResolvedModel resolved = baker.getModel(base);
            var properties = ModelRenderProperties.fromResolvedModel(baker, resolved, resolved.getTopTextureSlots());
            ArrowMesh mesh = ArrowMesh.of(context.entityModelSet().bakeLayer(ModelLayers.ARROW));
            return new WorldtreeArrowsModel(new Renderer(mesh, thresholds, stages), properties, new Matrix4f(transformation));
        }
    }

    private final Renderer renderer;
    private final ModelRenderProperties properties;
    private final Matrix4fc transformation;

    private WorldtreeArrowsModel(Renderer renderer, ModelRenderProperties properties, Matrix4fc transformation) {
        this.renderer = renderer;
        this.properties = properties;
        this.transformation = transformation;
    }

    @Override
    public void update(ItemStackRenderState state, ItemStack stack, ItemModelResolver resolver, ItemDisplayContext context,
                       ClientLevel level, ItemOwner owner, int seed) {
        WorldtreeDraw draw = stack.get(Worldtree.DRAW.get());
        if (draw == null || level == null || !hand(context)) return;
        float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float now = level.getGameTime() + partial;
        LivingEntity holder = owner == null ? null : owner.asLivingEntity();
        // 本地知道真实的拉弓刻数时用它（和原版换拉弓模型的刻数一致），否则按组件里开始的刻算
        float ticks = holder != null && holder.isUsingItem() && holder.getUseItem() == stack ? holder.getTicksUsingItem() + partial : now - draw.start();
        Args args = new Args(draw, ticks, now, context.firstPerson(), holder == null ? 0 : holder.getId());
        state.appendModelIdentityElement(this);
        state.appendModelIdentityElement(args);
        state.setAnimated();
        var layer = state.newLayer();
        layer.setLocalTransform(transformation);
        layer.setupSpecialModel(renderer, args);
        properties.applyToLayer(layer, context);
    }

    private static boolean hand(ItemDisplayContext context) {
        return context.firstPerson() || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
    }

    record Args(WorldtreeDraw draw, float ticks, float now, boolean firstPerson, int holder) {}

    /** 原版箭网格，换到箭自己的坐标：箭尾在原点，箭头在 +X 的 1 处（长度归一）。 */
    record ArrowMesh(List<Vector3f[]> quads, List<float[]> uvs, List<Vector3f> normals) {
        static ArrowMesh of(ModelPart root) {
            List<Vector3f[]> quads = new ArrayList<>();
            List<float[]> uvs = new ArrayList<>();
            List<Vector3f> normals = new ArrayList<>();
            List<float[]> raw = new ArrayList<>();
            root.visit(new PoseStack(), (pose, path, index, cube) -> {
                for (ModelPart.Polygon polygon : cube.polygons) {
                    var v = polygon.vertices();
                    if (v.length != 4) continue;
                    float[] q = new float[20];
                    for (int j = 0; j < 4; j++) {
                        Vector3f p = pose.pose().transformPosition(v[j].worldX(), v[j].worldY(), v[j].worldZ(), new Vector3f());
                        q[j * 5] = p.x; q[j * 5 + 1] = p.y; q[j * 5 + 2] = p.z; q[j * 5 + 3] = v[j].u(); q[j * 5 + 4] = v[j].v();
                    }
                    raw.add(q);
                }
            });
            // 箭轴是模型的 X：两端里贴图 u 在最外侧（0 或 1）的一端是箭头（原版贴图箭头在两半的外端）
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE, headSide = 0;
            for (float[] q : raw) for (int j = 0; j < 4; j++) { lo = Math.min(lo, q[j * 5]); hi = Math.max(hi, q[j * 5]); }
            for (float[] q : raw) for (int j = 0; j < 4; j++) {
                float u = q[j * 5 + 3], outer = Math.min(u, 1 - u);
                if (outer < .02f) headSide += q[j * 5] - (lo + hi) / 2;
            }
            float length = hi - lo, sign = headSide >= 0 ? 1 : -1, tail = sign > 0 ? lo : hi;
            for (float[] q : raw) {
                Vector3f[] corners = new Vector3f[4];
                for (int j = 0; j < 4; j++)
                    corners[j] = new Vector3f((q[j * 5] - tail) * sign / length, q[j * 5 + 1] / length * THIN, q[j * 5 + 2] * sign / length * THIN);
                Vector3f n = new Vector3f(corners[1]).sub(corners[0]).cross(new Vector3f(corners[3]).sub(corners[0]));
                if (n.lengthSquared() < 1e-12f) continue;
                quads.add(corners);
                // 与 WeaponDissolve 的约定相同：[u0, v0, u1, v1]，角 0 取 (u1,v1)、角 2 取 (u0,v0)
                uvs.add(new float[] {q[10 + 3], q[10 + 4], q[3], q[4]});
                normals.add(n.normalize());
            }
            return new ArrowMesh(List.copyOf(quads), List.copyOf(uvs), List.copyOf(normals));
        }
    }

    /** 真正画箭的特殊渲染器；坐标是物品模型的像素（姿势里已经做了展示变换和 -0.5 的居中）。 */
    static final class Renderer implements SpecialModelRenderer<Args> {
        private final ArrowMesh mesh;
        private final WeaponDissolve.Cells cells;
        private final List<Float> thresholds;
        private final List<Stage> stages;

        Renderer(ArrowMesh mesh, List<Float> thresholds, List<Stage> stages) {
            this.mesh = mesh;
            this.thresholds = thresholds;
            this.stages = stages;
            cells = WeaponDissolve.cut(mesh.quads, mesh.uvs, mesh.quads.stream().map(q -> true).toList(), 32, 32, new Vector3f(1, 0, 0));
        }

        @Override public Args extractArgument(ItemStack stack) { return null; }

        @Override public void getExtents(Consumer<Vector3fc> output) {}

        @Override
        public void submit(Args args, PoseStack pose, SubmitNodeCollector collector, int light, int overlay, boolean foil, int outline) {
            if (args == null || stages.isEmpty()) return;
            Stage stage = stages.get(stage(args.ticks * .05f));
            float length = new Vector3f(stage.tip).sub(stage.nock).length();
            pose.pushPose();
            pose.scale(1 / 16f, 1 / 16f, 1 / 16f);
            var draw = args.draw;
            if (draw.hasFull()) arrow(pose, collector, args, stage, length, 0, 0, RenderTypes.entityCutout(SOLID), light, 255,
                    (args.now - draw.full()) / APPEAR_TICKS, "center");
            for (int k = 0; k < draw.locks().size(); k++) {
                // 前后错开一点，叠在一起的虚影不闪
                float depth = (k % 2 == 0 ? 1 : -1) * .12f * (k / 2 + 1);
                // 第一人称弓离镜头很近，扇面收一半，不挡视线
                arrow(pose, collector, args, stage, length, WorldtreeBowItem.fan(k) * (args.firstPerson ? .5f : 1), depth, RenderTypes.entityTranslucentEmissive(PHANTOM),
                        FULL_BRIGHT, args.firstPerson ? 120 : 170, (args.now - draw.locks().get(k).time()) / APPEAR_TICKS, k);
            }
            pose.popPose();
        }

        private int stage(float useSeconds) {
            int s = 0;
            for (int i = 0; i < thresholds.size(); i++) if (useSeconds >= thresholds.get(i)) s = i + 1;
            return Math.min(s, stages.size() - 1);
        }

        private void arrow(PoseStack pose, SubmitNodeCollector collector, Args args, Stage stage, float length, float fan, float depth,
                           RenderType type, int light, int alpha, float appear, Object key) {
            // 箭的坐标 → 物品像素：箭尾放在搭箭点，绕弓面法线（物品 Z）转 fan 度，长度与交付那支箭相同
            Matrix4f bone = new Matrix4f().translation(stage.nock.x(), stage.nock.y(), stage.nock.z() + depth)
                    .rotateZ((float) Math.toRadians(fan)).scale(length);
            if (appear < 1) {
                WeaponDissolve.submit(new Key(args.holder, args.firstPerson, key), cells, bone, pose, collector, type, light, alpha,
                        Math.max(0, appear), true, false, EDGE, args.firstPerson);
                return;
            }
            collector.submitCustomGeometry(pose, type, (p, buffer) -> {
                for (int q = 0; q < mesh.quads.size(); q++) {
                    Vector3f[] c = mesh.quads.get(q);
                    float[] uv = mesh.uvs.get(q);
                    Vector3f n = bone.transformDirection(new Vector3f(mesh.normals.get(q))).normalize();
                    for (int j = 0; j < 4; j++) {
                        Vector3f v = bone.transformPosition(new Vector3f(c[j]));
                        float u = j == 0 || j == 3 ? uv[2] : uv[0], w = j < 2 ? uv[3] : uv[1];
                        buffer.addVertex(p, v.x, v.y, v.z).setColor(255, 255, 255, alpha).setUv(u, w)
                                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, n.x, n.y, n.z);
                    }
                }
            });
        }

        /** 放粒子的是谁：同一人第一、第三人称、每支箭分开。 */
        private record Key(int holder, boolean firstPerson, Object arrow) {}
    }
}
