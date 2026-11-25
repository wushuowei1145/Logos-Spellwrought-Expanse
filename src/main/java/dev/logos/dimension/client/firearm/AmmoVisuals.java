package dev.logos.dimension.client.firearm;

import dev.logos.dimension.firearm.Ammo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * 各种弹药打出去的画面（客户端）：按弹药物品编号注册，铳口火光由铳统一画（{@link FirearmClient}），
 * 弹道和命中由弹药自己画。没注册画法的弹药用 {@link #DEFAULT}（细弹道加命中碎屑）。
 */
public final class AmmoVisuals {
    private AmmoVisuals() {}

    /** 一发的画法：{@code muzzle} 是画面上的铳口，{@code traces} 是服务端给的各条弹道终点。 */
    public interface Visual {
        void draw(ClientLevel level, Vec3 muzzle, List<Ammo.Trace> traces);
    }

    private static final Map<Identifier, Visual> VISUALS = new HashMap<>();

    public static void register(Identifier ammo, Visual visual) { VISUALS.put(ammo, visual); }
    public static Visual get(Identifier ammo) { return VISUALS.getOrDefault(ammo, DEFAULT); }

    /** 默认：每条弹道一串小火星，终点打在方块上溅碎屑、打在生物上溅暴击星。 */
    public static final Visual DEFAULT = (level, muzzle, traces) -> {
        for (var t : traces) {
            tracer(level, muzzle, t.end(), .7);
            impact(level, t);
        }
    };

    /** 一条弹道：从铳口到终点每隔 {@code step} 格一颗往前飘的小火星。 */
    public static void tracer(ClientLevel level, Vec3 from, Vec3 to, double step) {
        var d = to.subtract(from);
        double len = d.length();
        if (len < 1e-3) return;
        var dir = d.scale(1 / len);
        for (double s = 1.2; s < len; s += step) {
            var p = from.add(dir.scale(s));
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, dir.x * .15, dir.y * .15, dir.z * .15);
        }
    }

    /** 终点：方块碎屑和一缕烟，或者生物身上的暴击星。 */
    public static void impact(ClientLevel level, Ammo.Trace t) {
        var p = t.end();
        var r = level.getRandom();
        switch (t.kind()) {
            case Ammo.Trace.BLOCK -> {
                var state = Block.stateById(t.block());
                for (int i = 0; i < 4; i++)
                    level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), p.x, p.y, p.z,
                            (r.nextDouble() - .5) * .3, r.nextDouble() * .25, (r.nextDouble() - .5) * .3);
                level.addParticle(ParticleTypes.SMOKE, p.x, p.y, p.z, 0, .02, 0);
            }
            case Ammo.Trace.ENTITY -> {
                for (int i = 0; i < 3; i++)
                    level.addParticle(ParticleTypes.CRIT, p.x, p.y, p.z, (r.nextDouble() - .5) * .4, r.nextDouble() * .3, (r.nextDouble() - .5) * .4);
            }
            default -> {}
        }
    }
}
