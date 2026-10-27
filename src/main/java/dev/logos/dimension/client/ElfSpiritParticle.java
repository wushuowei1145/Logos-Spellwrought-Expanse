package dev.logos.dimension.client;

import dev.logos.dimension.DimensionParticles;
import dev.logos.dimension.LogosDimension;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

/**
 * 森林精灵倒下时从身上飘出的绿色光点：小、全亮、半透明，带着初速度散开后慢慢向上飘、轻轻左右摆，淡入淡出。
 * 贴图是代码绘制的白色实心圆点（tools/forest_elf_resources.py），这里染成叶绿色。由 {@link dev.logos.dimension.npc.ForestElf} 在客户端生成。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class ElfSpiritParticle extends SingleQuadParticle {
    /** 生成过的光点数（客户端测试用）。 */
    private static long created;
    private final float baseSize;
    private final double sway;

    private ElfSpiritParticle(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites,
                              RandomSource random) {
        super(level, x, y, z, sprites.first());
        xd = dx;
        yd = dy;
        zd = dz;
        lifetime = 28 + random.nextInt(24);
        baseSize = quadSize = (.035f + random.nextFloat() * .035f);
        sway = random.nextDouble() * Math.PI * 2;
        hasPhysics = false;
        // 叶绿到嫩绿之间
        float t = random.nextFloat();
        setColor(Mth.lerp(t, .42f, .70f), Mth.lerp(t, .86f, 1f), Mth.lerp(t, .36f, .55f));
        alpha = 0;
        created++;
    }

    public static long created() {
        return created;
    }

    @SubscribeEvent
    static void providers(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(DimensionParticles.ELF_SPIRIT.get(), ElfSpiritParticle::provider);
    }

    private static ParticleProvider<SimpleParticleType> provider(SpriteSet sprites) {
        return (options, level, x, y, z, dx, dy, dz, random) -> new ElfSpiritParticle(level, x, y, z, dx, dy, dz, sprites, random);
    }

    @Override
    public void tick() {
        xo = x;
        yo = y;
        zo = z;
        if (age++ >= lifetime) {
            remove();
            return;
        }
        // 初速度很快衰减，之后以向上漂浮为主，带一点左右摆动
        xd = xd * .9 + Math.cos(sway + age * .25) * .0015;
        zd = zd * .9 + Math.sin(sway + age * .25) * .0015;
        yd = yd * .9 + .0025;
        move(xd, yd, zd);
        float life = (float) age / lifetime;
        alpha = Math.min(1, age / 4f) * (1 - life * life);
        quadSize = baseSize * (1 - .4f * life);
    }

    @Override
    public Layer getLayer() {
        return Layer.TRANSLUCENT;
    }

    @Override
    public int getLightCoords(float partialTick) {
        return 0xF000F0;
    }
}
