package dev.logos.dimension.client;

import dev.logos.dimension.DimensionParticles;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** At most four loaded-column probes per emission; bounded local native-particle populations. */
@EventBusSubscriber(modid=LogosDimension.ID,value=Dist.CLIENT)
public final class JourneyAtmosphere {
    private static long frame;
    private static ClientLevel previousLevel;
    public static long emitted(){return JourneyAmbientParticle.created();}
    static long frame(){return frame;}
    @SubscribeEvent static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();
        if(mc.level!=previousLevel){JourneyAmbientParticle.clear();previousLevel=mc.level;}
        if(mc.level==null||mc.player==null||!mc.level.dimension().equals(LogosDimension.CLOUDSEA)){JourneyAmbientParticle.clear();return;}
        if(mc.isPaused())return;
        frame++;int setting=mc.options.particles().get().ordinal(),capacity=AmbientMotion.capacity(setting);
        JourneyAmbientParticle.trim(capacity);
        if(capacity==0||frame%AmbientMotion.interval(setting)!=0||JourneyAmbientParticle.active()>=capacity)return;
        var level=mc.level;var random=level.getRandom();var origin=mc.player.blockPosition();
        for(int attempt=0;attempt<4;attempt++){
            int x=origin.getX()+random.nextInt(29)-14,z=origin.getZ()+random.nextInt(29)-14;
            if(!level.hasChunk(x>>4,z>>4))continue;
            var pos=anchor(level,x,z,origin.getY());if(pos==null)continue;
            var biome=biome(level,pos);int season=JourneySeasons.of(level);
            boolean warm=biome!=null&&!biome.barren()&&!biome.conifer()&&biome!=CloudseaBiome.AURORA_DUNES;
            boolean fireflies=AmbientMotion.fireflies(season,JourneySeasons.ticks(level),warm,level.isRaining());
            var here=level.getBlockState(pos);var below=level.getBlockState(pos.below());
            boolean plant=here.getBlock() instanceof JourneyPlantBlock||below.getBlock() instanceof JourneyPlantBlock||here.is(CloudseaBlocks.GRAPEVINE.get())||here.is(CloudseaBlocks.BRAMBLE.get())||below.is(CloudseaBlocks.BRAMBLE.get());
            double px=x+random.nextDouble(),pz=z+random.nextDouble();
            if(fireflies&&random.nextFloat()<.75f){
                for(int i=0;i<2;i++)level.addParticle(DimensionParticles.FIREFLY.get(),px+random.nextDouble()*.8-.4,pos.getY()+.45+random.nextDouble()*1.4,pz+random.nextDouble()*.8-.4,0,0,0);
            }else if(random.nextFloat()<(biome!=null&&biome.barren()?.25f:.85f)){
                level.addParticle(DimensionParticles.MOTE.get(),px,pos.getY()+.25+random.nextDouble()*1.8,pz,0,0,0);
            }
            if(plant&&random.nextInt(3)==0)level.addParticle(DimensionParticles.GLIMMER.get(),px,pos.getY()+.2+random.nextDouble()*.7,pz,0,0,0);
            break;
        }
    }
    private static CloudseaBiome biome(ClientLevel level,BlockPos pos){
        var key=level.getBiome(pos).unwrapKey();if(key.isEmpty())return null;
        var id=key.get().identifier();if(!id.getNamespace().equals(LogosDimension.ID))return null;
        for(var b:CloudseaBiome.values())if(b.id().equals(id.getPath()))return b;return null;
    }
    private static BlockPos anchor(ClientLevel level,int x,int z,int playerY){
        int surface=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
        if(Math.abs(surface-playerY)<=8){var p=new BlockPos(x,surface,z);if(supported(level,p))return p;}
        // A heightmap may stop at a woody branch; inspect only the player's small vertical neighbourhood.
        for(int y=playerY+3;y>=playerY-7;y--){var p=new BlockPos(x,y,z);if(supported(level,p))return p;}
        return null;
    }
    private static boolean supported(ClientLevel level,BlockPos pos){
        var state=level.getBlockState(pos);if(!state.getCollisionShape(level,pos).isEmpty()||!state.getFluidState().isEmpty())return false;
        var below=level.getBlockState(pos.below());
        return below.is(BlockTags.SUPPORTS_VEGETATION)||below.is(BlockTags.LEAVES)||below.is(Blocks.SNOW)||below.is(Blocks.SNOW_BLOCK)
                ||below.getBlock() instanceof JourneyPlantBlock||state.getBlock() instanceof JourneyPlantBlock
                ||below.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                ||pos.getY()<55&&level.getBrightness(LightLayer.SKY,pos)==0&&below.is(BlockTags.BASE_STONE_OVERWORLD);
    }
    private JourneyAtmosphere(){}
}
