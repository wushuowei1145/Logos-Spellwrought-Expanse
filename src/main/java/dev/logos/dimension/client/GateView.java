package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.vfx.GateIgnition;
import dev.logos.dimension.gate.Gate;
import dev.logos.dimension.gate.GateStoneBlockEntity;
import dev.logos.dimension.gate.GateStyle;
import dev.logos.dimension.gate.GateTravel;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.phys.Vec3;

/**
 * 远行之门的实际客户端检查：大号门没点亮的样子、点亮特效的各阶段（经服务端发包）、点亮后楼梯和活板门的补缝、
 * 小号门；再让玩家真走进门到远行大陆，看到达一侧的小号门，走回来。
 */
@LDLRegisterClient(name = "gate_views", group = "gate", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class GateView implements UIScenario {
    private static final BlockPos STONE = new BlockPos(0, 99, 0), PIVOT = new BlockPos(9, 0, 3);
    private boolean previousHideGui;
    private long started;
    private BlockPos arrival;
    private BlockPos worldgenStone;
    private java.util.UUID standId;
    private int firstCount, lateCount, laterCount;

    @Override public void configure(ScenarioOptions o) { o.tags("gate").defaultTimeoutMs(90000).scenarioTimeoutMs(420000); }

    /** 平整一块草地，把大号门放在中间。 */
    private static void stage(ServerLevel level) {
        for (int x = -40; x <= 130; x++) for (int z = -40; z <= 40; z++) {
            level.setBlock(new BlockPos(x, 98, z), Blocks.DIRT.defaultBlockState(), 2);
            level.setBlock(new BlockPos(x, 99, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            for (int y = 100; y < 125; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
        BlockPos origin = STONE.subtract(PIVOT);
        level.getStructureManager().get(LogosDimension.id("gate/journey_gate")).orElseThrow().placeInWorld(level, origin, origin,
                new StructurePlaceSettings().setRotation(Rotation.NONE).setRotationPivot(PIVOT).setIgnoreEntities(true), level.getRandom(), 2);
    }

    private ScenarioBuilder at(ScenarioBuilder s, String name, double x, double y, double z, float yaw, float pitch) {
        return s.server(name, ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), new Vec3(x, y, z), yaw, pitch)).ticks(10);
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set 6000").runCommand("weather clear").runCommand("difficulty peaceful")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance();
                    previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = true;
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    mc.options.fov().set(70);
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                    mc.player.getAbilities().flying = true;
                })
                .server("搭门", ctx -> stage(ctx.server().overworld()));
        at(s, "正面机位", .5, 102, -20, 0, -4).ticks(30).screenshot("big_unlit_front")
                .check("大号门地面中心是启门石", ctx -> Minecraft.getInstance().level.getBlockState(STONE).is(Gate.GATE_STONE.get()))
                .check("蓝色羊毛没有留下", ctx -> !Minecraft.getInstance().level.getBlockState(STONE.above()).is(Blocks.BLUE_WOOL));
        s.server("灌满", ctx -> ((GateStoneBlockEntity) ctx.server().overworld().getBlockEntity(STONE)).receiveMana(1000))
                .waitUntil("特效开始", ctx -> GateIgnition.count() > 0)
                .step("记下时刻", ctx -> started = Minecraft.getInstance().level.getGameTime());
        for (int t : new int[]{6, 14, 22, 26, 32, 44, 60})
            s.waitUntil("第 " + t + " 刻", ctx -> Minecraft.getInstance().level.getGameTime() - started >= t).screenshot("ignite_" + t);
        s.waitUntil("门洞填满", ctx -> Minecraft.getInstance().level.getBlockState(STONE.offset(0, 9, 0)).is(Gate.GATE_PORTAL.get()))
                .ticks(30).screenshot("big_lit_front");
        at(s, "左下楼梯近景", -3, 101, -3.5, 0, 10).screenshot("edge_stairs_low");
        at(s, "拱顶近景", 0, 105.5, -5, 0, -30).screenshot("edge_top");
        at(s, "背面", .5, 102, 20, 180, -4).screenshot("big_lit_back");
        at(s, "侧面", 12, 104, -12, 45, 0).screenshot("big_lit_angle");
        s.runCommand("time set 18000").ticks(20).screenshot("big_lit_angle_night");
        s.runCommand("time set 6000");
        // 每种样式：正面、斜面和背面，门面都沿 X；再各放一扇转到 Z 向的
        GateStyle[] styles = GateStyle.values();
        s.server("生成五种门", ctx -> {
            for (GateStyle style : styles) {
                int x = 30 + style.ordinal() * 16;
                GateTravel.createGate(ctx.server().overworld(), new BlockPos(x, 99, -18), Direction.Axis.X, style);
                GateTravel.createGate(ctx.server().overworld(), new BlockPos(x, 99, 18), Direction.Axis.Z, style);
            }
        }).ticks(5);
        for (GateStyle style : styles) {
            int x = 30 + style.ordinal() * 16;
            String name = style.name().toLowerCase(java.util.Locale.ROOT);
            at(s, name + " 正面", x + .5, 101.5, -34, 0, -6).ticks(20).screenshot(name + "_front");
            at(s, name + " 斜面", x + 11, 103, -28, 40, 2).screenshot(name + "_angle");
            at(s, name + " 背面", x + .5, 101.5, -2, 180, -6).screenshot(name + "_back");
            at(s, name + " 转向", x + .5 + 15, 102, 18.5, 90, -4).screenshot(name + "_turned");
        }
        s.runCommand("time set 18000").ticks(20);
        for (GateStyle style : styles) {
            int x = 30 + style.ordinal() * 16;
            at(s, style + " 夜里", x + 11, 103, -28, 40, 2).screenshot(style.name().toLowerCase(java.util.Locale.ROOT) + "_night");
        }
        s.runCommand("time set 6000");
        // 走世界生成那条路放一次大号门：结构、模板池都接上才放得出来
        at(s, "去放结构的地方", 180.5, 102, -20, 0, -4).ticks(40)
                .runCommand("place structure logos_dimension:journey_gate 180 99 0").ticks(10)
                // 结构按地表高度放，不一定在 99 层
                .server("找结构里的启门石", ctx -> {
                    var level = ctx.server().overworld();
                    for (var p : BlockPos.betweenClosed(160, level.getMinY(), -20, 200, 140, 20)) if (level.getBlockState(p).is(Gate.GATE_STONE.get())) worldgenStone = p.immutable();
                    if (worldgenStone != null) DimensionTravel.teleport(ctx.player(), level, worldgenStone.offset(0, 4, -20).getBottomCenter(), 0, -4);
                }).ticks(30).screenshot("worldgen_gate")
                .check("结构放出了启门石", ctx -> worldgenStone != null)
                .check("结构放出的门没有点亮", ctx -> worldgenStone != null && !Minecraft.getInstance().level.getBlockState(worldgenStone.above()).is(Gate.GATE_PORTAL.get()));
        // 真走进去
        s.setGameMode(GameType.SURVIVAL)
                .server("走进大号门", ctx -> {
                    ctx.player().getAbilities().flying = false;
                    DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), new Vec3(.5, 100, .5), 0, 0);
                })
                .waitUntil("到达远行大陆", ctx -> Minecraft.getInstance().level.dimension() == LogosDimension.CLOUDSEA)
                .server("看到达一侧", ctx -> {
                    arrival = ctx.player().blockPosition();
                    ctx.player().setPos(arrival.getX() + .5, arrival.getY() + 3, arrival.getZ() - 11.5);
                    ctx.player().connection.teleport(arrival.getX() + .5, arrival.getY() + 3, arrival.getZ() - 11.5, 0, 0);
                    ctx.player().getAbilities().mayfly = true;
                    ctx.player().getAbilities().flying = true;
                    ctx.player().onUpdateAbilities();
                })
                .ticks(40).screenshot("cloudsea_arrival")
                .check("到达一侧是点亮的门", ctx -> Minecraft.getInstance().level.getBlockState(arrival).is(Gate.GATE_PORTAL.get()))
                .server("走回门里", ctx -> {
                    ctx.player().getAbilities().flying = false;
                    ctx.player().onUpdateAbilities();
                    ctx.player().setPortalCooldown(0);
                    ctx.player().connection.teleport(arrival.getX() + .5, arrival.getY(), arrival.getZ() + .5, 0, 0);
                })
                .waitUntil("回到主世界", ctx -> Minecraft.getInstance().level.dimension() == Level.OVERWORLD)
                .ticks(20)
                .check("回到原来那扇门", ctx -> Minecraft.getInstance().player.blockPosition().closerThan(STONE, 12));
        // 传送票：玩家留在主世界，放一个盔甲架过门。远行大陆里没有玩家，它还在走刻只能是因为传送票；票过期后就停了。
        // 先等玩家自己那张票过期（300 刻）。
        s.server("站到门外", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), new Vec3(.5, 101, -8), 0, 0))
                .ticks(320)
                .server("盔甲架过门", ctx -> {
                    var level = ctx.server().overworld();
                    var stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
                    stand.snapTo(.5, 100, .5);
                    level.addFreshEntity(stand);
                    standId = stand.getUUID();
                })
                .ticks(120)
                .server("读盔甲架", ctx -> {
                    var far = ctx.server().getLevel(LogosDimension.CLOUDSEA).getEntity(standId);
                    firstCount = far == null ? -1 : far.tickCount;
                })
                .check("过门后另一侧在走刻", ctx -> firstCount > 20)
                .ticks(320)
                .server("票过期后再读", ctx -> {
                    var far = ctx.server().getLevel(LogosDimension.CLOUDSEA).getEntity(standId);
                    lateCount = far == null ? -1 : far.tickCount;
                })
                .ticks(60)
                .server("再读一次", ctx -> {
                    var far = ctx.server().getLevel(LogosDimension.CLOUDSEA).getEntity(standId);
                    laterCount = far == null ? -1 : far.tickCount;
                })
                .check("票过期后另一侧不再走刻", ctx -> laterCount == lateCount);
        s.teardown("恢复", ctx -> {
            var mc = Minecraft.getInstance();
            mc.options.hideGui = previousHideGui;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        });
    }
}
