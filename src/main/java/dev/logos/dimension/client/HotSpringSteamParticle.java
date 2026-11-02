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
 * 温泉水蒸气粒子：
 * 缓缓向上升起、轻柔摆动膨胀并在空中自然消散的半透明白雾蒸汽。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class HotSpringSteamParticle extends SingleQuadParticle {
    private final SpriteSet sprites;
    private final float baseSize;
    private final double swayOffset;

    private HotSpringSteamParticle(ClientLevel level, double x, double y, double z, double dx, double dy, double dz,
                                   SpriteSet sprites, RandomSource random) {
        super(level, x, y, z, sprites.first());
        this.sprites = sprites;
        this.xd = dx + (random.nextDouble() - 0.5) * 0.012;
        this.yd = dy + 0.022 + random.nextDouble() * 0.024;
        this.zd = dz + (random.nextDouble() - 0.5) * 0.012;
        this.lifetime = 50 + random.nextInt(35);
        this.baseSize = 0.18f + random.nextFloat() * 0.12f;
        this.quadSize = this.baseSize;
        this.swayOffset = random.nextDouble() * Math.PI * 2.0;
        this.hasPhysics = false;
        this.setColor(0.97f, 0.98f, 1.0f);
        this.alpha = 0.0f;
    }

    @SubscribeEvent
    static void providers(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(DimensionParticles.STEAM.get(), HotSpringSteamParticle::provider);
    }

    public static ParticleProvider<SimpleParticleType> provider(SpriteSet sprites) {
        return (options, level, x, y, z, dx, dy, dz, random) ->
                new HotSpringSteamParticle(level, x, y, z, dx, dy, dz, sprites, random);
    }

    @Override
    public Layer getLayer() {
        return Layer.TRANSLUCENT;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }

        this.setSpriteFromAge(this.sprites);

        // 缓慢向上升腾并微风漂移
        double sway = Math.sin((double) this.age * 0.08 + this.swayOffset) * 0.005;
        this.xd += sway;
        this.zd += sway;
        this.move(this.xd, this.yd, this.zd);

        // 蒸汽扩散变大
        float progress = (float) this.age / (float) this.lifetime;
        this.quadSize = this.baseSize * (1.0f + progress * 1.8f);

        // 透明度：前 15 刻淡入至 0.42，随后平缓淡出至 0
        if (this.age < 15) {
            this.alpha = Mth.lerp((float) this.age / 15.0f, 0.0f, 0.42f);
        } else {
            this.alpha = Mth.lerp((float) (this.age - 15) / (float) (this.lifetime - 15), 0.42f, 0.0f);
        }
    }
}
