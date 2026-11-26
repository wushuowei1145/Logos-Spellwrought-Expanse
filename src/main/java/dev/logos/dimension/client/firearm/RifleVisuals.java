package dev.logos.dimension.client.firearm;

import dev.logos.dimension.firearm.Ammo;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/**
 * 步枪弹的画面：一条细长的弹道一直画到最远的落点（穿透时经过的几个生物各溅一下暴击星），终点碎屑或暴击星。
 * 比霰弹的一蓬更密、更长，看得出是一颗子弹打远处。
 */
final class RifleVisuals {
    private RifleVisuals() {}

    static void draw(ClientLevel level, Vec3 muzzle, List<Ammo.Trace> traces) {
        Ammo.Trace far = null;
        double farthest = -1;
        for (var t : traces) {
            double d = t.end().distanceToSqr(muzzle);
            if (d > farthest) { farthest = d; far = t; }
            AmmoVisuals.impact(level, t);
        }
        if (far != null) AmmoVisuals.tracer(level, muzzle, far.end(), .5);
    }
}
