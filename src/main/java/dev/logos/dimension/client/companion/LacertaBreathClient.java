package dev.logos.dimension.client.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.foxfire.FoxfireClient;
import dev.logos.dimension.companion.LacertaCompanion;
import dev.logos.dimension.companion.LacertaDragon;
import dev.logos.dimension.foxfire.FoxfireFlame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 拉塞塔（巨龙）的古龙龙炎吐息（docs/LACERTA_COMPANION.md 第 19 节）：每刻沿她同步过来的喷射方向撒一股股龙炎喷流
 * （{@link FoxfireClient#jet}，顺着方向拉长的火焰）和熔金火星，从嘴里喷出、越远越粗（不用原版粒子）。蓄压时嘴边先聚起几点火星。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class LacertaBreathClient {
    /** 火舌往外伸的速度（格/刻）：刚开喷时一道火从嘴里推出去，几刻后喷满。 */
    private static final double REACH_SPEED = 3;
    /** 每刻撒多少：嘴边的焰心、沿途的火舌、往前飞的火星。 */
    private static final int CORE = 4, BODY = 20, SPARKS = 3;
    /** 测试用：撒过多少点。 */
    private static int streamed;

    private LacertaBreathClient() {}

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) return;
        for (Entity e : level.entitiesForRendering()) {
            if (e instanceof LacertaCompanion l && l.isDragonForm() && l.breathingFire()) stream(level, l);
        }
    }

    private static void stream(ClientLevel level, LacertaCompanion l) {
        RandomSource r = level.getRandom();
        long age = level.getGameTime() - l.breathChangedAt();
        Vec3 mouth = LacertaDragon.mouth(l.position(), l.yBodyRot);
        if (age < LacertaDragon.BREATH_START) {
            // 蓄压：火星从四周往嘴里聚，嘴边一小簇火舌
            Vec3 off = new Vec3(r.nextDouble() - .5, r.nextDouble() - .5, r.nextDouble() - .5).normalize().scale(.9 + r.nextDouble() * .5);
            FoxfireClient.ember(FoxfireFlame.DRAGON, true, mouth.add(off), off.scale(-1 / 8d), 8, .05f + r.nextFloat() * .03f);
            if (age > LacertaDragon.BREATH_START / 2) {
                FoxfireClient.ember(FoxfireFlame.DRAGON, false, mouth, new Vec3(0, .02, 0), 6, .12f + r.nextFloat() * .06f);
            }
            streamed += 2;
            return;
        }
        Vec3 dir = l.breathDir();
        double reach = Math.min(LacertaDragon.BREATH_RANGE, 4 + (age - LacertaDragon.BREATH_START) * REACH_SPEED);
        // 嘴边细而亮的焰心
        for (int i = 0; i < CORE; i++) {
            Vec3 at = mouth.add(dir.scale(r.nextDouble() * 1.2)).add(disk(r, dir, .1));
            FoxfireClient.jet(FoxfireFlame.DRAGON, at, dir.scale(.9), 3 + r.nextInt(2), .2f + r.nextFloat() * .08f);
        }
        // 沿途一股股往前冲的喷流：出生点铺满整条（一开喷就连着），飞完正好到喷到的地方，越远越粗
        for (int i = 0; i < BODY; i++) {
            double speed = .8 + r.nextDouble() * .4;
            int life = 5 + r.nextInt(4);
            double d = Math.max(0, reach - speed * life * .8) * r.nextDouble();
            Vec3 at = mouth.add(dir.scale(d)).add(disk(r, dir, .15 + d * .16));
            float size = (float) ((.3 + .7 * d / LacertaDragon.BREATH_RANGE) * (.8 + .4 * r.nextDouble()));
            FoxfireClient.jet(FoxfireFlame.DRAGON, at, dir.scale(speed).add(disk(r, dir, .06)), life, size);
        }
        for (int i = 0; i < SPARKS; i++) {
            Vec3 vel = dir.scale(1 + r.nextDouble() * .4).add(disk(r, dir, .12));
            FoxfireClient.ember(FoxfireFlame.DRAGON, true, mouth, vel, 10 + r.nextInt(6), .05f + r.nextFloat() * .04f);
        }
        streamed += CORE + BODY + SPARKS;
    }

    /** 垂直于 {@code dir}、半径 {@code radius} 的圆盘里随便一点。 */
    private static Vec3 disk(RandomSource r, Vec3 dir, double radius) {
        Vec3 v = new Vec3(r.nextDouble() - .5, r.nextDouble() - .5, r.nextDouble() - .5);
        v = v.subtract(dir.scale(v.dot(dir)));
        if (v.lengthSqr() < 1e-6) return Vec3.ZERO;
        return v.normalize().scale(radius * Math.sqrt(r.nextDouble()));
    }

    /** 测试用：撒过多少点龙炎。 */
    public static int streamed() {
        return streamed;
    }
}
