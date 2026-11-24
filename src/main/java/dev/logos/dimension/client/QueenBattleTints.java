package dev.logos.dimension.client;

import dev.logos.dimension.elven.blight.CorruptedElfQueen;
import dev.logos.dimension.elven.blight.HeartPurification;
import dev.logos.dimension.elven.blight.QueenBattleRules;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * 女王战里交给世界球体着色（{@link WorldTintScreen}）的球（docs/CORRUPTED_QUEEN_REMAKE.md 第 6 节）：
 * 公主身边的净化圈、腐败之心每次搏动推出的一道淡淡的去饱和波、狂乱时从中心漫开的腐败。都按同步来的状态算，不另发包。
 */
final class QueenBattleTints {
    /** 离镜头这么远的战斗不画。 */
    private static final double RANGE = 96;

    private QueenBattleTints() {}

    private static boolean fighting(CorruptedElfQueen q, Vec3 eye) {
        return (q.phase() == 2 || q.phase() == 3) && q.position().distanceToSqr(eye) < RANGE * RANGE;
    }

    static boolean active() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        Vec3 eye = mc.gameRenderer.getMainCamera().position();
        for (var e : mc.level.entitiesForRendering()) if (e instanceof CorruptedElfQueen q && fighting(q, eye)) return true;
        return false;
    }

    static void collect(List<WorldTintScreen.Sphere> out) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 eye = mc.gameRenderer.getMainCamera().position();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (var e : mc.level.entitiesForRendering()) {
            if (!(e instanceof CorruptedElfQueen q) || !fighting(q, eye)) continue;
            // 净化圈：跟着公主，随进度扩大。
            if (mc.level.getEntity(q.princessId()) instanceof net.minecraft.world.entity.LivingEntity p && p.isAlive())
                out.add(new WorldTintScreen.Sphere(p.getPosition(partial).add(0, .4, 0), (float) HeartPurification.circle(q.purifyProgress()),
                        WorldTintScreen.Mode.PURIFY, .85f, 0));
            // 搏动波：和悬吊模型同一个节拍（阶段二起快 1.6 倍），峰值后 2 秒内从心脏推出去。
            float beatTime = (mc.level.getGameTime() % 100000 + partial) * 1.6f;
            float since = (float) ((beatTime % 100) - 24) / 1.6f;
            if (since >= 0 && since < 40) out.add(new WorldTintScreen.Sphere(q.corePosition(partial).add(0, 2.5, 0), 3 + since * .75f,
                    WorldTintScreen.Mode.PULSE, .32f * (1 - since / 40), 2.5f));
            // 狂乱：腐败从中心往外漫，快淹到祭坛（服务端的脉络也按这个半径长）。
            if (q.phase() == QueenBattleRules.FRENZY) {
                double reach = QueenBattleRules.frenzyReach(q.phaseAge(partial), q.frenzyLimit());
                out.add(new WorldTintScreen.Sphere(q.arena().add(0, .5, 0), (float) reach + 1.5f, WorldTintScreen.Mode.TAINT,
                        .5f * QueenBattleRules.smooth(q.phaseAge(partial) / 40), 0));
            }
        }
    }
}
