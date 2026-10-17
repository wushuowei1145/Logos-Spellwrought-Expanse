package dev.logos.dimension;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Native particles only; no ambient mob entities, ticking blocks or network broadcasts. */
public final class DimensionParticles {
    private static final DeferredRegister<ParticleType<?>> TYPES=DeferredRegister.create(Registries.PARTICLE_TYPE,LogosDimension.ID);
    public static final DeferredHolder<ParticleType<?>,SimpleParticleType> FIREFLY=TYPES.register("journey_firefly",()->new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>,SimpleParticleType> MOTE=TYPES.register("journey_mana_mote",()->new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>,SimpleParticleType> GLIMMER=TYPES.register("journey_plant_glimmer",()->new SimpleParticleType(false));
    public static void register(IEventBus bus){TYPES.register(bus);}
    private DimensionParticles(){}
}
