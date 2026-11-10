package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.client.screenfx.WorldStyleRenderer;
import dev.logos.dimension.cloudsea.JourneySeasons;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * 云岫峰林实景：在全新世界里找到群系，让区块按正常流程生成，从高空、峰间与谷底几处取景。
 * 只看地形与植被，不涉及结构。
 */
public abstract class CloudPeaksViews implements UIScenario {
    private static BlockPos center;
    private Vec3 look;
    protected abstract int view();

    @Override public void configure(ScenarioOptions options) {
        options.tags("cloud_peaks").defaultTimeoutMs(600000).scenarioTimeoutMs(900000);
    }

    @Override public void define(ScenarioBuilder scenario) {
        // 0 高空俯瞰 1 峰间平视 2 谷底仰视 3 柱顶俯看 4 柱顶黄山松侧面 5 溪流 6 群系边缘回望过渡带
        double[][] eyes = {{-120, 110, -120}, {-150, 50, 20}, {-130, 30, -60}, {-8, 18, -8}, {18, 4, 14}, {0, 5, 0}};
        double[][] looks = {{0, 0, 0}, {0, 70, 0}, {0, 100, 0}, {30, -6, 30}, {0, 4, 0}, {0, 0, 0}};
        scenario.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("设置画面", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true;mc.options.fov().set(view() == 0 ? 70 : 75);mc.options.renderDistance().set(20);
                mc.options.broadcastOptions();
            })
            .server("找到云岫峰林并飞过去", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                if (center == null) {
                    center = NativeWorlds.locateBiome(level, "cloudsea_cloud_peaks");
                    System.out.println("CLOUD_PEAKS center=" + center);
                    dumpHeights(level, center);
                }
                level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK)
                    .getOrThrow(JourneySeasons.CLOCK), JourneySeasons.SEASON_TICKS + 6000);
                level.getWeatherData().setRaining(false);level.getWeatherData().setThundering(false);
                level.getChunk(center);
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ());
                // 谷底和柱顶两个机位按实际高度放：谷底找附近最低的地面，柱顶找附近最高的地面
                BlockPos low = center, high = center;
                for (int dx = -48; dx <= 48; dx += 4) for (int dz = -48; dz <= 48; dz += 4) {
                    int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX() + dx, center.getZ() + dz);
                    if (h < level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, low.getX(), low.getZ())) low = center.offset(dx, 0, dz);
                    if (h > level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, high.getX(), high.getZ())) high = center.offset(dx, 0, dz);
                }
                // 溪流机位：离中心最近的一处水面
                BlockPos water = null;
                for (int r = 0; r <= 64 && water == null; r += 4) for (int dx = -r; dx <= r && water == null; dx += 4) for (int dz = -r; dz <= r; dz += 4) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int h = level.getHeight(Heightmap.Types.WORLD_SURFACE, center.getX() + dx, center.getZ() + dz);
                    var p = new BlockPos(center.getX() + dx, h - 1, center.getZ() + dz);
                    // 要开阔的水面：四个方向 6 格外也都是水，相机不会贴着陡坡
                    int open = 0;
                    for (var dir : net.minecraft.core.Direction.Plane.HORIZONTAL)
                        if (level.getFluidState(p.relative(dir, 6)).is(net.minecraft.tags.FluidTags.WATER)) open++;
                    if (level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER) && open >= 3) {water = p;break;}
                }
                int lowY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, low.getX(), low.getZ());
                int highY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, high.getX(), high.getZ());
                System.out.printf("CLOUD_PEAKS ground=%d low=%s@%d high=%s@%d%n", ground, low, lowY, high, highY);
                if (view() == 6) {
                    // 从中心沿八个方向往外走，取离中心最远的群系边界（过渡带最完整）；站在边界外侧方，斜着看过渡带
                    var biome = net.minecraft.resources.ResourceKey.create(Registries.BIOME, LogosDimension.id("cloudsea_cloud_peaks"));
                    BlockPos edge = null;Vec3 dir = null;int best = Integer.MAX_VALUE;
                    for (int k = 0; k < 8; k++) {
                        double a = k * Math.PI / 4;var dv = new Vec3(Math.cos(a), 0, Math.sin(a));
                        for (int dist = 16; dist < 320; dist += 8) {
                            var p = BlockPos.containing(center.getX() + dv.x * dist, 0, center.getZ() + dv.z * dist);
                            level.getChunk(p);
                            int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
                            if (!level.getBiome(p.atY(h)).is(biome)) {
                                if (best == Integer.MAX_VALUE || dist > best) {best = dist;edge = p.atY(h);dir = dv;}
                                break;
                            }
                        }
                    }
                    if (edge == null) {edge = center.atY(ground);dir = new Vec3(1, 0, 0);}
                    System.out.println("CLOUD_PEAKS edge=" + edge + " dist=" + best);
                    var side = new Vec3(-dir.z, 0, dir.x);
                    var eye6 = Vec3.atBottomCenterOf(edge).add(dir.scale(70)).add(side.scale(80)).add(0, 55, 0);
                    look = Vec3.atBottomCenterOf(edge).add(dir.scale(-60)).add(0, 25, 0);
                    var d6 = look.subtract(eye6.add(0, ctx.player().getEyeHeight(), 0));
                    DimensionTravel.teleport(ctx.player(), level, eye6, (float) Math.toDegrees(Math.atan2(-d6.x, d6.z)),
                        (float) -Math.toDegrees(Math.atan2(d6.y, Math.hypot(d6.x, d6.z))));
                    ctx.player().getAbilities().flying = true;ctx.player().onUpdateAbilities();
                    return;
                }
                if (view() == 5) {
                    if (water == null) water = center.atY(ground);
                    System.out.println("CLOUD_PEAKS water=" + water);
                    var w = Vec3.atBottomCenterOf(water);
                    // 朝最近的柱林看：水面上方几格，往柱顶方向抬头
                    var toPeak = new Vec3(high.getX() + .5 - w.x, 0, high.getZ() + .5 - w.z);
                    var away = toPeak.lengthSqr() < 1 ? new Vec3(1, 0, 0) : toPeak.normalize();
                    var eye5 = clear(level, w.add(away.scale(-10)).add(0, 6, 0));
                    look = new Vec3(high.getX() + .5, w.y + 22, high.getZ() + .5);
                    var d5 = look.subtract(eye5.add(0, ctx.player().getEyeHeight(), 0));
                    DimensionTravel.teleport(ctx.player(), level, eye5, (float) Math.toDegrees(Math.atan2(-d5.x, d5.z)),
                        (float) -Math.toDegrees(Math.atan2(d5.y, Math.hypot(d5.x, d5.z))));
                    ctx.player().getAbilities().flying = true;ctx.player().onUpdateAbilities();
                    return;
                }
                var base = view() == 2 ? new Vec3(center.getX() + .5, ground, center.getZ() + .5)
                    : view() >= 3 ? new Vec3(high.getX() + .5, highY, high.getZ() + .5) : new Vec3(center.getX() + .5, ground, center.getZ() + .5);
                var e = base.add(eyes[view()][0], eyes[view()][1], eyes[view()][2]);
                if (view() <= 1) {
                    // 群系画风看镜头所在的群系：高空和峰间两个机位放在群系里面，沿群系伸得最远的方向退出去再回望
                    var inward = farthestInside(level, center);
                    double back = Math.min(inward.y * .7, view() == 0 ? 140 : 110);
                    e = base.add(inward.x * back, eyes[view()][1], inward.z * back);
                    // 退到的地方可能正好是石柱，至少比脚下高出一截
                    var at = BlockPos.containing(e);
                    level.getChunk(at);
                    e = new Vec3(e.x, Math.max(e.y, level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ()) + (view() == 0 ? 40 : 12)), e.z);
                }
                e = clear(level, e);
                look = (view() >= 2 ? base : new Vec3(center.getX(), ground, center.getZ())).add(looks[view()][0], looks[view()][1], looks[view()][2]);
                var d = look.subtract(e.add(0, ctx.player().getEyeHeight(), 0));
                DimensionTravel.teleport(ctx.player(), level, e, (float) Math.toDegrees(Math.atan2(-d.x, d.z)),
                    (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
                ctx.player().getAbilities().flying = true;ctx.player().onUpdateAbilities();
            })
            .waitUntil("区块到达", ctx -> Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.hasChunkAt(BlockPos.containing(look)))
            .ticks(260)
            .step("对准", ctx -> {
                var player = Minecraft.getInstance().player;var d = look.subtract(player.getEyePosition());
                player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
                player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
            }).ticks(40)
            // 同一个机位拍两张：关掉群系画风的原画，和国画
            .step("原画", ctx -> {
                ClientConfig.BIOME_STYLE.set(0.0);
                WorldStyleRenderer.settleBiomeFade();
            }).ticks(2)
            .screenshot("cloud_peaks_" + view() + "_plain")
            .step("国画", ctx -> {
                ClientConfig.BIOME_STYLE.set(1.0);
                WorldStyleRenderer.settleBiomeFade();
                System.out.println("CLOUD_PEAKS ink level=" + WorldStyleRenderer.biomeLevel());
            }).ticks(2)
            .screenshot("cloud_peaks_" + view());
        if (view() == 4) {
            // 夜里纸色跟着雾色暗下去，不能是一张白纸
            scenario.server("入夜", ctx -> {
                    var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                    level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK)
                        .getOrThrow(JourneySeasons.CLOCK), JourneySeasons.SEASON_TICKS + 18000);
                }).ticks(60)
                .screenshot("cloud_peaks_4_night");
        }
        scenario.teardown("恢复界面", ctx -> {
                Minecraft.getInstance().options.hideGui = false;
                ClientConfig.BIOME_STYLE.set(1.0);
            });
    }

    /** 机位要在空气里：地形一改，原来按偏移算的机位可能落进石柱里，截出一片黑。往上挪到脚和头都是空气为止 */
    private static Vec3 clear(net.minecraft.server.level.ServerLevel level, Vec3 e) {
        level.getChunk(BlockPos.containing(e));
        for (int i = 0; i < 256; i++) {
            var feet = BlockPos.containing(e);
            if (level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()) return e;
            e = e.add(0, 1, 0);
        }
        return e;
    }

    /** 八个方向里群系从中心伸得最远的那个：x、z 是方向，y 是伸出去多远（格）。 */
    private static Vec3 farthestInside(net.minecraft.server.level.ServerLevel level, BlockPos center) {
        var biome = net.minecraft.resources.ResourceKey.create(Registries.BIOME, LogosDimension.id("cloudsea_cloud_peaks"));
        Vec3 best = new Vec3(1, 0, 0);
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            int reach = 0;
            for (int dist = 8; dist < 320; dist += 8) {
                var p = BlockPos.containing(center.getX() + Math.cos(a) * dist, 0, center.getZ() + Math.sin(a) * dist);
                level.getChunk(p);
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
                if (!level.getBiome(p.atY(h)).is(biome)) break;
                reach = dist;
            }
            if (reach > best.y) best = new Vec3(Math.cos(a), reach, Math.sin(a));
        }
        return best;
    }

    /** 导出中心周围 384×384 格的地表高度、水面与群系，供离线画俯视高度图看整体布局。 */
    private static void dumpHeights(net.minecraft.server.level.ServerLevel level, BlockPos center) {
        int r = 192;var out = new StringBuilder();
        // 没加载的区块 getHeight 会直接给世界最低点，先把整片生成出来
        for (int cx = (center.getX() - r) >> 4; cx <= (center.getX() + r) >> 4; cx++)
            for (int cz = (center.getZ() - r) >> 4; cz <= (center.getZ() + r) >> 4; cz++) level.getChunk(cx, cz);
        var biome = net.minecraft.resources.ResourceKey.create(Registries.BIOME, LogosDimension.id("cloudsea_cloud_peaks"));
        for (int dz = -r; dz < r; dz += 2) {
            for (int dx = -r; dx < r; dx += 2) {
                int x = center.getX() + dx, z = center.getZ() + dz;
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int w = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                boolean water = level.getFluidState(new BlockPos(x, w - 1, z)).is(net.minecraft.tags.FluidTags.WATER);
                boolean in = level.getBiome(new BlockPos(x, h, z)).is(biome);
                out.append(h).append(water ? "w" : "").append(in ? "" : "o").append(dx + 2 < r ? "," : "");
            }
            out.append(System.lineSeparator());
        }
        try {
            var file = java.nio.file.Path.of("cloud_peaks_heights.csv");
            java.nio.file.Files.writeString(file, out);
            System.out.println("CLOUD_PEAKS heights=" + file.toAbsolutePath());
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }

    @LDLRegisterClient(name="cloud_peaks_overview", group="cloud_peaks", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Overview extends CloudPeaksViews { @Override protected int view() { return 0; } }
    @LDLRegisterClient(name="cloud_peaks_between", group="cloud_peaks", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Between extends CloudPeaksViews { @Override protected int view() { return 1; } }
    @LDLRegisterClient(name="cloud_peaks_valley", group="cloud_peaks", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Valley extends CloudPeaksViews { @Override protected int view() { return 2; } }
    @LDLRegisterClient(name="cloud_peaks_summit", group="cloud_peaks", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Summit extends CloudPeaksViews { @Override protected int view() { return 3; } }
    @LDLRegisterClient(name="cloud_peaks_pine", group="cloud_peaks", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Pine extends CloudPeaksViews { @Override protected int view() { return 4; } }
    @LDLRegisterClient(name="cloud_peaks_river", group="cloud_peaks", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class River extends CloudPeaksViews { @Override protected int view() { return 5; } }
    @LDLRegisterClient(name="cloud_peaks_edge", group="cloud_peaks", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Edge extends CloudPeaksViews { @Override protected int view() { return 6; } }
}
