package dev.logos.dimension.cloudsea;

import dev.logos.dimension.LogosDimension;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Eight Minecraft days per season; the thread context limits temperature changes to this dimension. */
@EventBusSubscriber(modid=LogosDimension.ID)
public final class JourneySeasons {
    public static final long SEASON_TICKS=192_000;
    public static final ResourceKey<WorldClock> CLOCK=ResourceKey.create(Registries.WORLD_CLOCK,LogosDimension.id("cloudsea"));
    public static final ThreadLocal<Integer> CONTEXT=new ThreadLocal<>();
    public static long ticks(Level level){return level.clockManager().getTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(CLOCK));}
    public static int at(long ticks){return (int)(Math.floorMod(ticks,SEASON_TICKS*4)/SEASON_TICKS);}
    public static int of(Level level){return at(ticks(level));}
    public static void enter(Level level){if(level!=null&&level.dimension().equals(LogosDimension.CLOUDSEA))CONTEXT.set(of(level));else CONTEXT.remove();}
    @SubscribeEvent static void pre(LevelTickEvent.Pre event){enter(event.getLevel());}
    @SubscribeEvent static void post(LevelTickEvent.Post event){CONTEXT.remove();}
    private JourneySeasons(){}
}
