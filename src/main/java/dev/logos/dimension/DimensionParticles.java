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
    public static final DeferredHolder<ParticleType<?>,SimpleParticleType> SPORE=TYPES.register("journey_luminous_spore",()->new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>,SimpleParticleType> SIGIL=TYPES.register("journey_woodland_sigil",()->new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>,SimpleParticleType> RIPPLE=TYPES.register("journey_silver_ripple",()->new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>,SimpleParticleType> WISP=TYPES.register("journey_water_wisp",()->new SimpleParticleType(false));
    /** 森林精灵倒下时飘出的绿色光点（ForestElf 在客户端生成，不走网络）。 */
    public static final DeferredHolder<ParticleType<?>,SimpleParticleType> ELF_SPIRIT=TYPES.register("forest_elf_spirit",()->new SimpleParticleType(false));
    public static void register(IEventBus bus){TYPES.register(bus);}
    private DimensionParticles(){}
}
