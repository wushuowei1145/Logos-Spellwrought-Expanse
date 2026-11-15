package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.elven.blight.*;
import dev.logos.dimension.gametest.ElvenBlightGameTests;
import dev.logos.dimension.gametest.ElvenForestGameTests;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** 实际客户端中检查原版拼接树心、巨大核心及女王模型，不运行在正式客户端。 */
@LDLRegisterClient(name="elven_blight_queen", group="elven_blight", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class ElvenBlightViews implements UIScenario {
    private ElvenForestGameTests.Tree tree;
    private java.util.UUID queen;
    @Override public void configure(ScenarioOptions o) { o.tags("elven_blight").defaultTimeoutMs(360000).scenarioTimeoutMs(600000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty normal")
            .step("设置树心镜头", ctx -> { var mc = Minecraft.getInstance(); mc.options.hideGui = true; mc.options.fov().set(86); mc.options.renderDistance().set(6); mc.options.broadcastOptions(); })
            .server("原版拼接腐败世界树", ctx -> {
                var world = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                tree = ElvenBlightGameTests.placeStudy(world);
                Vec3 eye = tree.pos(0, 10, -12).getBottomCenter();
                Vec3 d = tree.pos(0, 10, 0).getCenter().subtract(eye.add(0, ctx.player().getEyeHeight(), 0));
                DimensionTravel.teleport(ctx.player(), world, eye, (float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
                ctx.player().getAbilities().flying = true;
                ctx.player().onUpdateAbilities();
                world.getWeatherData().setRaining(false);
            }).waitUntil("核心同步到客户端", ctx -> {
                var mc = Minecraft.getInstance();
                return tree != null && mc.level != null && mc.level.getBlockEntity(tree.pos(0, 9, 0)) instanceof QueenHeart;
            }).ticks(100)
            .check("镜头位于树心净空", ctx -> {
                var mc = Minecraft.getInstance();
                var at = net.minecraft.core.BlockPos.containing(mc.player.getEyePosition());
                return mc.level.getBlockState(at).getCollisionShape(mc.level, at).isEmpty();
            })
            .checkServer("守护者坐在核心下", ctx -> {
                var core = (QueenHeart) ctx.player().level().getBlockEntity(tree.pos(0, 9, 0));
                queen = core.queen();
                return queen != null && ctx.player().level().getEntity(queen) instanceof CorruptedElfQueen q && q.action().equals("queen_guard") && q.getY() < tree.pos(0, 9, 0).getY() - 5;
            })
            .checkServer("黑色内厅已经拆空", ctx -> {
                var level = ctx.player().level();
                for (int y : new int[]{3, 12, 24, 40, 65, 80}) for (int x : new int[]{-8, 0, 8}) for (int z : new int[]{-8, 0, 8})
                    if (!level.getBlockState(tree.pos(x,y,z)).isAir()) return false;
                return level.getBlockState(tree.pos(0,1,0)).is(ElvenBlight.LUMEN.get());
            })
            .check("血管接头均扎进树壁",ctx->{
                var level=Minecraft.getInstance().level;
                var pos=tree.pos(0,9,0);
                for(var point:dev.logos.dimension.client.corruption.QueenHeartRenderer.attachmentPoints(pos,level.getBlockState(pos).getValue(QueenHeartBlock.FACING)))
                    if(level.getBlockState(point).getCollisionShape(level,point).isEmpty()) return false;
                return true;
            })
            .screenshot("suspended_corruption_heart")
            .server("观察战场地板纹路",ctx->{
                var eye=tree.pos(0,15,-5).getBottomCenter();
                var d=tree.pos(0,1,0).getBottomCenter().subtract(eye.add(0,ctx.player().getEyeHeight(),0));
                DimensionTravel.teleport(ctx.player(),ctx.player().level(),eye,
                        (float)Math.toDegrees(Math.atan2(-d.x,d.z)),(float)-Math.toDegrees(Math.atan2(d.y,Math.hypot(d.x,d.z))));
            }).ticks(8).screenshot("queen_arena_floor")
            .step("观察女王坐姿", ctx -> Minecraft.getInstance().options.fov().set(48))
            .server("靠近守护者", ctx -> {
                var eye = tree.pos(0,3,-7).getBottomCenter();
                var d = tree.pos(0,2,0).getBottomCenter().add(0,1.9,0).subtract(eye.add(0,ctx.player().getEyeHeight(),0));
                DimensionTravel.teleport(ctx.player(),ctx.player().level(),eye,(float)Math.toDegrees(Math.atan2(-d.x,d.z)),(float)-Math.toDegrees(Math.atan2(d.y,Math.hypot(d.x,d.z))));
            })
            .ticks(25)
            .check("近景镜头对准女王",ctx->{
                var p=Minecraft.getInstance().player;
                return p.getLookAngle().dot(tree.pos(0,2,0).getBottomCenter().add(0,1.9,0).subtract(p.getEyePosition()).normalize())>.98;
            }).screenshot("queen_seated_regalia")
            .step("准备入场录制",ctx->QueenEntranceCapture.start())
            .server("唤醒女王", ctx -> {
                var heart = (QueenHeart) ctx.player().level().getBlockEntity(tree.pos(0, 9, 0));
                ctx.player().setGameMode(GameType.SURVIVAL);
                queen = heart.awaken(ctx.player()).getUUID();
            }).waitUntil("入场提示与前景到达",ctx->QueenEntranceClient.active())
            .ticks(18)
            .check("镜头前景已绘制",ctx->QueenEntranceClient.frames()>0&&dev.logos.dimension.client.camera.CameraDirector.active())
            .checkServer("过场期间保护观看者",ctx->{
                var p=ctx.player();float health=p.getHealth();
                boolean protectedNow=QueenEntrances.protectedNow(p);
                p.hurtServer(p.level(),p.level().damageSources().magic(),4);
                return protectedNow&&p.getHealth()==health&&dev.logos.dimension.corruption.CorruptionContact.immune(p);
            })
            .check("斜侧机位避开玩家身体",ctx->{
                var mc=Minecraft.getInstance();var q=mc.level.getEntity(queen);
                return q!=null&&mc.player.getBoundingBox().inflate(.12).clip(mc.gameRenderer.getMainCamera().position(),q.position().add(0,1.2,0)).isEmpty();
            }).screenshot("queen_foreground_entrance")
            .ticks(22).screenshot("queen_rising")
            .ticks(30).screenshot("queen_regal_reveal")
            .ticks(40)
            .check("镜头与前景已退场",ctx->!QueenEntranceClient.active()&&!dev.logos.dimension.client.camera.CameraDirector.active())
            .checkServer("过场保护已结束",ctx->!QueenEntrances.protectedNow(ctx.player())&&!dev.logos.dimension.corruption.CorruptionContact.immune(ctx.player()))
            .check("女王模型到达", ctx -> {
                for (var entity : Minecraft.getInstance().level.entitiesForRendering()) if (entity.getUUID().equals(queen)) return entity instanceof CorruptedElfQueen;
                return false;
            })
            .checkServer("四格碰撞箱与眼高一致",ctx->{
                var q=ctx.player().level().getEntity(queen);
                return q!=null&&Math.abs(q.getBbHeight()-4)<.001&&Math.abs(q.getEyeHeight()-3.3)<.001;
            })
            .checkServer("女王进入战斗", ctx -> ctx.player().level().getEntity(queen) instanceof CorruptedElfQueen q && !q.entering() && !q.action().equals("queen_guard"))
            .server("恢复创造模式",ctx->ctx.player().setGameMode(GameType.CREATIVE))
            .server("四格女王全身镜头",ctx->{
                var q=ctx.player().level().getEntity(queen);
                var eye=tree.pos(0,3,-10).getBottomCenter();
                var d=q.position().add(0,2.3,0).subtract(eye.add(0,ctx.player().getEyeHeight(),0));
                DimensionTravel.teleport(ctx.player(),ctx.player().level(),eye,
                        (float)Math.toDegrees(Math.atan2(-d.x,d.z)),(float)-Math.toDegrees(Math.atan2(d.y,Math.hypot(d.x,d.z))));
            }).ticks(4).screenshot("queen_awakened")
            .step("对照白梅标题排版",ctx->dev.logos.dimension.client.shiraume.ShiraumeTitle.hold(30))
            .ticks(2).screenshot("shiraume_title_reference")
            .step("释放对照标题",ctx->dev.logos.dimension.client.shiraume.ShiraumeTitle.hold(-1))
            .teardown("恢复界面", ctx -> {
                dev.logos.dimension.client.shiraume.ShiraumeTitle.hold(-1);
                Minecraft.getInstance().options.hideGui = false;
                Minecraft.getInstance().options.fov().set(70);
            });
    }
}
