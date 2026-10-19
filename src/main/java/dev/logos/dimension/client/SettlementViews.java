package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.*;
import dev.logos.dimension.gametest.SettlementGameTests;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.phys.Vec3;

/** Native assembly on generated terrain, invoked explicitly for development screenshots only. */
public abstract class SettlementViews implements UIScenario {
    private BlockPos received;private long homes,fields;private int wheat;protected boolean town(){return false;}protected boolean close(){return false;}
    @Override public void configure(ScenarioOptions o){o.tags("blueprint_settlements").defaultTimeoutMs(300000).scenarioTimeoutMs(600000);}
    private static BlockPos flatGlade(ServerLevel l,boolean town){
        var origin=NativeWorlds.locateBiome(l,(town?CloudseaBiome.SUNLIT_GLADE:CloudseaBiome.SILK_FOLDS).id());var gen=l.getChunkSource().getGenerator();var random=l.getChunkSource().randomState();
        int best=Integer.MAX_VALUE;BlockPos result=origin;
        var allowed=java.util.Set.of("cloudsea_sunlit_glade","cloudsea_starflower_glade","cloudsea_silk_folds","cloudsea_rose_dunes");
        for(int x=-1024;x<=1024;x+=128)for(int z=-1024;z<=1024;z+=128){
            int min=999,max=-999;var p=origin.offset(x,0,z);
            var biome=gen.getBiomeSource().getNoiseBiome(p.getX()>>2,24,p.getZ()>>2,random.sampler());
            if(biome.unwrapKey().isEmpty()||!allowed.contains(biome.unwrapKey().get().identifier().getPath()))continue;
            int centerY=gen.getBaseHeight(p.getX(),p.getZ(),Heightmap.Types.WORLD_SURFACE_WG,l,random);if(centerY<67||centerY>115)continue;
            for(int dx:new int[]{-100,0,100})for(int dz:new int[]{-100,0,100}){int y=gen.getBaseHeight(p.getX()+dx,p.getZ()+dz,Heightmap.Types.WORLD_SURFACE_WG,l,random);min=Math.min(min,y);max=Math.max(max,y);}
            if(min>=63&&max<130&&max-min<best){best=max-min;result=p;}
        }
        System.out.println("SETTLEMENT_FLAT_STUDY town="+town+" range="+best+" origin="+result);
        return result;
    }
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
        .step("settlement camera",ctx->{var m=Minecraft.getInstance();m.options.hideGui=true;m.options.fov().set(close()?58:66);m.options.renderDistance().set(14);m.options.broadcastOptions();})
        .server("native blueprint assembly on actual terrain",ctx->{
            var l=ctx.server().getLevel(LogosDimension.CLOUDSEA);var at=flatGlade(l,town());
            var start=SettlementGameTests.assemble(l,town(),at,2);SettlementGameTests.place(l,start);
            homes=SettlementGameTests.homes(start);fields=SettlementGameTests.wheatSeeds(start);var root=start.getPieces().getFirst().getBoundingBox();var target=root.getCenter().getBottomCenter();
            // Blueprint cottages already contain wheat; exclude it so this preview actually
            // verifies the new terrain-following planting rather than the old garden crops.
            var originalCrops=new java.util.HashSet<BlockPos>();
            for(var piece:start.getPieces())if(piece instanceof PoolElementStructurePiece pool&&pool.getElement() instanceof net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement single){
                var template=l.getStructureManager().getOrCreate(single.getTemplateLocation());
                var settings=new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings().setRotation(pool.getRotation());
                for(var block:template.filterBlocks(pool.getPosition(),settings,net.minecraft.world.level.block.Blocks.WHEAT))originalCrops.add(block.pos());
            }
            var bounds=start.getBoundingBox();var crops=new java.util.ArrayList<BlockPos>();
            for(int x=bounds.minX()-8;x<=bounds.maxX()+8;x++)for(int z=bounds.minZ()-8;z<=bounds.maxZ()+8;z++){
                int top=l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
                for(int y=top-1;y<=top+1;y++){var pos=new BlockPos(x,y,z);if(!originalCrops.contains(pos)&&l.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.WHEAT))crops.add(pos);}
            }
            wheat=crops.size();Vec3 eye=target.add(112,85,118);float yaw=136,pitch=27;
            if(close()){
                crops.sort(java.util.Comparator.comparingDouble(p->p.distSqr(root.getCenter())));
                boolean framed=false;
                outer:for(var crop:crops){
                    var look=crop.getCenter();
                    for(int[] offset:new int[][]{{8,5,8},{-8,5,8},{8,5,-8},{-8,5,-8},{0,6,10},{10,6,0}}){
                        var point=look.add(offset[0],offset[1],offset[2]);var cameraEye=point.add(0,ctx.player().getEyeHeight(),0);
                        if(!l.noCollision(ctx.player(),new net.minecraft.world.phys.AABB(point.x-.3,point.y,point.z-.3,point.x+.3,point.y+1.9,point.z+.3)))continue;
                        var ray=l.clip(new net.minecraft.world.level.ClipContext(cameraEye,look,net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,ctx.player()));
                        if(ray.getType()!=net.minecraft.world.phys.HitResult.Type.MISS)continue;
                        target=look;eye=point;yaw=(float)Math.toDegrees(Math.atan2(point.x-look.x,look.z-point.z));pitch=(float)Math.toDegrees(Math.atan2(cameraEye.y-look.y,Math.hypot(point.x-look.x,point.z-look.z)));framed=true;break outer;
                    }
                }
                if(!framed)throw new IllegalStateException("No unobstructed view of terrain-following wheat");
            }
            received=BlockPos.containing(target);
            DimensionTravel.teleport(ctx.player(),l,eye,yaw,pitch);ctx.player().getAbilities().flying=true;ctx.player().onUpdateAbilities();
            l.clockManager().setTotalTicks(l.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK),JourneySeasons.SEASON_TICKS+6000);l.getWeatherData().setRaining(false);l.getWeatherData().setThundering(false);
            System.out.println("SETTLEMENT_CLIENT town="+town()+" houses="+homes+" fields="+fields+" vanilla_wheat="+wheat+" target="+target);
        }).waitUntil("village terrain visible",ctx->{var l=Minecraft.getInstance().level;return l!=null&&l.dimension().equals(LogosDimension.CLOUDSEA)&&l.hasChunk(received.getX()>>4,received.getZ()>>4);}).ticks(240)
        .checkServer("native noise world",ctx->NativeWorlds.vanilla(ctx.player().level()))
        .check("large housing layout",ctx->homes>=(town()?20:14))
        .check("vanilla wheat scattered beside homes",ctx->fields>=16&&wheat>=20)
        .check("camera outside blocks",ctx->{var m=Minecraft.getInstance();var pos=BlockPos.containing(m.player.getEyePosition());return m.level.getBlockState(pos).getCollisionShape(m.level,pos).isEmpty();})
        .screenshot(close()?"terrain_following_vanilla_wheat":town()?"blueprint_market_town":"blueprint_glade_village")
        .teardown("restore HUD",ctx->Minecraft.getInstance().options.hideGui=false);
    }
    @LDLRegisterClient(name="blueprint_village",group="blueprint_settlements",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Village extends SettlementViews{}
    @LDLRegisterClient(name="blueprint_town",group="blueprint_settlements",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Town extends SettlementViews{protected boolean town(){return true;}}
    @LDLRegisterClient(name="blueprint_wheat",group="blueprint_settlements",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Wheat extends Town{protected boolean close(){return true;}}
}
