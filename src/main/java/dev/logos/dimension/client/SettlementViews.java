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
    private BlockPos received;private long homes,fields;private int rice;protected boolean town(){return false;}protected boolean close(){return false;}
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
            homes=SettlementGameTests.homes(start);fields=SettlementGameTests.count(start,"rice_paddy");var root=start.getPieces().getFirst().getBoundingBox();var target=root.getCenter().getBottomCenter();
            var field=start.getPieces().stream().filter(p->p instanceof PoolElementStructurePiece pool&&pool.getElement().toString().contains("rice_paddy")).findFirst().orElseThrow();
            var bounds=field.getBoundingBox();rice=0;
            for(int x=bounds.minX();x<=bounds.maxX();x++)for(int z=bounds.minZ();z<=bounds.maxZ();z++)for(int y=bounds.minY();y<=bounds.maxY();y++)if(l.getBlockState(new BlockPos(x,y,z)).is(CloudseaBlocks.RICE.get()))rice++;
            if(close())target=new Vec3((bounds.minX()+bounds.maxX()+1)/2.0,bounds.minY()+2,(bounds.minZ()+bounds.maxZ()+1)/2.0);
            var eye=target.add(close()?17:112,close()?14:85,close()?18:118);received=BlockPos.containing(target);
            DimensionTravel.teleport(ctx.player(),l,eye,136,close()?25:27);ctx.player().getAbilities().flying=true;ctx.player().onUpdateAbilities();
            l.clockManager().setTotalTicks(l.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK),JourneySeasons.SEASON_TICKS+6000);l.getWeatherData().setRaining(false);l.getWeatherData().setThundering(false);
            System.out.println("SETTLEMENT_CLIENT town="+town()+" houses="+homes+" fields="+fields+" first_field_rice="+rice+" target="+target);
        }).waitUntil("village terrain visible",ctx->{var l=Minecraft.getInstance().level;return l!=null&&l.dimension().equals(LogosDimension.CLOUDSEA)&&l.hasChunk(received.getX()>>4,received.getZ()>>4);}).ticks(240)
        .checkServer("native noise world",ctx->NativeWorlds.vanilla(ctx.player().level()))
        .check("large housing layout",ctx->homes>=(town()?20:14))
        .check("large rice fields contain real rice",ctx->fields>=2&&rice>1500)
        .check("camera outside blocks",ctx->{var m=Minecraft.getInstance();var pos=BlockPos.containing(m.player.getEyePosition());return m.level.getBlockState(pos).getCollisionShape(m.level,pos).isEmpty();})
        .screenshot(close()?"broad_rice_paddy":town()?"blueprint_market_town":"blueprint_glade_village")
        .teardown("restore HUD",ctx->Minecraft.getInstance().options.hideGui=false);
    }
    @LDLRegisterClient(name="blueprint_village",group="blueprint_settlements",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Village extends SettlementViews{}
    @LDLRegisterClient(name="blueprint_town",group="blueprint_settlements",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Town extends SettlementViews{protected boolean town(){return true;}}
    @LDLRegisterClient(name="blueprint_paddy",group="blueprint_settlements",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Paddy extends Town{protected boolean close(){return true;}}
}
