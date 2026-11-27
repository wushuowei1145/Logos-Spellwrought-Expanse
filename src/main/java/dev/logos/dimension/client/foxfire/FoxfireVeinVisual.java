package dev.logos.dimension.client.foxfire;

import dev.logos.client.LiveVein;
import dev.logos.client.VeinVisual;
import dev.logos.dimension.foxfire.FoxfireFlame;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 狐火术式的游脉（{@code FoxfireVeinVisualsMixin} 换上）：和 Logos 各元素共用的游脉一样，沿着爬过的地面一路冒火、
 * 头上往上蹿、带起地面的碎屑，停在什么东西上时炸一下——只是冒的是狐火：幽蓝用原版的灵魂火焰，白梅的淡粉用淡粉的尘和樱花瓣，龙炎全是自己画的火舌和熔金火星（地面碎屑也换成迸出的火星，不用原版粒子）。
 */
final class FoxfireVeinVisual implements VeinVisual {
    /** 每爬这么远冒一点火。 */
    private static final double SPACING = .3;

    private final FoxfireFlame flame;

    FoxfireVeinVisual(FoxfireFlame flame) {
        this.flame = flame;
    }

    @Override
    public boolean tick(Level level, LiveVein vein) {
        Vec3 n = vein.normal();
        RandomSource r = level.getRandom();
        if (vein.ended()) {
            if (vein.sinceEnd() == 1 && vein.struck()) {
                Vec3 at = vein.pos().add(n.scale(vein.height() / 2));
                for (int i = 0; i < 10; i++) flame(level, r, at, vein.width() / 2, .06);
            }
            return false;
        }
        Vec3 from = vein.prev(), to = vein.pos();
        // 横过它所在的面、和前进方向垂直
        Vec3 side = vein.dir().cross(n);
        if (side.lengthSqr() < 1e-6) side = new Vec3(-vein.dir().z, 0, vein.dir().x);
        side = side.lengthSqr() < 1e-6 ? Vec3.ZERO : side.normalize();
        int motes = Math.max(1, (int) Math.ceil(from.distanceTo(to) / SPACING));
        for (int k = 0; k < motes; k++) {
            Vec3 at = from.lerp(to, (k + r.nextDouble()) / motes).add(side.scale((r.nextDouble() - .5) * vein.width()));
            flame(level, r, at.add(n.scale(.08)), .03, .015);
            if (k % 2 == 0) {
                if (flame == FoxfireFlame.DRAGON) sparks(r, at, n);
                else crumb(level, r, at, n);
            }
        }
        Vec3 head = to.add(n.scale(vein.height() / 2));
        for (int i = 0; i < 2; i++) flame(level, r, head, Math.min(vein.width(), vein.height()) / 3, .03);
        return true;
    }

    /** 一点狐火，往上飘 {@code up}。 */
    private void flame(Level level, RandomSource r, Vec3 at, double spread, double up) {
        double x = at.x + (r.nextDouble() - .5) * 2 * spread, y = at.y + (r.nextDouble() - .5) * 2 * spread, z = at.z + (r.nextDouble() - .5) * 2 * spread;
        if (flame == FoxfireFlame.DRAGON) {
            // 龙炎不用原版粒子：一小簇自己画的火舌，偶尔一点熔金火星
            FoxfireClient.ember(flame, false, new Vec3(x, y, z), new Vec3(0, up + .015, 0), 9 + r.nextInt(7), .16f + r.nextFloat() * .1f);
            if (r.nextInt(3) == 0) FoxfireClient.ember(flame, true, new Vec3(x, y, z),
                    new Vec3((r.nextDouble() - .5) * .03, up * 2 + .03, (r.nextDouble() - .5) * .03), 12 + r.nextInt(10), .035f + r.nextFloat() * .02f);
            return;
        }
        if (flame == FoxfireFlame.KITSUNE) {
            level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, 0, up, 0);
            return;
        }
        level.addParticle(new DustParticleOptions(flame.core, 1.2f), x, y, z, 0, up, 0);
        if (r.nextInt(4) == 0) level.addParticle(ParticleTypes.CHERRY_LEAVES, x, y, z, 0, 0, 0);
    }

    /** 龙炎游脉换掉地面碎屑：从地里迸出几点火星，顺着面的法线往外溅。 */
    private void sparks(RandomSource r, Vec3 at, Vec3 n) {
        for (int i = 0; i < 2; i++) {
            Vec3 jitter = new Vec3((r.nextDouble() - .5) * .12, (r.nextDouble() - .5) * .12, (r.nextDouble() - .5) * .12);
            Vec3 v = n.scale(.07 + r.nextDouble() * .08).add(jitter.subtract(n.scale(jitter.dot(n))));
            FoxfireClient.ember(flame, true, at.add(n.scale(.05)), v, 8 + r.nextInt(8), .03f + r.nextFloat() * .025f);
        }
    }

    /** 带起一点地面的碎屑（同 Logos 共用的游脉）。 */
    private static void crumb(Level level, RandomSource r, Vec3 at, Vec3 n) {
        BlockState ground = level.getBlockState(BlockPos.containing(at.subtract(n.scale(.1))));
        if (ground.isAir() || !ground.getFluidState().isEmpty()) return;
        Vec3 p = at.add(n.scale(.05));
        Vec3 jitter = new Vec3((r.nextDouble() - .5) * .1, (r.nextDouble() - .5) * .1, (r.nextDouble() - .5) * .1);
        Vec3 v = n.scale(.1 + r.nextDouble() * .1).add(jitter.subtract(n.scale(jitter.dot(n))));
        level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, ground), p.x, p.y, p.z, v.x, v.y, v.z);
    }
}
