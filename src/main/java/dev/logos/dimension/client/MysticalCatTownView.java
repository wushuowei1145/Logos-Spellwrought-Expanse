package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;

import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.npc.MysticalCat;
import dev.logos.dimension.npc.MysticalCatResidents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.Vec3;

/**
 * 林隙镇子里的灵猫居民（实际客户端）：打开结构生成、找最近的一座镇子，站到镇子上空，
 * 补到上限（{@link MysticalCatResidents#TOWN_CAP}）后数一数：每只都在镇子范围内、以镇子中心为家、不会因玩家走远而消失。
 */
@LDLRegisterClient(name = "mystical_cat_town", group = "mystical_cat_world", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class MysticalCatTownView implements UIScenario {
    private static StructureStart start;
    private static BlockPos center;
    private static MysticalCat friend;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("mystical_cat_world").defaultTimeoutMs(300_000).scenarioTimeoutMs(500_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful").runCommand("weather clear")
                .step("镜头", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.hideGui = true;
                    mc.options.renderDistance().set(8);
                    mc.options.fov().set(60);
                    mc.options.broadcastOptions();
                })
                .server("找一座镇子", ctx -> {
                    var server = ctx.server();
                    try {
                        var field = net.minecraft.world.level.levelgen.WorldOptions.class.getDeclaredField("generateStructures");
                        field.setAccessible(true);
                        field.setBoolean(server.getWorldGenSettings().options(), true);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                    ServerLevel level = server.getLevel(LogosDimension.CLOUDSEA);
                    var holder = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                            .getOrThrow(ResourceKey.create(Registries.STRUCTURE, LogosDimension.id("journey_town")));
                    var found = level.getChunkSource().getGenerator().findNearestMapStructure(level, HolderSet.direct(holder), BlockPos.ZERO, 200, false);
                    if (found == null) throw new IllegalStateException("没找到林隙镇子");
                    BlockPos at = found.getFirst();
                    start = level.getChunk(at.getX() >> 4, at.getZ() >> 4, ChunkStatus.STRUCTURE_STARTS).getStartForStructure(holder.value());
                    if (start == null || !start.isValid()) throw new IllegalStateException("镇子的结构起点无效");
                    center = start.getBoundingBox().getCenter();
                    var p = ctx.player();
                    DimensionTravel.teleport(p, level, Vec3.atCenterOf(center).add(-18, 0, -18).with(net.minecraft.core.Direction.Axis.Y, 200), -45, 30);
                    p.getAbilities().flying = true;
                    p.onUpdateAbilities();
                    System.out.println("[cat] town at " + center + " box " + start.getBoundingBox());
                })
                .ticks(200)
                .server("站到镇子上空", ctx -> {
                    ServerLevel level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                    int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ());
                    var p = ctx.player();
                    DimensionTravel.teleport(p, level, new Vec3(center.getX() - 16, y + 16, center.getZ() - 16), -45, 32);
                    p.getAbilities().flying = true;
                    p.onUpdateAbilities();
                })
                .ticks(200)
                .server("补到上限", ctx -> {
                    ServerLevel level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                    for (int i = 0; i < 40 && MysticalCatResidents.count(level, start) < MysticalCatResidents.TOWN_CAP; i++)
                        MysticalCatResidents.refill(level, start, MysticalCatResidents.TOWN_CAP);
                    System.out.println("[cat] residents " + MysticalCatResidents.count(level, start));
                })
                .ticks(100)
                .checkServer("镇子里有灵猫，不超过上限", ctx -> {
                    int n = MysticalCatResidents.count(ctx.server().getLevel(LogosDimension.CLOUDSEA), start);
                    return n >= 1 && n <= MysticalCatResidents.TOWN_CAP;
                })
                .checkServer("每只都以镇子中心为家、不会消失", ctx -> {
                    ServerLevel level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                    for (MysticalCat cat : level.getEntitiesOfClass(MysticalCat.class, MysticalCatResidents.area(start))) {
                        if (!cat.hasHome() || cat.getHomePosition().distManhattan(center.atY(cat.getHomePosition().getY())) > 0) return false;
                        if (cat.removeWhenFarAway(1000) || !cat.isPersistenceRequired()) return false;
                    }
                    return true;
                })
                .checkServer("再补不会超过上限", ctx -> {
                    ServerLevel level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                    MysticalCatResidents.refill(level, start, MysticalCatResidents.TOWN_CAP);
                    return MysticalCatResidents.count(level, start) <= MysticalCatResidents.TOWN_CAP;
                })
                .ticks(200)
                .checkServer("AI 跑了十秒，灵猫还在镇子里", ctx -> MysticalCatResidents.count(ctx.server().getLevel(LogosDimension.CLOUDSEA), start) >= 1)
                .screenshot("mystical_cat_town")
                .server("摸头、喂鱼", ctx -> {
                    ServerLevel level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                    var p = ctx.player();
                    MysticalCat cat = level.getEntitiesOfClass(MysticalCat.class, MysticalCatResidents.area(start)).getFirst();
                    friend = cat;
                    DimensionTravel.teleport(p, level, cat.position().add(1.2, 0, 0), 90, 10);
                    p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                    cat.interact(p, net.minecraft.world.InteractionHand.MAIN_HAND, cat.position());
                    p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COD));
                    cat.interact(p, net.minecraft.world.InteractionHand.MAIN_HAND, cat.position());
                    p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                })
                .checkServer("摸头 +1、喂鱼 +2：亲近到 3", ctx -> friend.fondness(ctx.player()) == MysticalCat.FOND)
                .ticks(60)
                .checkServer("身边的玩家得到灵猫相伴", ctx -> ctx.player().hasEffect(dev.logos.dimension.npc.MysticalCompany.EFFECT))
                .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
    }
}
