package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.JourneySeasons;
import dev.logos.dimension.gametest.ElvenForestGameTests;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.Vec3;

/** 实际客户端检查树屋、旋转楼梯与同节拍林光，截图来自原版模板放置。 */
public abstract class ElvenHabitationViews implements UIScenario {
    private static net.minecraft.server.level.ServerLevel cachedLevel;
    private static ElvenForestGameTests.Tree cachedTree;
    private ElvenForestGameTests.Tree tree;
    private Vec3 look;
    protected int scene(){return 0;}
    @Override public void configure(ScenarioOptions o){o.tags("elven_habitation").defaultTimeoutMs(360000).scenarioTimeoutMs(600000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("设置树屋观察",ctx->{
                var mc=Minecraft.getInstance();
                // 无窗口模式默认压缩为一像素；截图场景显式恢复离屏渲染尺寸。
                var window=mc.getWindow();window.setWindowed(1600,900);window.setWidth(1600);window.setHeight(900);
                mc.getMainRenderTarget().resize(1600,900);mc.gameRenderer.resize(1600,900);mc.resizeGui();
                mc.options.hideGui=true;mc.options.fov().set(scene()==2?92:65);mc.options.renderDistance().set(12);mc.options.broadcastOptions();
            })
            .server("原版模板拼接",ctx->{
                var l=ctx.server().getLevel(LogosDimension.CLOUDSEA);
                if(cachedLevel!=l||cachedTree==null){cachedTree=ElvenForestGameTests.placeStudy(l);cachedLevel=l;}
                tree=cachedTree;
                BlockPos at=scene()==0?tree.pos(73,36,84):scene()==1?tree.pos(24,34,-42):scene()==2?tree.pos(0,26,0):tree.pos(29,9,44);
                look=(scene()==0?tree.pos(52,30,60):scene()==1?tree.pos(0,28,-65):scene()==2?tree.pos(-10,28,3):tree.pos(52,14,52)).getCenter();
                var eye=at.getBottomCenter();var direction=look.subtract(eye.add(0,ctx.player().getEyeHeight(),0));
                DimensionTravel.teleport(ctx.player(),l,eye,(float)Math.toDegrees(Math.atan2(-direction.x,direction.z)),(float)-Math.toDegrees(Math.atan2(direction.y,Math.hypot(direction.x,direction.z))));
                ctx.player().getAbilities().flying=true;ctx.player().onUpdateAbilities();
                l.clockManager().setTotalTicks(l.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK),JourneySeasons.SEASON_TICKS+(scene()<2?6000:18000));
                l.getWeatherData().setRaining(false);l.getWeatherData().setThundering(false);
            }).waitUntil("树心方块到达",ctx->tree!=null&&Minecraft.getInstance().level!=null&&Minecraft.getInstance().level.dimension().equals(LogosDimension.CLOUDSEA)&&Minecraft.getInstance().level.getBlockState(tree.pos(0,1,0)).is(dev.logos.dimension.elven.ElvenBlocks.INLAY.get())).ticks(200)
            .step("对准树屋",ctx->{var p=Minecraft.getInstance().player;var d=look.subtract(p.getEyePosition());p.setYRot((float)Math.toDegrees(Math.atan2(-d.x,d.z)));p.setXRot((float)-Math.toDegrees(Math.atan2(d.y,Math.hypot(d.x,d.z))));}).ticks(30)
            .check("镜头未进入方块",ctx->{var mc=Minecraft.getInstance();var p=BlockPos.containing(mc.player.getEyePosition());return mc.level.getBlockState(p).getCollisionShape(mc.level,p).isEmpty();})
            .check("镜头对准目标",ctx->{var p=Minecraft.getInstance().player;return p.getLookAngle().dot(look.subtract(p.getEyePosition()).normalize())>.98;})
            .check("森林光点数量有界",ctx->ElvenBreathingLights.active()<=160)
            .check("离屏截图有实际分辨率",ctx->{var target=Minecraft.getInstance().getMainRenderTarget();return target.width==1600&&target.height==900;})
            .screenshot(scene()==0?"broadleaf_tree_house":scene()==1?"fir_tree_house":scene()==2?"spiral_elven_floors":"breathing_forest");
        if(scene()==3)s.check("附近森林呼吸光已生成",ctx->ElvenBreathingLights.active()>5).repeat(4,b->b.ticks(30).screenshot("synchronized_breath"));
        s.teardown("恢复界面",ctx->Minecraft.getInstance().options.hideGui=false);
    }
    @LDLRegisterClient(name="elven_broadleaf_home",group="elven_habitation",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Broadleaf extends ElvenHabitationViews{}
    @LDLRegisterClient(name="elven_fir_home",group="elven_habitation",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Fir extends ElvenHabitationViews{protected int scene(){return 1;}}
    @LDLRegisterClient(name="elven_spiral_hall",group="elven_habitation",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Stair extends ElvenHabitationViews{protected int scene(){return 2;}}
    @LDLRegisterClient(name="elven_breathing_grove",group="elven_habitation",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Forest extends ElvenHabitationViews{protected int scene(){return 3;}}
}
