package dev.logos.dimension.client.firearm;

import dev.logos.dimension.firearm.Ammo;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;

/** 霰弹的画面：八条短促的弹道（只画前面一段，散开的一蓬），终点碎屑和暴击星；铳口前再喷一团火星。 */
final class BuckshotVisuals {
    private BuckshotVisuals() {}

    static void draw(ClientLevel level, Vec3 muzzle, List<Ammo.Trace> traces) {
        var r = level.getRandom();
        for (var t : traces) {
            var d = t.end().subtract(muzzle);
            double len = d.length();
            if (len < 1e-3) continue;
            var dir = d.scale(1 / len);
            // 弹道只画前 10 格，远处的一颗颗分不清，只看得到命中点。
            AmmoVisuals.tracer(level, muzzle, muzzle.add(dir.scale(Math.min(len, 10))), .9);
            AmmoVisuals.impact(level, t);
            for (int i = 0; i < 2; i++) {
                var p = muzzle.add(dir.scale(.4 + r.nextDouble() * .8));
                level.addParticle(ParticleTypes.SMALL_FLAME, p.x, p.y, p.z, dir.x * .2, dir.y * .2, dir.z * .2);
            }
        }
    }
}
