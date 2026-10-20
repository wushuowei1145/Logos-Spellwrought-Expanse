package dev.logos.dimension.client;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.JourneySeasons;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Native sun, weather and thin clouds; native sky with local vegetation-bound magic particles. */
@EventBusSubscriber(modid=LogosDimension.ID,value=Dist.CLIENT)
public final class CloudseaClient {
    public static final ResourceKey<WorldClock> CLOCK=JourneySeasons.CLOCK;
    public static long ticks(Level level){return JourneySeasons.ticks(level);}
    @SubscribeEvent static void beforeFrame(RenderFrameEvent.Pre event){JourneySeasons.enter(Minecraft.getInstance().level);}
    @SubscribeEvent static void afterFrame(RenderFrameEvent.Post event){JourneySeasons.CONTEXT.remove();}
    private CloudseaClient(){}
}
