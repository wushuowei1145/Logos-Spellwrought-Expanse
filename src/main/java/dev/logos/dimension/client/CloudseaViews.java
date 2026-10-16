package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** Camera-only development scenarios: no new entrance item, portal, command or survival route. */
public abstract class CloudseaViews implements UIScenario {
    protected BlockPos received;
    protected CloudseaBiome kind(){return CloudseaBiome.SILK_FOLDS;}
    protected abstract String shot();
    protected float pitch(){return 14;}
    protected float yaw(){return 135;}
    protected Vec3 viewpoint(CloudseaChunkGenerator gen){
        CloudseaTerrain.Region r=null;for(int z=-8;z<=8&&r==null;z++)for(int x=-8;x<=8&&r==null;x++){var q=gen.terrain().region(x,z);if(q.biome()==kind())r=q;}
        if(r==null)throw new IllegalStateException("Biome viewpoint not found");
        int x=Math.floorDiv(r.x(),512)*512+256,z=Math.floorDiv(r.z(),512)*512+256;
        received=new BlockPos(x+80,gen.terrain().column(x+80,z).ground(),z);
        if(received.getY()<110)received=new BlockPos(x,gen.terrain().column(x,z).ground(),z);
        return new Vec3(x+235,gen.terrain().column(x,z).ground()+140,z+145);
    }
    @Override public void configure(ScenarioOptions o){o.tags("cloudsea","terrain").defaultTimeoutMs(120_000).scenarioTimeoutMs(180_000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
                .step("camera settings",ctx->{var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.renderDistance().set(16);mc.options.broadcastOptions();})
                .server("inspect the actual Cloudsea",ctx->{var p=ctx.player();var level=ctx.server().getLevel(LogosDimension.CLOUDSEA);if(level==null)throw new IllegalStateException("Cloudsea missing");var gen=(CloudseaChunkGenerator)level.getChunkSource().getGenerator();var at=viewpoint(gen);level.getChunk(received);DimensionTravel.teleport(p,level,at,yaw(),pitch());p.getAbilities().flying=true;p.onUpdateAbilities();level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK).getOrThrow(CloudseaClient.CLOCK),200);})
                .checkServer("viewpoint generated",ctx->!ctx.player().level().getBlockState(received).isAir())
                .waitUntil("client received terrain",ctx->Minecraft.getInstance().level!=null&&Minecraft.getInstance().level.dimension().equals(LogosDimension.CLOUDSEA)&&Minecraft.getInstance().level.hasChunk(Minecraft.getInstance().player.blockPosition().getX()>>4,Minecraft.getInstance().player.blockPosition().getZ()>>4))
                .server("reset sky event after loading",ctx->{var level=ctx.player().level();level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK).getOrThrow(CloudseaClient.CLOCK),150);})
                .ticks(180).check("correct new dimension",ctx->Minecraft.getInstance().level.dimension().equals(LogosDimension.CLOUDSEA))
                .screenshot(shot()).teardown("restore HUD",ctx->Minecraft.getInstance().options.hideGui=false);
    }
    @LDLRegisterClient(name="cloudsea_folds",group="cloudsea",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Folds extends CloudseaViews {protected String shot(){return "folded_terrain";}}
    @LDLRegisterClient(name="cloudsea_ribbons",group="cloudsea",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Ribbons extends CloudseaViews {protected String shot(){return "floating_ribbons";}protected CloudseaBiome kind(){return CloudseaBiome.STAR_RIBBONS;}}
    @LDLRegisterClient(name="cloudsea_sky",group="cloudsea",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Sky extends CloudseaViews {
        protected String shot(){return "sky_cleft_whale";}protected float pitch(){return -63;}protected float yaw(){return 35;}
        protected Vec3 viewpoint(CloudseaChunkGenerator gen){received=new BlockPos(0,gen.terrain().column(0,0).ground(),0);return new Vec3(.5,received.getY()+18,.5);}
    }
    @LDLRegisterClient(name="cloudsea_village",group="cloudsea",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Village extends CloudseaViews {
        protected String shot(){return "medieval_settlement";}
        protected Vec3 viewpoint(CloudseaChunkGenerator gen){CloudseaSettlements.Village v=null;for(int z=-3;z<=3&&v==null;z++)for(int x=-3;x<=3&&v==null;x++)v=gen.settlements().village(gen.terrain().region(x,z));if(v==null)throw new IllegalStateException("No village");var h=v.houses().getFirst();super.received=new BlockPos(h.x(),h.y(),h.z());return new Vec3(v.x()+80,h.y()+40,v.z()+80);}
    }
    @LDLRegisterClient(name="cloudsea_caves",group="cloudsea",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Caves extends CloudseaViews {
        protected String shot(){return "inverted_root_caves";}protected float pitch(){return 0;}
        protected Vec3 viewpoint(CloudseaChunkGenerator gen){return cave(gen,-20);}
    }
    @LDLRegisterClient(name="cloudsea_core",group="cloudsea",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Core extends Caves {protected String shot(){return "demon_depths";}protected Vec3 viewpoint(CloudseaChunkGenerator gen){return cave(gen,-90);}}
    protected Vec3 cave(CloudseaChunkGenerator gen,int y){for(int z=16;z<96;z++)for(int x=16;x<96;x++){var c=gen.terrain().column(x,z);if(gen.terrain().cave(x,y,z,c)&&gen.terrain().cave(x,y+2,z,c)){received=new BlockPos(x,CloudseaTerrain.MIN_Y+1,z);return new Vec3(x+.5,y,z+.5);}}throw new IllegalStateException("No cave viewpoint");}
}
