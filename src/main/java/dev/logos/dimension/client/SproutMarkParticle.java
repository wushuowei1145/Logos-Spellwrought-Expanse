package dev.logos.dimension.client;

import dev.logos.dimension.DimensionParticles;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.item.NatureAttachments;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** 附着于已同步萌芽印记的小叶片，由客户端低频生成，随印记消耗或到期停止。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class SproutMarkParticle extends SingleQuadParticle {
    private static long created;
    private SproutMarkParticle(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
        super(level, x, y, z, sprites.first());
        lifetime = 18; quadSize = .11f; yd = .006; hasPhysics = false; created++;
    }
    public static long created() { return created; }

    @SubscribeEvent static void providers(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(DimensionParticles.SPROUT_MARK.get(), sprites ->
                (options, level, x, y, z, dx, dy, dz, random) -> new SproutMarkParticle(level, x, y, z, sprites));
    }

    @SubscribeEvent static void targetTick(EntityTickEvent.Post event) {
        var entity = event.getEntity();
        if (!entity.level().isClientSide() || !(entity instanceof LivingEntity)
                || entity.tickCount % 10 != 0 || !entity.hasData(NatureAttachments.SPROUT_MARK)) return;
        if (!entity.getData(NatureAttachments.SPROUT_MARK).active(entity.level().getGameTime())) return;
        double phase = entity.tickCount * .12;
        entity.level().addParticle(DimensionParticles.SPROUT_MARK.get(),
                entity.getX() + Math.cos(phase) * .28, entity.getY() + entity.getBbHeight() * .7,
                entity.getZ() + Math.sin(phase) * .28, 0, 0, 0);
    }

    @Override public void tick() {
        xo = x; yo = y; zo = z;
        if (age++ >= lifetime) { remove(); return; }
        move(0, yd, 0);
        alpha = Math.min(1, age / 3f) * (1 - (float) age / lifetime);
    }
    @Override public Layer getLayer() { return Layer.TRANSLUCENT; }
    @Override public int getLightCoords(float partialTick) { return 0xF000F0; }
}
