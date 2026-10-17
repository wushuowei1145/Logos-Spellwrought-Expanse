package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.instance.BoundCauldronBlockEntity;
import dev.logos.dimension.instance.CauldronWorlds;

import dev.logos.dimension.world.AlchemicalLayout;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Real gravity crosses each basin; the client must render a newly allocated UUID dimension. */
@LDLRegisterClient(name="cauldron_uuid_round_trip", group="cauldron_instances", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public final class CauldronInstanceView implements UIScenario {
    private static final BlockPos FIRST = new BlockPos(-8, 101, 0);
    private static final BlockPos SECOND = new BlockPos(8, 101, 0);
    private volatile UUID firstId, secondId;
    private volatile BlockPos returnPortal;
    private long firstSeed;

    @Override public void configure(ScenarioOptions options) {
        options.tags("cauldron_instances", "portal").scenarioTimeoutMs(240_000).defaultTimeoutMs(80_000);
    }

    private static BoundCauldronBlockEntity platform(ServerLevel level, BlockPos pot) {
        for (int x=-3; x<=3; x++) for (int z=-3; z<=3; z++) {
            level.setBlockAndUpdate(pot.offset(x,-1,z), DimensionBlocks.STRATA_STONE.get().defaultBlockState());
            for (int y=0; y<6; y++) level.removeBlock(pot.offset(x,y,z), false);
        }
        level.setBlockAndUpdate(pot, DimensionBlocks.ALCHEMICAL_CAULDRON.get().defaultBlockState());
        return (BoundCauldronBlockEntity) level.getBlockEntity(pot);
    }

    private static void drop(ServerPlayer player, ServerLevel level, BlockPos pot) {
        player.setGameMode(GameType.SURVIVAL);
        player.setPortalCooldown(0);
        DimensionTravel.teleport(player, level, new Vec3(pot.getX()+.5,pot.getY()+2.5,pot.getZ()+.5), 180, 12);
    }

    private static boolean clientInstance() {
        var level = Minecraft.getInstance().level;
        return level != null && LogosDimension.isCrucible(level.dimension()) && !level.dimension().equals(LogosDimension.CRUCIBLE);
    }

    @Override public void define(ScenarioBuilder scenario) {
        scenario.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("hide HUD for actual arrival screenshots",ctx->Minecraft.getInstance().options.hideGui=true)
            .server("place two unbound cauldrons",ctx->{
                var overworld=ctx.server().overworld();
                platform(overworld,FIRST);platform(overworld,SECOND);
                DimensionTravel.teleport(ctx.player(),overworld,new Vec3(0,104,5),180,25);
                ctx.player().getAbilities().flying=true;ctx.player().onUpdateAbilities();
            })
            .checkServer("unused cauldrons have no UUID world",ctx->
                ((BoundCauldronBlockEntity)ctx.server().overworld().getBlockEntity(FIRST)).worldId()==null
                &&((BoundCauldronBlockEntity)ctx.server().overworld().getBlockEntity(SECOND)).worldId()==null)
            .server("jump through first cauldron surface",ctx->drop(ctx.player(),ctx.server().overworld(),FIRST))
            .waitUntil("client receives the first dynamic world",ctx->clientInstance())
            .server("remember the first bound world",ctx->{
                firstId=((BoundCauldronBlockEntity)ctx.server().overworld().getBlockEntity(FIRST)).worldId();
                firstSeed=ctx.player().level().getSeed();
                returnPortal=dev.logos.dimension.ReturnGate.prepare(ctx.player().level()).portal();
            })
            .checkServer("first arrival is outer Aether inside the finite border",ctx->{
                var p=ctx.player();var level=p.level();
                return firstId!=null&&level.dimension().equals(CauldronWorlds.key(firstId))&&p.isOnPortalCooldown()
                    &&Math.abs(level.getWorldBorder().getSize()-20_000)<.01&&level.getWorldBorder().isWithinBounds(p.blockPosition())
                    &&Math.hypot(p.getX(),p.getZ())>8500
                    &&level.getBiome(p.blockPosition()).is(LogosDimension.id("aether"));
            })
            .check("client renders the UUID named world",ctx->firstId!=null&&Minecraft.getInstance().level.dimension().equals(CauldronWorlds.key(firstId)))
            .ticks(100).screenshot("first_cauldron_outer_aether")
            .waitUntil("client receives the automatically generated exit",ctx->returnPortal!=null&&Minecraft.getInstance().level.getBlockState(returnPortal).is(DimensionBlocks.VORTEX.get()))
            .step("right click the generated return vortex",ctx->{
                var mc=Minecraft.getInstance();
                mc.gameMode.useItemOn(mc.player,net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(returnPortal.getCenter(),net.minecraft.core.Direction.UP,returnPortal,false));
            })
            .waitUntil("return vortex reaches the overworld",ctx->Minecraft.getInstance().level!=null&&Minecraft.getInstance().level.dimension().equals(Level.OVERWORLD))
            .checkServer("physical return saved a point next to the original cauldron",ctx->
                ctx.player().level()==ctx.server().overworld()&&ctx.player().position().distanceToSqr(FIRST.getCenter())<25)
            .server("jump into the original cauldron again",ctx->drop(ctx.player(),ctx.server().overworld(),FIRST))
            .waitUntil("client reenters the same UUID",ctx->firstId!=null&&Minecraft.getInstance().level!=null&&Minecraft.getInstance().level.dimension().equals(CauldronWorlds.key(firstId)))
            .checkServer("reentry retains the bound UUID and seed",ctx->
                ctx.player().level().dimension().equals(CauldronWorlds.key(firstId))&&ctx.player().level().getSeed()==firstSeed)
            .server("return and jump through the second cauldron",ctx->{
                if(!DimensionTravel.leave(ctx.player()))throw new IllegalStateException("Cannot return before second entrance");
                drop(ctx.player(),ctx.server().overworld(),SECOND);
            })
            .waitUntil("client receives a different dynamic world",ctx->clientInstance()&&!Minecraft.getInstance().level.dimension().equals(CauldronWorlds.key(firstId)))
            .server("remember second world identity",ctx->secondId=((BoundCauldronBlockEntity)ctx.server().overworld().getBlockEntity(SECOND)).worldId())
            .checkServer("second cauldron has an independent UUID and seed",ctx->secondId!=null&&!secondId.equals(firstId)
                &&ctx.player().level().dimension().equals(CauldronWorlds.key(secondId))&&ctx.player().level().getSeed()!=firstSeed)
            .ticks(100).screenshot("second_cauldron_outer_aether")
            .server("clean up only the two test UUID worlds",ctx->{
                DimensionTravel.leave(ctx.player());
                CauldronWorlds.requestDestroy(ctx.server(),firstId);CauldronWorlds.requestDestroy(ctx.server(),secondId);
                CauldronWorlds.processPending(ctx.server());
                ctx.player().setGameMode(GameType.CREATIVE);
            })
            .waitUntil("client returns before test worlds close",ctx->Minecraft.getInstance().level!=null&&Minecraft.getInstance().level.dimension().equals(Level.OVERWORLD))
            .checkServer("test worlds are gone and continent remains loaded",ctx->
                ctx.server().getLevel(CauldronWorlds.key(firstId))==null&&ctx.server().getLevel(CauldronWorlds.key(secondId))==null
                    &&ctx.server().getLevel(LogosDimension.CLOUDSEA)!=null)
            .teardown("restore HUD",ctx->Minecraft.getInstance().options.hideGui=false);
    }
}
