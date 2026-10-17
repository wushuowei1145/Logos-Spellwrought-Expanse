package dev.logos.dimension.client;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** One local ambient mote at most every six ticks; no entities, world scans or worker threads. */
@EventBusSubscriber(modid=LogosDimension.ID,value=Dist.CLIENT)
public final class JourneyAtmosphere {
    private static int ticks;
    private static long emitted;
    public static long emitted(){return emitted;}
    @SubscribeEvent static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null||!mc.level.dimension().equals(LogosDimension.CLOUDSEA))return;
        int setting=mc.options.particles().get().ordinal();if(setting>=2||++ticks%(setting==1?12:6)!=0)return;
        var level=mc.level;var random=level.getRandom();var origin=mc.player.blockPosition();
        for(int i=0;i<4;i++){
            int x=origin.getX()+random.nextInt(25)-12,z=origin.getZ()+random.nextInt(25)-12;
            if(!level.hasChunk(x>>4,z>>4))continue;
            int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
            if(Math.abs(y-origin.getY())>12)continue;
            var pos=new BlockPos(x,y,z);var block=level.getBlockState(pos).getBlock();
            if(!(block instanceof JourneyPlantBlock)&&!(level.getBlockState(pos.below()).getBlock() instanceof SeasonalTurfBlock))continue;
            int season=JourneySeasons.of(level);long day=Math.floorMod(JourneySeasons.ticks(level),24000);
            var particle=season==3?ParticleTypes.END_ROD:day>13000?ParticleTypes.GLOW:ParticleTypes.SPORE_BLOSSOM_AIR;
            level.addParticle(particle,x+random.nextDouble(),y+.3+random.nextDouble()*1.4,z+random.nextDouble(),0,.003,0);
            emitted++;break;
        }
    }
    private JourneyAtmosphere(){}
}
