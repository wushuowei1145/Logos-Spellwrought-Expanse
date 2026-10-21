package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.JourneySeasons;
import dev.logos.dimension.elven.ElvenBlocks;
import dev.logos.dimension.gametest.ElvenForestGameTests;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.Vec3;

/** 开发时在真实地形上调用原版拼接器，分别检查全树和夜间内厅。 */
public abstract class ElvenForestViews implements UIScenario {
    private ElvenForestGameTests.Tree tree;
    private Vec3 look;
    protected boolean interior(){return false;}
    protected boolean detail(){return false;}
    @Override public void configure(ScenarioOptions o){o.tags("elven_forest").defaultTimeoutMs(360000).scenarioTimeoutMs(600000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("设置世界树观察镜头",ctx->{var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.fov().set(interior()?70:86);mc.options.renderDistance().set(interior()?16:20);mc.options.broadcastOptions();})
            .server("原版拼接世界树",ctx->{
                var l=ctx.server().getLevel(LogosDimension.CLOUDSEA);tree=ElvenForestGameTests.placeStudy(l);
                var eye=tree.pos(interior()?0:150,detail()?3:interior()?6:120,detail()?0:interior()?-21:160).getBottomCenter();
                look=tree.pos(0,detail()?3:interior()?7:108,detail()?9:interior()?5:0).getCenter();
                var direction=look.subtract(eye.add(0,ctx.player().getEyeHeight(),0));
                float yaw=(float)Math.toDegrees(Math.atan2(-direction.x,direction.z));
                float pitch=(float)-Math.toDegrees(Math.atan2(direction.y,Math.hypot(direction.x,direction.z)));
                DimensionTravel.teleport(ctx.player(),l,eye,yaw,pitch);ctx.player().getAbilities().flying=true;ctx.player().onUpdateAbilities();
                l.clockManager().setTotalTicks(l.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK),JourneySeasons.SEASON_TICKS+(interior()?18000:6000));l.getWeatherData().setRaining(false);l.getWeatherData().setThundering(false);
                System.out.println("ELVEN_CLIENT interior="+interior()+" center="+tree.center()+" pieces="+tree.start().getPieces().size()+" roots="+ElvenForestGameTests.roots(tree));
            }).waitUntil("树心区块到达客户端",ctx->{var mc=Minecraft.getInstance();return tree!=null&&mc.level!=null&&mc.level.dimension().equals(LogosDimension.CLOUDSEA)&&mc.level.getBlockState(tree.pos(0,1,0)).is(ElvenBlocks.INLAY.get());}).ticks(240)
            .step("重新瞄准实际树心",ctx->{var p=Minecraft.getInstance().player;var d=look.subtract(p.getEyePosition());p.setYRot((float)Math.toDegrees(Math.atan2(-d.x,d.z)));p.setXRot((float)-Math.toDegrees(Math.atan2(d.y,Math.hypot(d.x,d.z))));}).ticks(30)
            .checkServer("使用原版噪声世界",ctx->NativeWorlds.vanilla(ctx.player().level()))
            .check("家具和光源资源到达",ctx->Minecraft.getInstance().level.getBlockState(tree.pos(-6,2,9)).is(ElvenBlocks.BOOKCASE.get()))
            .checkServer("根部子件齐全",ctx->ElvenForestGameTests.roots(tree)>=1500)
            .check("中央有实际方块光照",ctx->Minecraft.getInstance().level.getBrightness(LightLayer.BLOCK,tree.pos(0,3,0))>=10)
            .check("相机不在实体方块内",ctx->{var mc=Minecraft.getInstance();var p=BlockPos.containing(mc.player.getEyePosition());return mc.level.getBlockState(p).getCollisionShape(mc.level,p).isEmpty();})
            .check("相机对准世界树",ctx->{var p=Minecraft.getInstance().player;return p.getLookAngle().dot(look.subtract(p.getEyePosition()).normalize())>.98;})
            .screenshot(detail()?"elven_reading_nook":interior()?"warm_elven_hall":"living_world_tree")
            .teardown("恢复界面",ctx->Minecraft.getInstance().options.hideGui=false);
    }
    @LDLRegisterClient(name="elven_world_tree",group="elven_forest",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Outside extends ElvenForestViews{}
    @LDLRegisterClient(name="elven_hall",group="elven_forest",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Hall extends ElvenForestViews{protected boolean interior(){return true;}}
    @LDLRegisterClient(name="elven_reading_nook",group="elven_forest",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Nook extends ElvenForestViews{protected boolean interior(){return true;}protected boolean detail(){return true;}}
}
