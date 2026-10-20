package dev.logos.dimension.client;

import dev.logos.dimension.DimensionParticles;
import dev.logos.dimension.cloudsea.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.*;
import net.minecraft.tags.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Local encounters with existing plants and water. Never places or changes a block. */
public final class JourneyMagicDetails {
    private static final List<BlockPos> PROBES=new ArrayList<>();
    private static final LinkedHashMap<Long,Long> RECENT=new LinkedHashMap<>();
    private static int cursor;private static long nextPlant,nextWater,nextWisp,nextSound,reactions,sounds;
    private static Vec3 lastFoot;
    static{
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=-1;y<=1;y++)PROBES.add(new BlockPos(x,y,z));
        PROBES.sort(Comparator.comparingInt(p->p.getX()*p.getX()+p.getZ()*p.getZ()+p.getY()*p.getY()));
    }
    public static long reactions(){return reactions;}
    public static long sounds(){return sounds;}
    static void clear(){RECENT.clear();cursor=0;nextPlant=nextWater=nextWisp=nextSound=0;lastFoot=null;}
    static void tick(ClientLevel level){
        var mc=Minecraft.getInstance();int setting=mc.options.particles().get().ordinal();long frame=JourneyAtmosphere.frame();
        if(setting>=2||mc.player==null||mc.player.isSpectator()){lastFoot=null;return;}
        if(frame%(setting==1?8:4)!=0)return;
        var origin=mc.player.blockPosition();int season=JourneySeasons.of(level);
        boolean night=AmbientMotion.night(JourneySeasons.ticks(level))>.2;
        for(int i=0;i<8;i++){
            var pos=origin.offset(PROBES.get(cursor));cursor=(cursor+1)%PROBES.size();
            if(!level.hasChunk(pos.getX()>>4,pos.getZ()>>4))continue;
            var state=level.getBlockState(pos);
            if(state.getFluidState().is(FluidTags.WATER)&&state.getFluidState().isSource()&&level.isEmptyBlock(pos.above())){
                if(frame>=nextWater&&ready(pos,frame,160)){
                    level.addParticle(DimensionParticles.RIPPLE.get(),pos.getX()+.35+level.getRandom().nextDouble()*.3,pos.getY()+.91,pos.getZ()+.35+level.getRandom().nextDouble()*.3,0,0,0);nextWater=frame+40;
                    if(night)wisp(level,pos.above(),frame);
                }
                continue;
            }
            if(frame<nextPlant||mc.player.distanceToSqr(pos.getCenter())>3.4*3.4)continue;
            boolean mushroom=state.is(CloudseaBlocks.GLOWCAP.get())||state.is(CloudseaBlocks.LUMEN_FERN.get());
            boolean flower=(state.is(CloudseaBlocks.MOONBELL.get())||state.is(CloudseaBlocks.ANEMONE.get()))&&season<2
                    ||state.is(CloudseaBlocks.HELLEBORE.get())&&(season==0||season==3)||state.is(CloudseaBlocks.BLUEBELL.get())||state.is(CloudseaBlocks.STARDEW.get());
            if(!(mushroom&&season!=3&&(night||level.getMaxLocalRawBrightness(pos)<9)||flower))continue;
            if(!ready(pos,frame,mushroom?240:400))continue;
            nextPlant=frame+50;reactions++;
            if(mushroom){
                for(int n=0;n<(setting==1?2:4);n++)level.addParticle(DimensionParticles.SPORE.get(),pos.getX()+.25+level.getRandom().nextDouble()*.5,pos.getY()+.75,pos.getZ()+.25+level.getRandom().nextDouble()*.5,0,0,0);
            }else{
                for(int n=0;n<(setting==1?2:4);n++){
                    double angle=n*Math.PI/2;level.addParticle(DimensionParticles.GLIMMER.get(),pos.getX()+.5+Math.cos(angle)*.32,pos.getY()+.35,pos.getZ()+.5+Math.sin(angle)*.32,0,0,0);
                }
                if(level.getBlockState(pos.below()).is(BlockTags.SUPPORTS_VEGETATION))level.addParticle(DimensionParticles.SIGIL.get(),pos.getX()+.5,pos.getY()+.025,pos.getZ()+.5,0,0,0);
                if(night){chime(level,pos,frame);if(level.getRandom().nextInt(4)==0)wisp(level,pos,frame);}
            }
        }
        // Brush through foliage: a few warm-season spores rise, never a permanent trail.
        var foot=mc.player.position();
        if(lastFoot!=null&&mc.player.onGround()&&foot.distanceToSqr(lastFoot)>.35*.35&&foot.distanceToSqr(lastFoot)<9&&season!=3){
            var here=level.getBlockState(origin);
            if(here.is(CloudseaBlocks.FERN.get())||here.is(CloudseaBlocks.BRACKEN.get())||here.is(CloudseaBlocks.MEADOW_GRASS.get()))
                level.addParticle(DimensionParticles.SPORE.get(),foot.x,foot.y+.15,foot.z,0,0,0);
        }
        lastFoot=foot;
    }
    private static boolean ready(BlockPos pos,long frame,int cooldown){
        long key=pos.asLong();Long seen=RECENT.get(key);if(seen!=null&&frame-seen<cooldown)return false;
        RECENT.remove(key);RECENT.put(key,frame);while(RECENT.size()>96)RECENT.remove(RECENT.keySet().iterator().next());return true;
    }
    private static void wisp(ClientLevel level,BlockPos pos,long frame){
        if(frame<nextWisp||level.isRaining())return;
        nextWisp=frame+600;level.addParticle(DimensionParticles.WISP.get(),pos.getX()+.5,pos.getY()+.45,pos.getZ()+.5,0,0,0);
    }
    private static void chime(ClientLevel level,BlockPos pos,long frame){
        if(frame<nextSound)return;
        nextSound=frame+600;sounds++;level.playLocalSound(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,SoundEvents.AMETHYST_BLOCK_CHIME,SoundSource.AMBIENT,.09f,1.5f+level.getRandom().nextFloat()*.2f,false);
    }
    private JourneyMagicDetails(){}
}
