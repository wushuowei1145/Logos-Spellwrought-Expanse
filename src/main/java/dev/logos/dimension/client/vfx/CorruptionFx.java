package dev.logos.dimension.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.elven.blight.CorruptedElfQueen;
import dev.logos.dimension.elven.blight.ElfPrincess;
import dev.logos.dimension.elven.blight.QueenSpellPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * 女王战里腐败一方的特效（{@link QueenSpellPayload} 的 {@code VEIN} 以后各种）：
 * <ul>
 * <li>光柱落点的红光预警、腐败之心轰下来的光柱（{@link CorruptionPillarVisual}）和落点像水柱一样的液滴飞溅；
 * <li>「荆棘穿刺」圈出的那块地、向上刺出的荆棘（{@link CorruptionThorns#spike}）；
 * <li>处决腐败之心的一刀：刀光劈过，被劈开的那一侧喷出汁液；
 * <li>公主净化时腐败之心周围的一圈光：离下一次降下越近，亮起的那段越长，降下来时整圈都亮。
 * </ul>
 * 全部按世界坐标画，不贴屏幕。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class CorruptionFx {
    private static final RenderType GLOW = VfxParts.add("life_impact/glow"), HALO = VfxParts.add("life_impact/halo"), RUNE = VfxParts.add("charge/circle_11"),
            STREAK = VfxParts.add("force/streak"), LIQUID = VfxParts.blend("life_impact/glow"), SPLAT = VfxParts.blend("life_impact/glow");
    /** 汁液：暗的紫红，高光是粉的；预警的红光；净化的嫩绿。 */
    private static final int DARK = 0x8E1A4C, SHINE = 0xFF9CC8, WARN = 0xFF2E50, CORRUPT = 0xB4286E, PURE = 0x9FE38E, PURE_LIT = 0xD4F8BE;
    private record Timed(QueenSpellPayload cue, long start) {}
    private static final class Drop { Vec3 pos, prev, vel; float size; int age, life; }
    private record Splat(Vec3 at, float radius, long start, int life) {}
    private record Spray(Vec3 at, Vec3 dir, float scale, long start) {}
    private static final List<Timed> MARKS = new ArrayList<>(), FIELDS = new ArrayList<>(), PILLARS = new ArrayList<>(), CUTS = new ArrayList<>();
    private static final List<Drop> DROPS = new ArrayList<>();
    private static final List<Splat> SPLATS = new ArrayList<>();
    private static final List<Spray> SPRAYS = new ArrayList<>();
    /** 画成腐败荆棘的游脉编号，和听说的时刻。 */
    private static final Map<Integer, Long> VEINS = new HashMap<>();
    private static Level shown;
    private static long pillars, cuts, rings;
    private static final int MAX_DROPS = 700;

    private CorruptionFx() {}

    /** 测试用：画过几次光柱、几次处决的刀光、几帧净化之光。 */
    public static long pillars() { return pillars; }
    public static long cuts() { return cuts; }
    public static long rings() { return rings; }
    public static int drops() { return DROPS.size(); }

    /** 这一条游脉要不要画成腐败荆棘。 */
    public static boolean corruptVein(int id) { return VEINS.containsKey(id); }

    private static void sameLevel(Level level) {
        if (shown == level) return;
        shown = level;
        MARKS.clear(); FIELDS.clear(); PILLARS.clear(); CUTS.clear(); DROPS.clear(); SPLATS.clear(); SPRAYS.clear(); VEINS.clear();
    }

    /** {@code QueenCrimsonFx} 收到 {@code VEIN} 以后的提示转到这里。 */
    public static void receive(QueenSpellPayload cue) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        sameLevel(level);
        long now = level.getGameTime();
        switch (cue.kind()) {
            case QueenSpellPayload.VEIN -> VEINS.put(cue.ticks(), now);
            case QueenSpellPayload.THORN -> CorruptionThorns.spike(level, cue.at(), cue.scale());
            case QueenSpellPayload.FIELD -> add(FIELDS, new Timed(cue, now), 16);
            case QueenSpellPayload.PILLAR_MARK -> add(MARKS, new Timed(cue, now), 24);
            case QueenSpellPayload.PILLAR -> {
                add(PILLARS, new Timed(cue, now), 24);
                pillars++;
                splash(level, cue.aux(), cue.scale());
            }
            case QueenSpellPayload.CUT -> {
                add(CUTS, new Timed(cue, now), 6);
                cuts++;
                SPRAYS.add(new Spray(cue.at(), cue.aux().normalize(), cue.scale(), now));
            }
            default -> { }
        }
    }

    private static <T> void add(List<T> list, T item, int cap) {
        if (list.size() >= cap) list.removeFirst();
        list.add(item);
    }

    // ---------------------------------------------------------------- 液滴

    private static void drop(Vec3 at, Vec3 vel, float size, int life) {
        if (DROPS.size() >= MAX_DROPS) return;
        var d = new Drop();
        d.pos = at; d.prev = at; d.vel = vel; d.size = size; d.life = life;
        DROPS.add(d);
    }

    /** 光柱落地：一股往上冲的水柱、一圈往外甩的冠、地上一摊。 */
    private static void splash(Level level, Vec3 at, float reach) {
        RandomSource r = level.getRandom();
        for (int i = 0; i < 22; i++)
            drop(at.add((r.nextDouble() - .5) * .5, .1, (r.nextDouble() - .5) * .5),
                    new Vec3((r.nextDouble() - .5) * .08, .55 + r.nextDouble() * .4, (r.nextDouble() - .5) * .08), .09f + r.nextFloat() * .06f, 40);
        for (int i = 0; i < 34; i++) {
            double a = r.nextDouble() * Math.PI * 2, s = .18 + r.nextDouble() * .22;
            drop(at.add(Math.cos(a) * .4, .15, Math.sin(a) * .4), new Vec3(Math.cos(a) * s, .22 + r.nextDouble() * .22, Math.sin(a) * s),
                    .07f + r.nextFloat() * .05f, 40);
        }
        add(SPLATS, new Splat(at.add(0, .03, 0), Math.max(1.4f, reach * .85f), level.getGameTime(), 50), 48);
    }

    @SubscribeEvent static void tick(ClientTickEvent.Post e) {
        var level = Minecraft.getInstance().level;
        if (level == null) { shown = null; return; }
        sameLevel(level);
        long now = level.getGameTime();
        RandomSource r = level.getRandom();
        // 刀口喷出来的汁液：一刀之后 2–12 刻里一股股地喷。
        for (var spray : SPRAYS) {
            long age = now - spray.start;
            if (age < 2 || age > 12) continue;
            for (int i = 0; i < 9; i++) {
                double along = (r.nextDouble() - .5) * spray.scale * .9;
                Vec3 at = spray.at.add(spray.dir.scale(along));
                Vec3 out = at.subtract(spray.at).add(0, .2, 0);
                out = out.lengthSqr() < 1e-4 ? new Vec3(0, 1, 0) : out.normalize();
                Vec3 vel = spray.dir.scale(.6).add(out.scale(.55)).add(0, .15, 0).normalize().scale(.22 + r.nextDouble() * .3)
                        .add((r.nextDouble() - .5) * .08, (r.nextDouble() - .5) * .08, (r.nextDouble() - .5) * .08);
                drop(at, vel, .08f + r.nextFloat() * .07f, 50);
            }
        }
        SPRAYS.removeIf(s -> now - s.start > 12);
        var it = DROPS.iterator();
        while (it.hasNext()) {
            var d = it.next();
            d.prev = d.pos;
            d.vel = d.vel.add(0, -.05, 0).scale(.985);
            d.pos = d.pos.add(d.vel);
            BlockPos at = BlockPos.containing(d.pos);
            boolean landed = d.vel.y < 0 && !level.getBlockState(at).getCollisionShape(level, at).isEmpty();
            if (landed || ++d.age > d.life) {
                if (landed && r.nextFloat() < .3f) add(SPLATS, new Splat(new Vec3(d.pos.x, at.getY() + 1.02, d.pos.z), .2f + r.nextFloat() * .25f, now, 40), 48);
                it.remove();
            }
        }
        MARKS.removeIf(t -> now - t.start > t.cue.ticks() + 4);
        FIELDS.removeIf(t -> now - t.start > t.cue.ticks() + 10);
        PILLARS.removeIf(t -> now - t.start > t.cue.ticks());
        CUTS.removeIf(t -> now - t.start > 16);
        SPLATS.removeIf(s -> now - s.start > s.life);
        VEINS.values().removeIf(t -> now - t > 1200);
    }

    // ---------------------------------------------------------------- 画

    @SubscribeEvent(priority = EventPriority.LOWEST) static void draw(SubmitCustomGeometryEvent e) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        sameLevel(mc.level);
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double now = mc.level.getGameTime() + partial;
        var camera = e.getLevelRenderState().cameraRenderState;
        var out = ShaderPackOverlay.collector(e.getSubmitNodeCollector());
        PoseStack pose = e.getPoseStack();
        CorruptionThorns.drawSpikes(mc.level, now, camera, pose, out);
        for (Timed t : PILLARS) {
            float age = (float) (now - t.start);
            float k = age < 2 ? age / 2 : age < 6 ? 1 : Math.max(0, 1 - (age - 6) / 8f);
            CorruptionPillarVisual.submit(t.cue.at(), t.cue.aux(), .75f * k + .05f, Math.min(1, k * 1.4f), now, camera.pos, pose, out);
        }
        boolean anything = !MARKS.isEmpty() || !FIELDS.isEmpty() || !CUTS.isEmpty() || !DROPS.isEmpty() || !SPLATS.isEmpty();
        var view = VfxParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        if (anything) {
            for (Timed t : MARKS) {
                float age = (float) (now - t.start), life = Math.max(1, t.cue.ticks());
                float k = Math.clamp(age / life, 0, 1), alpha = Math.min(1, age / 3) * Math.max(0, 1 - Math.max(0, age - life) / 4);
                Vec3 at = t.cue.at().add(0, .06, 0);
                float r = t.cue.scale();
                // 红光：外圈往里收，到落点大小时光柱就来了；中间越来越亮。
                out.submitCustomGeometry(pose, HALO, (p, b) -> VfxParts.ground(p, b, at, r * (1.7f - .7f * k), 0, VfxParts.argb(alpha * .8f, WARN)));
                out.submitCustomGeometry(pose, GLOW, (p, b) -> VfxParts.ground(p, b, at.add(0, .02, 0), r * 1.1f, 0, VfxParts.argb(alpha * (.2f + .5f * k), WARN)));
            }
            for (Timed t : FIELDS) {
                float age = (float) (now - t.start), life = t.cue.ticks();
                float alpha = Math.min(1, age / 5) * Math.clamp((life + 10 - age) / 10, 0, 1);
                Vec3 at = t.cue.at().add(0, .05, 0);
                float r = Math.max(1, t.cue.scale());
                out.submitCustomGeometry(pose, RUNE, (p, b) -> VfxParts.ground(p, b, at, r * 1.15f, age * .02f, VfxParts.argb(alpha * .75f, CORRUPT)));
                out.submitCustomGeometry(pose, HALO, (p, b) -> VfxParts.ground(p, b, at.add(0, .02, 0), r * 1.3f, -age * .01f, VfxParts.argb(alpha * .55f, WARN)));
            }
            for (Splat s : SPLATS) {
                float age = (float) (now - s.start);
                float grow = Math.min(1, age / 5), alpha = Math.clamp(1 - (age - s.life * .4f) / (s.life * .6f), 0, 1);
                out.submitCustomGeometry(pose, SPLAT, (p, b) -> VfxParts.ground(p, b, s.at, s.radius * (.5f + .5f * grow), 0, VfxParts.argb(alpha * .75f, DARK)));
            }
            for (Timed t : CUTS) {
                float age = (float) (now - t.start);
                Vec3 c = t.cue.at(), dir = t.cue.aux().normalize();
                float half = t.cue.scale() * 1.3f;
                float reach = Math.min(1, age / 2);
                float alpha = age < 5 ? 1 : Math.max(0, 1 - (age - 5) / 9);
                Vec3 tail = c.subtract(dir.scale(half)), head = tail.add(dir.scale(half * 2 * reach));
                out.submitCustomGeometry(pose, STREAK, (p, b) -> {
                    VfxParts.streak(p, b, view, tail, head, .55f, VfxParts.argb(alpha * .75f, CORRUPT));
                    VfxParts.streak(p, b, view, tail, head, .16f, VfxParts.argb(alpha, 0xFFFFFF));
                });
                out.submitCustomGeometry(pose, GLOW, (p, b) -> VfxParts.sprite(p, b, view, c, 1.6f * (1 + age * .05f), 0, VfxParts.argb(alpha * .45f, SHINE)));
            }
            if (!DROPS.isEmpty()) {
                double p0 = partial;
                out.submitCustomGeometry(pose, LIQUID, (p, b) -> {
                    for (var d : DROPS) {
                        Vec3 at = d.prev.lerp(d.pos, p0), tail = at.subtract(d.vel.scale(1.4));
                        VfxParts.streak(p, b, view, tail, at, d.size * 2.1f, VfxParts.argb(.92f, DARK));
                    }
                });
                out.submitCustomGeometry(pose, STREAK, (p, b) -> {
                    for (var d : DROPS) {
                        Vec3 at = d.prev.lerp(d.pos, p0), tail = at.subtract(d.vel.scale(.8));
                        VfxParts.streak(p, b, view, tail, at, d.size * .8f, VfxParts.argb(.65f, SHINE));
                    }
                });
            }
        }
        rings(mc.level, now, partial, pose, out, view);
        pose.popPose();
    }

    /** 公主在祭坛上净化时，腐败之心周围的一圈光。 */
    private static void rings(Level level, double now, float partial, PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector out, VfxParts.View view) {
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        for (var e : Minecraft.getInstance().level.entitiesForRendering()) {
            if (!(e instanceof CorruptedElfQueen q) || q.phase() != 2 || q.position().distanceToSqr(eye) > 96 * 96) continue;
            if (!(level.getEntity(q.princessId()) instanceof ElfPrincess p) || !p.channeling()) continue;
            Vec3 c = q.coreCenter(partial);
            boolean down = q.purifyWindow() > 0;
            float lit = down ? 1 : q.purifyToNext();
            float pulse = down ? .75f + .25f * (float) Math.sin(now * .5) : 1;
            rings++;
            out.submitCustomGeometry(pose, RUNE, (pp, b) -> VfxParts.ground(pp, b, c, 4.6f, (float) (now * .006), VfxParts.argb(.6f * pulse, PURE)));
            out.submitCustomGeometry(pose, GLOW, (pp, b) -> {
                int n = 40;
                for (int i = 0; i < n; i++) {
                    float at = i / (float) n;
                    double a = at * Math.PI * 2 + now * .01;
                    Vec3 mote = c.add(Math.cos(a) * 4.9, Math.sin(now * .08 + i) * .06, Math.sin(a) * 4.9);
                    boolean on = at <= lit;
                    VfxParts.sprite(pp, b, view, mote, on ? .42f : .2f, 0, VfxParts.argb(on ? .95f * pulse : .18f, on ? PURE_LIT : PURE));
                }
            });
        }
    }
}
