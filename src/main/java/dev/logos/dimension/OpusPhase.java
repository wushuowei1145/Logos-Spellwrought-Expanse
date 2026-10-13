package dev.logos.dimension;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.Level;

/** The dimension has its own clock; Overworld sleeping and time changes do not skip its work. */
public enum OpusPhase {
    NIGREDO, ALBEDO, CITRINITAS, RUBEDO;
    public static final int PHASE_TICKS = 24_000, PERIOD_TICKS = PHASE_TICKS * 4;
    public static final ResourceKey<WorldClock> CLOCK = ResourceKey.create(Registries.WORLD_CLOCK, LogosDimension.id("opus"));

    public static long ticks(Level level) {
        return level.clockManager().getTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(CLOCK));
    }
    public static OpusPhase at(long ticks) { return values()[(int) (Math.floorMod(ticks, PERIOD_TICKS) / PHASE_TICKS)]; }
    public static OpusPhase of(Level level) { return at(ticks(level)); }
    public String key() { return "phase.logos_dimension." + name().toLowerCase(java.util.Locale.ROOT); }
}
