package dev.logos.dimension.client.foxfire;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.dissolve.Dissolves;
import dev.logos.dimension.client.shiraume.SoftGlow;
import dev.logos.dimension.foxfire.FoxfireCue;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireRules;
import dev.logos.runtime.SurfaceAura;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * 狐火在客户端的样子（docs/FOXFIRE.md 第 5 节），只用颜色区分神狐的幽蓝和白梅的淡粉：
 * <ul>
 *   <li>打中：一闪，几缕火舌往上窜，交给泛光；换掉 Logos 的奥秘命中特效。</li>
 *   <li>施放：脚下一圈狐火，几缕火往上飘；换掉 Logos 的奥秘施法圈。</li>
 *   <li>魔力缠绕：手里东西燃着那种颜色的表面火焰（{@code FoxfireSurfaceAuraMixin}）；游脉一路冒狐火（{@link FoxfireVeinVisual}）。</li>
 *   <li>缠身：身上一圈往上窜的火舌；烧着的是自己时，第一人称画面底边泛起狐火色。</li>
 *   <li>惑：被打中的玩家画面四边泛起一圈狐火色，一秒内退掉；被惑的怪物头上绕着几点狐火。</li>
 * </ul>
 * 贴图都是 Logos 现成的特效贴图。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class FoxfireClient {
    private static final RenderType FLASH = VfxParts.add("fire_impact/flash");
    private static final RenderType GLOW = VfxParts.add("heal/glow");
    private static final RenderType TONGUE = VfxParts.add("fire_impact/glow");
    private static final SoftGlow BLOOM = new SoftGlow();
    private static final Map<FoxfireFlame, SurfaceAura> FIRES = new EnumMap<>(FoxfireFlame.class);

    private record Hit(FoxfireFlame flame, int entity, Vec3 at, Vec3 dir, float size, float start, long seed) {}
    private record Cast(FoxfireFlame flame, Vec3 at, float start) {}
    private record Daze(FoxfireFlame flame, int entity, float start, float ticks) {}
    private record Mark(FoxfireFlame flame, long until) {}

    private static final List<Hit> HITS = new ArrayList<>();
    private static final List<Cast> CASTS = new ArrayList<>();
    private static final List<Daze> DAZES = new ArrayList<>();
    private static final Map<Integer, Mark> VEINS = new HashMap<>();
    /** 这一刻正在狐火缠身的生物（每刻扫一遍）。 */
    private static final List<LivingEntity> BURNING = new ArrayList<>();
    private static @Nullable FoxfireFlame bewitchFlame;
    private static float bewitchStart = -1000;
    /** 客户端测试用：收到过几次各种提示；手上的缠绕换成狐火、游脉画成狐火颜色各几次。 */
    private static final int[] RECEIVED = new int[8];
    private static int wrapFires, veinLooks;

    private static final float HIT_LIFE = 14, CAST_LIFE = 20, BEWITCH_LIFE = 22;

    private FoxfireClient() {}

    /** 手上缠着狐火时，物品表面火焰换成的样子。 */
    public static SurfaceAura fire(FoxfireFlame flame) {
        wrapFires++;
        return FIRES.computeIfAbsent(flame, f -> new SurfaceAura(SurfaceAura.Style.FIRE, f.hue));
    }

    /** 狐火术式的游脉换上的样子。 */
    public static dev.logos.client.VeinVisual veinVisual(FoxfireFlame flame) {
        return new FoxfireVeinVisual(flame);
    }

    /** Logos 的游脉 {@code id} 是不是狐火术式的，是的话什么颜色。 */
    public static @Nullable FoxfireFlame vein(int id) {
        Mark mark = VEINS.get(id);
        ClientLevel level = Minecraft.getInstance().level;
        FoxfireFlame flame = mark != null && level != null && level.getGameTime() <= mark.until ? mark.flame : null;
        if (flame != null) veinLooks++;
        return flame;
    }

    public static int received(int kind) {
        return kind >= 0 && kind < RECEIVED.length ? RECEIVED[kind] : 0;
    }

    public static int wrapFires() {
        return wrapFires;
    }

    public static int veinLooks() {
        return veinLooks;
    }

    public static void clear() {
        HITS.clear();
        CASTS.clear();
        DAZES.clear();
        VEINS.clear();
        BURNING.clear();
        bewitchFlame = null;
        java.util.Arrays.fill(RECEIVED, 0);
        wrapFires = veinLooks = 0;
    }

    @SubscribeEvent
    static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(FoxfireCue.TYPE, (cue, context) -> receive(cue));
    }

    private static void receive(FoxfireCue cue) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        if (cue.kind() >= 0 && cue.kind() < RECEIVED.length) RECEIVED[cue.kind()]++;
        float now = level.getGameTime();
        FoxfireFlame flame = cue.flameKind();
        switch (cue.kind()) {
            case FoxfireCue.HIT -> HITS.add(new Hit(flame, cue.entity(), cue.pos(), cue.dir(), cue.size(), now, level.getRandom().nextLong()));
            case FoxfireCue.CAST -> CASTS.add(new Cast(flame, cue.pos(), now));
            case FoxfireCue.VEIN -> VEINS.put(cue.entity(), new Mark(flame, level.getGameTime() + 100));
            case FoxfireCue.BEWITCH -> {
                bewitchFlame = flame;
                bewitchStart = now;
            }
            case FoxfireCue.DAZE -> DAZES.add(new Daze(flame, cue.entity(), now, cue.size()));
            default -> {}
        }
        if (HITS.size() > 64) HITS.removeFirst();
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        BURNING.clear();
        if (level == null) return;
        for (Entity e : level.entitiesForRendering()) {
            if (e instanceof LivingEntity living && FoxfireRules.burning(living) != null) BURNING.add(living);
        }
        // 缠身的火星：幽蓝用原版的灵魂火焰，白梅的淡粉用淡粉的尘和樱花瓣
        RandomSource r = level.getRandom();
        for (LivingEntity e : BURNING) {
            // 第一人称时自己身上的火星会糊在镜头上：自己烧着由画面底边表示
            if (e.isInvisible() || (level.getGameTime() + e.getId()) % 2 != 0 || firstPersonSelf(e)) continue;
            FoxfireFlame flame = FoxfireRules.burning(e);
            double a = r.nextDouble() * Math.PI * 2, rr = e.getBbWidth() * .55;
            double x = e.getX() + Math.cos(a) * rr, y = e.getY() + r.nextDouble() * e.getBbHeight() * .8, z = e.getZ() + Math.sin(a) * rr;
            if (flame == FoxfireFlame.KITSUNE) level.addParticle(net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME, x, y, z, 0, .03, 0);
            else {
                level.addParticle(new net.minecraft.core.particles.DustParticleOptions(flame.core, 1.1f), x, y, z, 0, .04, 0);
                if (r.nextInt(5) == 0) level.addParticle(net.minecraft.core.particles.ParticleTypes.CHERRY_LEAVES, x, y, z, 0, 0, 0);
            }
        }
        long now = level.getGameTime();
        if (VEINS.size() > 64) VEINS.values().removeIf(m -> now > m.until);
    }

    // ---------------------------------------------------------------- 世界里

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submit(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || HITS.isEmpty() && CASTS.isEmpty() && DAZES.isEmpty() && BURNING.isEmpty()) return;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float now = level.getGameTime() + pt;
        var camera = event.getLevelRenderState().cameraRenderState;
        VfxParts.View view = VfxParts.View.of(camera);
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector out = ShaderPackOverlay.collector(event.getSubmitNodeCollector());
        boolean shadow = Dissolves.shadowPass();
        SoftGlow glow = BLOOM.begin(view);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);

        HITS.removeIf(h -> now - h.start > HIT_LIFE);
        for (Hit h : HITS) {
            Entity e = level.getEntity(h.entity);
            // 打在第一人称的自己身上：这一团会贴在镜头上，由惑的画面边缘表示
            if (e != null && firstPersonSelf(e)) continue;
            Vec3 at = e != null && now - h.start < 6 ? e.getBoundingBox().getCenter().add(e.getPosition(pt).subtract(e.position())) : h.at;
            float age = now - h.start;
            out.submitCustomGeometry(pose, TONGUE, (p, buf) -> hit(p, buf, view, h, at, age));
            float k0 = age / HIT_LIFE;
            // 泛光要克制：太大太亮会把第一人称的整个画面染成狐火色
            glow.disc(at, h.size * (.42f + .2f * k0), VfxParts.argb((1 - k0) * (1 - k0) * .38f, h.flame.core));
            if (age < 6) {
                float k = 1 - age / 6f;
                out.submitCustomGeometry(pose, FLASH, (p, buf) -> VfxParts.sprite(p, buf, view, at, h.size * (.32f + .22f * (1 - k)), h.seed % 7,
                        VfxParts.argb(k * .7f, h.flame.bright)));
            }
        }

        CASTS.removeIf(c -> now - c.start > CAST_LIFE);
        for (Cast c : CASTS) {
            float age = now - c.start;
            out.submitCustomGeometry(pose, TONGUE, (p, buf) -> cast(p, buf, view, c, age));
            float k0 = age / CAST_LIFE, fade0 = k0 < .2f ? k0 / .2f : 1 - (k0 - .2f) / .8f, radius0 = castRadius(k0);
            glow.ring(c.at.add(0, .06, 0), new Vec3(0, 1, 0), radius0, .1f, 32, VfxParts.argb(fade0 * .8f, c.flame.core));
            glow.disc(c.at.add(0, .2, 0), radius0 * 1.1f, VfxParts.argb(fade0 * .35f, c.flame.core));
        }

        DAZES.removeIf(d -> now - d.start > d.ticks);
        for (Daze d : DAZES) {
            Entity e = level.getEntity(d.entity);
            if (e == null) continue;
            Vec3 head = e.getPosition(pt).add(0, e.getBbHeight() + .25, 0);
            float age = now - d.start, fade = Math.min(1, Math.min(age / 4f, (d.ticks - age) / 6f));
            float orbit = e.getBbWidth() * .45f + .2f;
            Vec3[] wisps = new Vec3[3];
            for (int i = 0; i < 3; i++) {
                double a = age * .35 + i * Math.PI * 2 / 3;
                wisps[i] = head.add(Math.cos(a) * orbit, .08 * Math.sin(age * .5 + i), Math.sin(a) * orbit);
                glow.disc(wisps[i], .28f, VfxParts.argb(fade * .5f, d.flame.core));
            }
            out.submitCustomGeometry(pose, GLOW, (p, buf) -> {
                for (Vec3 w : wisps) {
                    VfxParts.sprite(p, buf, view, w, .14f, 0, VfxParts.argb(fade * .95f, d.flame.bright));
                    VfxParts.sprite(p, buf, view, w, .3f, 0, VfxParts.argb(fade * .55f, d.flame.core));
                }
            });
        }

        for (LivingEntity e : BURNING) {
            FoxfireFlame flame = FoxfireRules.burning(e);
            if (flame == null || e.isInvisible()) continue;
            if (firstPersonSelf(e)) continue;
            Vec3 base = e.getPosition(pt);
            float width = e.getBbWidth(), height = e.getBbHeight();
            int id = e.getId();
            out.submitCustomGeometry(pose, TONGUE, (p, buf) -> burn(p, buf, view, flame, base, width, height, now, id));
            float flicker = .75f + .25f * Mth.sin(now * .9f + id);
            glow.disc(base.add(0, height * .5, 0), Math.max(.5f, width) * 1.1f, VfxParts.argb(.35f * flicker, flame.core));
        }
        pose.popPose();
        if (!shadow) glow.flush();
    }

    /** 一闪之后几缕火舌往上窜：从身上偏向来路的另一边冒出来，越往上越细、从焰心的白转成外焰的颜色。 */
    private static void hit(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Hit h, Vec3 at, float age) {
        RandomSource r = RandomSource.create(h.seed);
        float s = h.size;
        for (int i = 0; i < 7; i++) {
            float life = 8 + r.nextFloat() * 6, delay = r.nextFloat() * 2.5f;
            float k = (age - delay) / life;
            Vec3 side = new Vec3(r.nextFloat() - .5, r.nextFloat() * .4 - .1, r.nextFloat() - .5).add(h.dir.scale(.6)).normalize();
            if (k <= 0 || k >= 1) continue;
            Vec3 p = at.add(side.scale(s * (.18 + .35 * k))).add(0, s * .55 * k * k + s * .05, 0);
            float radius = s * (.32f - .22f * k);
            float alpha = (k < .15f ? k / .15f : 1 - (k - .15f) / .85f) * .95f;
            VfxParts.sprite(pose, buf, view, p, radius, i * 1.7f + age * .05f, VfxParts.argb(alpha, VfxParts.lerpColor(h.flame.bright, h.flame.core, Math.min(1, k * 1.6f))));
        }
        float k = age / HIT_LIFE;
        VfxParts.sprite(pose, buf, view, at, s * (.4f + .2f * k), 0, VfxParts.argb((1 - k) * .45f, h.flame.core));
    }

    /** 施放：脚下亮起一圈，五缕火从圈上飘起来。 */
    private static float castRadius(float k) {
        return .7f + .5f * VfxParts.smooth(0, 1, k);
    }

    private static void cast(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Cast c, float age) {
        float k = age / CAST_LIFE, fade = k < .2f ? k / .2f : 1 - (k - .2f) / .8f;
        float radius = castRadius(k);
        for (int i = 0; i < 5; i++) {
            double a = i * Math.PI * 2 / 5 + age * .03;
            float rise = Math.min(1, age / 14f);
            Vec3 p = c.at.add(Math.cos(a) * radius, .1 + rise * (.6 + .15 * i % 3), Math.sin(a) * radius);
            VfxParts.sprite(pose, buf, view, p, .22f * (1 - rise * .6f), i, VfxParts.argb(fade * .9f, VfxParts.lerpColor(c.flame.bright, c.flame.core, rise)));
        }
    }

    /** 缠身：身上一圈火舌一直往上窜（每根错开相位），身体中间一团微光。 */
    private static void burn(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, FoxfireFlame flame, Vec3 base, float width, float height,
                             float now, int id) {
        // 火舌在身体外面一圈，不然被身体挡住
        float radius = width * .5f + .18f;
        for (int i = 0; i < 7; i++) {
            float phase = Mth.frac(now * .05f + i / 7f + id * .37f);
            double a = i * Math.PI * 2 / 7 + id + now * .01;
            Vec3 p = base.add(Math.cos(a) * radius * (1 - phase * .35), height * (.08 + .8 * phase), Math.sin(a) * radius * (1 - phase * .35));
            float alpha = Mth.sin(phase * Mth.PI) * .95f;
            VfxParts.sprite(pose, buf, view, p, Math.max(.16f, width * .55f) * (1.15f - phase * .75f), i + now * .02f,
                    VfxParts.argb(alpha, VfxParts.lerpColor(flame.bright, flame.core, phase)));
        }
    }

    /** {@code e} 是第一人称镜头所在的那个人。 */
    private static boolean firstPersonSelf(Entity e) {
        Minecraft mc = Minecraft.getInstance();
        return e == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson();
    }

    // ---------------------------------------------------------------- 画面上

    @SubscribeEvent
    static void hud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui) return;
        float now = mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        GuiGraphicsExtractor g = event.getGuiGraphics();
        int w = g.guiWidth(), h = g.guiHeight();
        FoxfireFlame burning = FoxfireRules.burning(mc.player);
        if (burning != null && mc.options.getCameraType().isFirstPerson()) {
            float flicker = .7f + .3f * Mth.sin(now * .8f) * Mth.sin(now * .37f + 1);
            int top = (int) (h * .72f);
            g.fillGradient(0, top, w, h, VfxParts.argb(0, burning.core), VfxParts.argb(.42f * flicker, burning.core));
        }
        float age = now - bewitchStart;
        if (bewitchFlame != null && age >= 0 && age < BEWITCH_LIFE) {
            float k = age < 3 ? age / 3 : 1 - (age - 3) / (BEWITCH_LIFE - 3);
            edges(g, w, h, bewitchFlame.core, .4f * k);
        }
    }

    /** 四边往里渐淡的一圈。 */
    private static void edges(GuiGraphicsExtractor g, int w, int h, int rgb, float alpha) {
        int band = Math.max(8, Math.min(w, h) / 6);
        g.fillGradient(0, 0, w, band, VfxParts.argb(alpha, rgb), VfxParts.argb(0, rgb));
        g.fillGradient(0, h - band, w, h, VfxParts.argb(0, rgb), VfxParts.argb(alpha, rgb));
        int strips = 12;
        for (int i = 0; i < strips; i++) {
            float a = alpha * (1 - (i + .5f) / strips);
            int x0 = band * i / strips, x1 = band * (i + 1) / strips;
            g.fill(x0, 0, x1, h, VfxParts.argb(a / strips * 2.2f, rgb));
            g.fill(w - x1, 0, w - x0, h, VfxParts.argb(a / strips * 2.2f, rgb));
        }
    }
}
