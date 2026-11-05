package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.fox.SanctuaryResidents;
import dev.logos.dimension.fox.SanctuaryResidentsPiece;
import dev.logos.dimension.npc.DivineFox;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 神狐聚落的居民（实际客户端）：找一处聚落，等核心区生成，数街上的神狐（至少一只、至多 20 只，都记着这处聚落）；
 * 再打倒三只，居民数跟着减少，补回一只后加一。
 */
@LDLRegisterClient(name = "fox_sanctuary_residents", group = "fox_dev", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class FoxResidentsView implements UIScenario {
    private static StructureStart start;
    private static SanctuaryResidentsPiece residents;
    private static int before;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("fox_dev").defaultTimeoutMs(400_000).scenarioTimeoutMs(600_000);
    }

    private static AABB box() {
        var b = start.getBoundingBox();
        return new AABB(b.minX(), b.minY(), b.minZ(), b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1);
    }

    private static java.util.List<DivineFox> residents(ServerLevel level) {
        return level.getEntitiesOfClass(DivineFox.class, box(), f -> f.isAlive() && Long.valueOf(residents.sanctuary()).equals(f.sanctuary()));
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SPECTATOR).runCommand("difficulty peaceful").runCommand("weather clear")
                .step("镜头", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.hideGui = true;
                    mc.options.renderDistance().set(12);
                    mc.options.fov().set(70);
                })
                .server("找一处聚落", ctx -> {
                    start = FoxSanctuaryViews.locate(ctx.server());
                    if (start == null) throw new IllegalStateException("没找到神狐聚落");
                    residents = start.getPieces().stream().filter(p -> p instanceof SanctuaryResidentsPiece)
                            .map(p -> (SanctuaryResidentsPiece) p).findFirst().orElseThrow();
                    var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                    BlockPos first = residents.spots().getFirst();
                    DimensionTravel.teleport(ctx.player(), level, Vec3.atCenterOf(first).add(6, 8, 6), 135, 40);
                    ctx.player().setNoGravity(true);
                })
                .checkServer("落脚点不超过 20 个", ctx -> residents.planned() >= 1 && residents.planned() <= SanctuaryResidents.MAX)
                .ticks(600)
                .waitUntilServer("街上有神狐居民", ctx -> !residents(ctx.server().getLevel(LogosDimension.CLOUDSEA)).isEmpty()).ticks(600)
                .screenshot("fox_sanctuary_residents")
                .checkServer("这处聚落的神狐不超过 20 只", ctx -> {
                    var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                    int n = level.getEntitiesOfClass(DivineFox.class, box()).size();
                    System.out.println("[fox] residents planned " + residents.planned() + ", loaded " + n);
                    return n <= SanctuaryResidents.MAX;
                })
                .server("打倒三只", ctx -> {
                    var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                    before = SanctuaryResidents.alive(level, residents.sanctuary(), residents.planned());
                    residents(level).stream().limit(3).forEach(f -> f.kill(level));
                })
                .ticks(80)
                .checkServer("居民数减少", ctx -> SanctuaryResidents.alive(ctx.server().getLevel(LogosDimension.CLOUDSEA),
                        residents.sanctuary(), residents.planned()) == before - 3)
                .checkServer("补回一只", ctx -> {
                    var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                    return SanctuaryResidents.refill(level, residents)
                            && SanctuaryResidents.alive(level, residents.sanctuary(), residents.planned()) == before - 2;
                })
                .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
    }
}
