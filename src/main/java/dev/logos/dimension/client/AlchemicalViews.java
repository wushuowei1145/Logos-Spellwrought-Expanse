package dev.logos.dimension.client;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.world.AlchemicalLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
/** Inspect the vanilla data-driven instance, never the retired fixed dimension. */
public abstract class AlchemicalViews implements UIScenario {
    protected volatile BlockPos viewedGround;
    protected abstract String shot();
    protected AlchemicalLayout.Region region(){return AlchemicalLayout.Region.AETHER;}
    protected float yaw(){return 55;}protected float pitch(){return 18;}protected int settleTicks(){return 120;}
    protected Vec3 camera(ServerLevel level){var feet=DimensionTravel.findLanding(level,NativeWorlds.center(),region());if(feet==null)throw new IllegalStateException("No biome landing");viewedGround=BlockPos.containing(feet).below();return feet.add(0,10,0);}
    @Override public void configure(ScenarioOptions o){o.tags("logos_dimension","terrain").defaultTimeoutMs(120000).scenarioTimeoutMs(240000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("camera",ctx->{var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.renderDistance().set(10);})
            .server("enter native instance",ctx->{var p=ctx.player();if(!LogosDimension.isCrucible(p.level().dimension()))DimensionTravel.enterTarget(p,dev.logos.dimension.gametest.NativeTestWorlds.crucible(ctx.server()),p.position());var at=camera(p.level());DimensionTravel.teleport(p,p.level(),at,yaw(),pitch());p.getAbilities().flying=true;p.onUpdateAbilities();})
            .waitUntil("native instance received",ctx->Minecraft.getInstance().level!=null&&LogosDimension.isCrucible(Minecraft.getInstance().level.dimension())&&!Minecraft.getInstance().level.getBlockState(viewedGround).isAir())
            .runCommand("alchemy phase albedo").ticks(settleTicks()).checkServer("native terrain generated",ctx->!ctx.player().level().getBlockState(viewedGround).isAir()).screenshot(shot());
        extra(s);s.teardown("restore HUD",ctx->Minecraft.getInstance().options.hideGui=false);
    }
    protected void extra(ScenarioBuilder s){}
    @LDLRegisterClient(name="crucible_aether",group="logos_dimension",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Aether extends AlchemicalViews{protected String shot(){return "native_aether";}}
    @LDLRegisterClient(name="crucible_sulfur",group="logos_dimension",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Sulfur extends AlchemicalViews{protected String shot(){return "native_sulfur";}protected AlchemicalLayout.Region region(){return AlchemicalLayout.Region.SULFUR;}}
    @LDLRegisterClient(name="crucible_mercury",group="logos_dimension",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Mercury extends AlchemicalViews{protected String shot(){return "native_mercury";}protected AlchemicalLayout.Region region(){return AlchemicalLayout.Region.MERCURY;}}
    @LDLRegisterClient(name="crucible_salt",group="logos_dimension",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Salt extends AlchemicalViews{protected String shot(){return "native_salt";}protected AlchemicalLayout.Region region(){return AlchemicalLayout.Region.SALT;}}
    public static class Island extends AlchemicalViews{protected String shot(){return "native_island";}protected Vec3 camera(ServerLevel level){viewedGround=new BlockPos(8,320,8);level.getChunk(viewedGround);return new Vec3(8,334,65);}}
    public static class Retort extends Island{}
    public static class Sky extends AlchemicalViews{protected String shot(){return "native_array";}protected float pitch(){return -70;}}
    public static class Haunt extends AlchemicalViews{protected String shot(){return "native_island_fog";}protected Vec3 camera(ServerLevel level){IslandProgress.of(level).setDefeated(NativeWorlds.center(),false);viewedGround=new BlockPos(8,level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR,8,8)-1,8);return new Vec3(8,230,8);}}
}
