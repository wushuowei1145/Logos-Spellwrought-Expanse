package dev.logos.dimension.client.dissolve;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.screenfx.GlowBloom;
import dev.logos.dimension.dissolve.DissolvePayload;
import dev.logos.dimension.dissolve.DissolveShape;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * 这个客户端上正在消散的东西：实体（连同穿的、拿的）、某只手上的东西、方块。服务端用 {@code Dissolving} 开始，见 docs/DISSOLVE.md。
 * 消散完的目标再藏一小会儿，等服务端把它拿走。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class Dissolves {
    private Dissolves() {}

    /** 消散完之后还藏多少刻。 */
    private static final int LINGER = 40;
    private static final int CAP = 1500;
    /** 同时消散的方块超过这些就切粗一档。 */
    private static final int FINE_BLOCKS = 16, TEXEL_BLOCKS = 64;
    private static final RenderType HALO = VfxParts.add("fire_impact/glow"), SPARK = VfxParts.add("fire_impact/ember");

    private static final Map<Integer, Dissolve> ENTITIES = new HashMap<>();
    private static final Map<Long, Dissolve> HELD = new HashMap<>();
    private static final List<BlockGroup> GROUPS = new ArrayList<>();
    private static final Map<EntityRenderState, Dissolve> STATES = new IdentityHashMap<>();
    private static final List<Mote> MOTES = new ArrayList<>();
    private static final RandomSource RANDOM = RandomSource.create();
    private static int ticks;
    private static long frame;
    private static @Nullable Level shown;

    /** 有多少东西在消散、外面有多少粒子（客户端场景要查）。 */
    public static int active() { return ENTITIES.size() + HELD.size() + GROUPS.size(); }

    public static int motes() { return MOTES.size(); }

    private static float now() {
        return ticks + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }

    /** 物品渲染状态多记的东西：谁拿着它。由 Mixin 实现。 */
    public interface HeldItem {
        int logos_dimension$owner();

        void logos_dimension$owner(int owner);

        ItemDisplayContext logos_dimension$context();
    }

    /** Logos 只留了给自己用的判断，这里直接问 Iris。Iris 是可选的，所以用反射，没装时不碰它的类。 */
    private static final class Iris {
        private static final @Nullable Object API;
        private static final @Nullable Method IN_USE, SHADOW;

        static {
            Object api = null;
            Method inUse = null, shadow = null;
            if (ModList.get().isLoaded("iris")) {
                try {
                    Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    api = type.getMethod("getInstance").invoke(null);
                    inUse = type.getMethod("isShaderPackInUse");
                    shadow = type.getMethod("isRenderingShadowPass");
                } catch (ReflectiveOperationException e) {
                    api = null;
                }
            }
            API = api;
            IN_USE = inUse;
            SHADOW = shadow;
        }

        static boolean shadowPass() {
            if (API == null) return false;
            try {
                return (boolean) IN_USE.invoke(API) && (boolean) SHADOW.invoke(API);
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }
    }

    /** 这一轮是不是光影包的阴影通道，那时的姿势是太阳的。 */
    public static boolean shadowPass() { return Iris.shadowPass(); }

    // ---- 开始 ----------------------------------------------------------------------------------

    @SubscribeEvent
    static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(DissolvePayload.TYPE, (payload, context) -> receive(payload));
    }

    private static void receive(DissolvePayload p) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        switch (p.target()) {
            case DissolvePayload.ENTITY -> {
                Entity e = level.getEntity(p.entity());
                if (e != null) entity(e, p.shape(), p.ticks(), p.color());
            }
            case DissolvePayload.RIGHT, DissolvePayload.LEFT -> {
                if (level.getEntity(p.entity()) instanceof LivingEntity e) {
                    held(e, p.target() == DissolvePayload.LEFT ? HumanoidArm.LEFT : HumanoidArm.RIGHT, p.shape(), p.ticks(), p.color());
                }
            }
            default -> {
                List<BlockState> states = new ArrayList<>(p.states().size());
                for (int id : p.states()) states.add(Block.stateById(id));
                blocks(level, p.positions(), states, p.shape(), p.ticks(), p.color());
            }
        }
    }

    /** 一个实体和它画的所有东西，脚底是图案原点。{@link DissolveShape.Kind#POINT} 从世界坐标换算过来。 */
    private static void entity(Entity entity, DissolveShape shape, int duration, int color) {
        Vec3 at = entity.position();
        if (shape.kind() == DissolveShape.Kind.POINT) {
            shape = new DissolveShape(shape.kind(), shape.x() - (float) at.x, shape.y() - (float) at.y, shape.z() - (float) at.z, shape.inward(), shape.rough());
        }
        Dissolve d = new Dissolve(shape, ticks, duration, color);
        float w = entity.getBbWidth() / 2;
        d.include(-w, 0, -w);
        d.include(w, entity.getBbHeight(), w);
        ENTITIES.put(entity.getId(), d);
    }

    /** {@code holder} 这一边手上的东西，不含人。世界里的一点对物品没有意义，改从中间外扩。 */
    private static void held(LivingEntity holder, HumanoidArm arm, DissolveShape shape, int duration, int color) {
        if (shape.kind() == DissolveShape.Kind.POINT) shape = DissolveShape.sphere(.5f, .5f, .5f);
        HELD.put(key(holder.getId(), arm == HumanoidArm.LEFT), new Dissolve(shape, ticks, duration, color));
    }

    /**
     * 已经（或马上）从世界移除的方块，算一个目标：球形或扫过穿过整组，噪波跨方块连续。
     * {@link DissolveShape.Kind#POINT} 是世界里的一点。
     */
    private static void blocks(Level level, List<BlockPos> positions, List<BlockState> states, DissolveShape shape, int duration, int color) {
        if (positions.isEmpty()) return;
        BlockPos first = positions.getFirst();
        int minX = first.getX(), minY = first.getY(), minZ = first.getZ(), maxX = minX, maxY = minY, maxZ = minZ;
        for (BlockPos p : positions) {
            minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
        }
        BlockPos min = new BlockPos(minX, minY, minZ);
        if (shape.kind() == DissolveShape.Kind.POINT) {
            shape = new DissolveShape(shape.kind(), shape.x() - minX, shape.y() - minY, shape.z() - minZ, shape.inward(), shape.rough());
        }
        Dissolve d = new Dissolve(shape, ticks, duration, color);
        d.include(0, 0, 0);
        d.include(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
        DissolveMesh.Detail detail = positions.size() <= FINE_BLOCKS ? DissolveMesh.Detail.FINE
                : positions.size() <= TEXEL_BLOCKS ? DissolveMesh.Detail.TEXEL : DissolveMesh.Detail.COARSE;
        List<GhostBlock> ghosts = new ArrayList<>(positions.size());
        for (int i = 0; i < positions.size(); i++) {
            GhostBlock ghost = GhostBlock.of(level, positions.get(i), states.get(i), min, detail);
            if (ghost != null) ghosts.add(ghost);
        }
        GROUPS.add(new BlockGroup(d, ghosts));
    }

    private static long key(int owner, boolean left) { return (long) owner << 1 | (left ? 1 : 0); }

    // ---- 渲染那边调用的 ---------------------------------------------------------------------------

    /** 每个物品解析模型时调用：有手上的东西在消散时，记下谁拿着它。 */
    public static void resolved(ItemStackRenderState out, ItemStack stack, ItemDisplayContext context, @Nullable ItemOwner owner) {
        if (HELD.isEmpty() || !(out instanceof HeldItem item)) return;
        if (owner instanceof ItemOwner.OffsetFromOwner offset) owner = offset.owner();
        item.logos_dimension$owner(stack.isEmpty() || context == ItemDisplayContext.GUI || !(owner instanceof Entity entity) ? -1 : entity.getId());
    }

    /** 实体的渲染状态做好了；{@link #entityCollector} 靠它找到消散。 */
    public static void extracted(Entity entity, EntityRenderState state) {
        if (ENTITIES.isEmpty()) return;
        Dissolve d = ENTITIES.get(entity.getId());
        if (d != null) STATES.put(state, d);
    }

    /** 离镜头 {@code x y z} 的实体往哪里提交。 */
    public static SubmitNodeCollector entityCollector(SubmitNodeCollector out, EntityRenderState state, double x, double y, double z, PoseStack pose) {
        if (STATES.isEmpty()) return out;
        Dissolve d = STATES.get(state);
        return d == null ? out : DissolveCollector.entity(out, d, x, y, z, pose, now(), frame, ticks);
    }

    /** 物品的层往哪里提交：拿着它的那只手正在消散时，是切割用的收集器。 */
    public static SubmitNodeCollector itemCollector(SubmitNodeCollector out, HeldItem item, PoseStack pose) {
        if (HELD.isEmpty() || item.logos_dimension$owner() < 0) return out;
        ItemDisplayContext context = item.logos_dimension$context();
        boolean hand = context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        if (!hand) return out;
        Dissolve d = HELD.get(key(item.logos_dimension$owner(), context.leftHand()));
        return d == null ? out : DissolveCollector.item(out, d, context, pose, now(), frame, ticks);
    }

    @SubscribeEvent
    static void onReload(AddClientReloadListenersEvent event) {
        event.addListener(LogosDimension.id("dissolve"), (ResourceManagerReloadListener) rm -> {
            DissolveMesh.clear();
            DissolveSkin.clear();
        });
    }

    @SubscribeEvent
    static void onFrame(RenderFrameEvent.Pre event) {
        frame++;
        DissolveCapture.nextFrame();
    }

    /** 渲染状态在一帧开始之前就做好了，所以到这一帧结束才忘掉。 */
    @SubscribeEvent
    static void onFrameEnd(RenderFrameEvent.Post event) {
        STATES.clear();
    }

    // ---- 每刻 ----------------------------------------------------------------------------------

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != shown) { clear(); shown = mc.level; }
        if (mc.isPaused() || mc.level == null) return;
        ticks++;
        ENTITIES.values().removeIf(Dissolves::over);
        HELD.values().removeIf(Dissolves::over);
        GROUPS.removeIf(g -> g.dissolve.gone(ticks));
        for (Dissolve d : ENTITIES.values()) emit(d);
        for (Dissolve d : HELD.values()) emit(d);
        for (BlockGroup g : GROUPS) emit(g.dissolve);
        MOTES.removeIf(m -> !m.tick());
        if (active() == 0) DissolveMesh.clear();
    }

    private static boolean over(Dissolve d) { return ticks > d.start + d.ticks + LINGER; }

    /** 从发光条上放粒子，和原版的两个发射器一样：贴在边缘的柔光（看上去着火主要靠它），和沿卷曲路线飘走的火星。 */
    private static void emit(Dissolve d) {
        if (d.gone(ticks) || !d.visible(ticks) || MOTES.size() >= CAP) return;
        // 按发光条有多少贴图像素算，也就是格子数的四分之一
        int halos = Math.min(4 + d.sampled / 12, 36), sparks = Math.min(1 + d.sampled / 40, 12);
        float[] s = d.samples;
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        for (int i = 0; i < halos + sparks; i++) {
            int o = RANDOM.nextInt(d.sampled) * 6;
            boolean halo = i < halos;
            // 自己手上的东西飘出来的粒子就在眼前，这个距离上必须又小又慢
            float near = Mth.clamp((float) camera.distanceTo(new Vec3(s[o], s[o + 1], s[o + 2])) / 4, .15f, 1);
            float speed = (halo ? .002f : .012f + RANDOM.nextFloat() * .035f) * near;
            Vec3 v = new Vec3(s[o + 3], s[o + 4], s[o + 5]).scale(speed)
                    .add((RANDOM.nextFloat() - .5f) * speed, (RANDOM.nextFloat() - .5f) * speed + (halo ? 0 : .012f * near), (RANDOM.nextFloat() - .5f) * speed);
            MOTES.add(new Mote(new Vec3(s[o], s[o + 1], s[o + 2]), v, halo, d.color,
                    halo ? 3 + RANDOM.nextInt(4) : 14 + RANDOM.nextInt(22), (halo ? .07f + RANDOM.nextFloat() * .07f : .018f + RANDOM.nextFloat() * .022f) * near));
        }
    }

    // ---- 画 ------------------------------------------------------------------------------------

    /** 排在最后，在 Logos 的 ShaderPackOverlay.begin 之后提交（见 AGENTS.md）。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submit(SubmitCustomGeometryEvent event) {
        if (GROUPS.isEmpty() && MOTES.isEmpty()) return;
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector raw = event.getSubmitNodeCollector(), out = ShaderPackOverlay.collector(raw);
        for (BlockGroup group : GROUPS) group.submit(pose, raw, out, camera);
        if (MOTES.isEmpty()) return;
        float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        if (!shadowPass()) {
            for (Mote m : MOTES) if (!m.halo) m.bloom(camera.pos, pt);
        }
        VfxParts.View view = VfxParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        out.submitCustomGeometry(pose, HALO, (p, buf) -> {
            for (Mote m : MOTES) if (m.halo) m.draw(p, buf, view, pt);
        });
        out.submitCustomGeometry(pose, SPARK, (p, buf) -> {
            for (Mote m : MOTES) if (!m.halo) m.draw(p, buf, view, pt);
        });
        pose.popPose();
    }

    /** 换世界或场景结束时清空。 */
    public static void clear() {
        ENTITIES.clear();
        HELD.clear();
        GROUPS.clear();
        STATES.clear();
        MOTES.clear();
    }

    // ---- 零件 ----------------------------------------------------------------------------------

    private static final class Mote {
        Vec3 pos, last, vel;
        final boolean halo;
        final int color, life;
        final float size;
        int age;

        Mote(Vec3 pos, Vec3 vel, boolean halo, int color, int life, float size) {
            this.pos = last = pos;
            this.vel = vel;
            this.halo = halo;
            this.color = color;
            this.life = life;
            this.size = size;
        }

        boolean tick() {
            last = pos;
            pos = pos.add(vel);
            vel = vel.scale(.95);
            if (!halo) {
                // 卷曲噪声，用一片缓慢转动的正弦场代替
                double t = (age + life) * .08;
                vel = vel.add(Math.sin(pos.y * 3 + t) * .0025, .0012 + Math.sin(pos.z * 3.3 + t) * .001, Math.sin(pos.x * 3.2 - t) * .0025);
            }
            return ++age < life;
        }

        /** 火星交给真正的泛光；柔光本身已经是软的，不用。 */
        void bloom(Vec3 camera, float pt) {
            float k = (age + pt) / life;
            Vec3 at = last.lerp(pos, pt).subtract(camera);
            int rgb = VfxParts.lerpColor(0xFFF0D0, color, Mth.clamp(k * 1.6f, 0, 1));
            GlowBloom.sprite((float) at.x, (float) at.y, (float) at.z, size * (1 - k * .5f), VfxParts.argb(Mth.clamp((1 - k) * 1.5f, 0, 1), rgb));
        }

        void draw(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, float pt) {
            float k = (age + pt) / life;
            Vec3 at = last.lerp(pos, pt);
            if (halo) {
                VfxParts.sprite(pose, buf, view, at, size * (1 + k * .4f), 0, VfxParts.argb((1 - k) * .4f, color));
            } else {
                int rgb = VfxParts.lerpColor(0xFFF0D0, color, Mth.clamp(k * 1.6f, 0, 1));
                VfxParts.sprite(pose, buf, view, at, size * (1 - k * .5f), age * .3f, VfxParts.argb(Mth.clamp((1 - k) * 1.5f, 0, 1), rgb));
            }
        }
    }

    private record GhostBlock(BlockPos pos, DissolveMesh mesh, float[] noise, Matrix4f toPattern, int[] tints) {
        static @Nullable GhostBlock of(Level level, BlockPos pos, BlockState state, BlockPos min, DissolveMesh.Detail detail) {
            Minecraft mc = Minecraft.getInstance();
            List<BlockStateModelPart> parts = new ArrayList<>();
            mc.getModelManager().getBlockStateModelSet().get(state).collectParts(RandomSource.create(state.getSeed(pos)), parts);
            List<BakedQuad> quads = new ArrayList<>();
            for (BlockStateModelPart part : parts) {
                for (Direction d : Direction.values()) quads.addAll(part.getQuads(d));
                quads.addAll(part.getQuads(null));
            }
            DissolveMesh mesh = DissolveMesh.of(quads, detail);
            if (mesh == null || mesh.quads.length == 0) return null;
            int layers = 0;
            for (BakedQuad q : quads) layers = Math.max(layers, q.materialInfo().tintIndex() + 1);
            int[] tints = new int[layers];
            for (int i = 0; i < layers; i++) {
                var source = mc.getBlockColors().getTintSource(state, i);
                tints[i] = source == null ? -1 : ARGB.opaque(source.colorInWorld(state, mc.level, pos));
            }
            int ox = pos.getX() - min.getX(), oy = pos.getY() - min.getY(), oz = pos.getZ() - min.getZ();
            float[] noise = new float[mesh.quads.length];
            for (int i = 0; i < noise.length; i++) {
                noise[i] = DissolveNoise.at(ox + mesh.cells[i * 6], oy + mesh.cells[i * 6 + 1], oz + mesh.cells[i * 6 + 2]);
            }
            return new GhostBlock(pos, mesh, noise, new Matrix4f().translation(ox, oy, oz), tints);
        }
    }

    private record BlockGroup(Dissolve dissolve, List<GhostBlock> ghosts) {
        void submit(PoseStack pose, SubmitNodeCollector raw, SubmitNodeCollector out, CameraRenderState camera) {
            Level level = Minecraft.getInstance().level;
            float now = now();
            if (level == null || dissolve.gone(now)) return;
            boolean particles = !shadowPass();
            for (GhostBlock g : ghosts) {
                pose.pushPose();
                pose.translate(g.pos.getX() - camera.pos.x, g.pos.getY() - camera.pos.y, g.pos.getZ() - camera.pos.z);
                Dissolve.Glow glow = new Dissolve.Glow();
                List<BakedQuad> left = dissolve.left(g.mesh, g.noise, pose.last(), g.toPattern, null, now, glow, particles, camera.pos, frame, ticks);
                if (!left.isEmpty()) {
                    raw.submitItem(pose, ItemDisplayContext.NONE, LevelRenderer.getLightCoords(level, g.pos), OverlayTexture.NO_OVERLAY, 0, g.tints, left,
                            ItemStackRenderState.FoilType.NONE);
                }
                if (particles) {
                    glow.bloom(false);
                    glow.submit(pose, out);
                }
                pose.popPose();
            }
        }
    }
}
