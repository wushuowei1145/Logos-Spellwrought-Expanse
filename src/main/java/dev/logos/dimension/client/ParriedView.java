package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.item.LongswordItem;
import dev.logos.dimension.item.OathSpearItem;
import dev.logos.dimension.item.UchigatanaItem;
import dev.logos.dimension.longsword.LongswordCombat;
import dev.logos.dimension.npc.DivineFox;
import dev.logos.dimension.npc.NpcEntities;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 被弹反的实际客户端检查：四把近战武器的 {@code parried} 动作（第一人称、第三人称正面与背后截图）；
 * 真实按键架刀弹开神狐的一刀，神狐僵直、突刺反击命中；神狐格开玩家的刀，玩家僵直；走 AI 的神狐僵直时站住不动。
 */
@LDLRegisterClient(name="parried", group="parried", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class ParriedView implements UIScenario {
    private boolean previousHideGui;
    private int foxId, cowId, side;
    private float foxHealth;
    private Vec3 foxAt;
    private double staggerDrift;
    private long staggerStart, staggerTicks;

    private boolean phase(String... moves) {
        var p = LongswordClient.pose(Minecraft.getInstance().player.getUUID());
        return p != null && Arrays.asList(moves).contains(p.move());
    }
    private static void camera(CameraType type) { Minecraft.getInstance().options.setCameraType(type); }
    private DivineFox fox(net.minecraft.server.MinecraftServer server) { return (DivineFox) server.overworld().getEntity(foxId); }

    /** 只带打刀的神狐（不走 finalizeSpawn：那里会随机发弓）。 */
    private DivineFox spawnFox(ServerLevel level, double z, boolean ai) {
        var fox = NpcEntities.DIVINE_FOX.get().create(level, EntitySpawnReason.COMMAND);
        var look = new java.util.LinkedHashMap<>(dev.logos.dimension.npc.NpcParts.DIVINE_FOX.defaults());
        look.put("outfit", "outfit_miko");
        fox.setAppearance(look);
        fox.snapTo(.5, 100, z, 180, 0);
        fox.setYHeadRot(180);
        fox.setYBodyRot(180);
        fox.setNoAi(!ai);
        fox.setPersistenceRequired();
        fox.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        fox.setHealth(1000);
        fox.setKatana(1);
        level.addFreshEntity(fox);
        foxId = fox.getId();
        return fox;
    }

    /** 拿起一把武器，等拔完，在三个机位各被弹反一次并截图。 */
    private ScenarioBuilder weapon(ScenarioBuilder s, String name, Supplier<ItemStack> stack) {
        s.server("拿起" + name, ctx -> ctx.player().setItemInHand(InteractionHand.MAIN_HAND, stack.get()))
                .waitUntil(name + "待机", ctx -> phase("idle")).ticks(4);
        for (var view : List.of(CameraType.FIRST_PERSON, CameraType.THIRD_PERSON_FRONT, CameraType.THIRD_PERSON_BACK)) {
            String tag = name + "_" + view.name().toLowerCase(java.util.Locale.ROOT);
            s.step(tag + "机位", ctx -> camera(view)).ticks(2)
                    .server(tag + "被弹反", ctx -> LongswordCombat.parried(ctx.player()))
                    .waitUntil(tag + "僵直同步", ctx -> phase(Moveset.PARRIED)).ticks(3).screenshot(tag)
                    .waitUntil(tag + "回到待机", ctx -> phase("idle")).ticks(2);
        }
        return s.server("放下" + name, ctx -> ctx.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY))
                .waitUntil(name + "交回原版", ctx -> LongswordClient.pose(Minecraft.getInstance().player.getUUID()) == null);
    }

    @Override public void configure(ScenarioOptions o) { o.tags("parried").defaultTimeoutMs(60000).scenarioTimeoutMs(300000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty easy")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousHideGui = mc.options.hideGui;
                    // 隐藏界面（F1）时原版不画第一人称的手，所以保留界面。
                    mc.options.hideGui = false; mc.player.getInventory().setSelectedSlot(0);
                    camera(CameraType.FIRST_PERSON); mc.options.fov().set(70);
                    var w = mc.getWindow(); w.setWindowed(1280,720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280,720); mc.gameRenderer.resize(1280,720); mc.resizeGui();
                }).server("练刀场", ctx -> {
                    var level = ctx.server().overworld();
                    level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, ctx.server());
                    level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MONSTERS, false, ctx.server());
                    for (int x=-9;x<=9;x++) for (int z=-9;z<=12;z++) {
                        level.setBlockAndUpdate(new BlockPos(x,99,z), Blocks.STONE_BRICKS.defaultBlockState());
                        for (int y=100;y<108;y++) level.removeBlock(new BlockPos(x,y,z),false);
                    }
                    DimensionTravel.teleport(ctx.player(),level,new Vec3(.5,100,.5),0,0);
                    ctx.player().getInventory().clearContent(); ctx.player().getInventory().setSelectedSlot(0);
                    // 玩家和神狐面对面，背后机位看不到神狐：用看不见的盔甲架从侧面看。
                    var stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
                    stand.setInvisible(true); stand.setNoGravity(true); stand.snapTo(2.5, 99.6, 1.35, 90, 0); stand.setYHeadRot(90);
                    level.addFreshEntity(stand); side = stand.getId();
                }).setGameMode(GameType.SURVIVAL);
        weapon(s, "dawnvow", () -> new ItemStack(LongswordItem.DAWNVOW.get()));
        weapon(s, "blacksteel", () -> new ItemStack(LongswordItem.BLACKSTEEL.get()));
        weapon(s, "uchigatana", () -> new ItemStack(UchigatanaItem.INK.get()));
        weapon(s, "oath_spear", () -> new ItemStack(OathSpearItem.PACK_OATH.get()));
        // 真实按键：架刀弹开神狐的一刀，先只看僵直。
        s.server("拿打刀、放神狐", ctx -> {
                    ctx.player().setHealth(20);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(UchigatanaItem.INK.get()));
                    var fox = spawnFox(ctx.server().overworld(), 2.2, false);
                    fox.drawKatana(); fox.setTarget(ctx.player());
                }).waitUntil("待机", ctx -> phase("idle")).ticks(10)
                .waitUntil("侧面镜头同步", ctx -> Minecraft.getInstance().level.getEntity(side) != null)
                .step("侧面机位", ctx -> { var mc = Minecraft.getInstance(); camera(CameraType.THIRD_PERSON_BACK); mc.setCameraEntity(mc.level.getEntity(side)); })
                .server("神狐出刀", ctx -> fox(ctx.server()).katanaInput(Moveset.Input.ATTACK))
                .step("架刀", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(true);
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                })
                .waitUntil("弹刀", ctx -> phase("parry"))
                .checkServer("神狐僵直", ctx -> fox(ctx.server()).staggered())
                .check("神狐的客户端在播被弹反", ctx -> {
                    var move = ((DivineFox) Minecraft.getInstance().level.getEntity(foxId)).npcCombatMove();
                    return move != null && "katana_parried_npc".equals(move.animation());
                })
                .ticks(2).screenshot("fox_parried")
                .waitUntilServer("神狐僵直结束回到待机", ctx -> "idle".equals(fox(ctx.server()).katanaMove().name()))
                .step("松开右键", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(mc.player);
                }).waitUntil("玩家回到待机", ctx -> phase("idle"))
                // 再来一次，左键一直按着：弹刀接突刺。招式很短，截图一耽误就错过招式本身，所以等突刺的结果。
                .server("神狐再出刀", ctx -> { foxHealth = fox(ctx.server()).getHealth(); fox(ctx.server()).katanaInput(Moveset.Input.ATTACK); })
                .step("架刀并按住左键", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(true); mc.options.keyAttack.setDown(true);
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                })
                .waitUntilServer("破绽里突刺命中", ctx -> fox(ctx.server()).getHealth() < foxHealth)
                .step("松开左键与右键", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyAttack.setDown(false); mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(mc.player);
                }).waitUntil("回到待机", ctx -> phase("idle"))
                // 神狐格开玩家的刀（一半几率）：按住左键一直砍到被格开。
                .server("神狐不再出刀", ctx -> fox(ctx.server()).setTarget(null))
                .step("按住左键", ctx -> Minecraft.getInstance().options.keyAttack.setDown(true))
                .waitUntil("玩家被弹反", ctx -> phase(Moveset.PARRIED))
                .step("松开左键", ctx -> Minecraft.getInstance().options.keyAttack.setDown(false))
                .ticks(2).screenshot("player_parried_by_fox")
                .waitUntil("玩家回到待机", ctx -> phase("idle"))
                // 走 AI 的神狐被弹反时站住，播完接着追目标。
                .server("走 AI 的神狐追一只牛", ctx -> {
                    var level = ctx.server().overworld();
                    fox(ctx.server()).discard();
                    // 拉开 19 格：超过突进距离，要先跑起来才进得了僵直。
                    var fox = spawnFox(level, -8, true);
                    var cow = EntityType.COW.create(level, EntitySpawnReason.COMMAND);
                    cow.setNoAi(true); cow.setInvisible(true); cow.snapTo(.5, 100, 11.5); level.addFreshEntity(cow); cowId = cow.getId();
                    fox.setTarget(cow);
                    foxAt = fox.position();
                })
                .waitUntilServer("神狐拔刀追上去", ctx -> fox(ctx.server()).katanaMove() != null && fox(ctx.server()).position().distanceTo(foxAt) > 1)
                .server("被弹反", ctx -> {
                    var fox = fox(ctx.server());
                    fox.setDeltaMovement(Vec3.ZERO); fox.parried(ctx.player()); foxAt = fox.position();
                    staggerStart = ctx.server().overworld().getGameTime();
                })
                // 僵直期间每次轮询都量一下，场景步骤的快慢不固定，不能只看某一刻。
                .waitUntilServer("僵直结束", ctx -> {
                    var fox = fox(ctx.server());
                    if (!fox.staggered()) {
                        if (staggerTicks == 0) staggerTicks = ctx.server().overworld().getGameTime() - staggerStart;
                        return true;
                    }
                    staggerDrift = Math.max(staggerDrift, fox.position().distanceTo(foxAt));                    return false;
                })
                .checkServer("僵直时站住不动", ctx -> staggerDrift < .2)
                // 轮询可能晚一两刻，所以量出来的时长只能偏长。
                .checkServer("NPC 僵直 0.8 秒", ctx -> staggerTicks >= 16 && staggerTicks <= 19)
                .waitUntilServer("僵直结束后接着追", ctx -> fox(ctx.server()).position().distanceTo(foxAt) > 2)
                .step("回到玩家镜头", ctx -> Minecraft.getInstance().setCameraEntity(Minecraft.getInstance().player))
                .server("清场", ctx -> {
                    var level = ctx.server().overworld();
                    var stand = level.getEntity(side); if (stand != null) stand.discard();
                    fox(ctx.server()).discard();
                    var cow = level.getEntity(cowId); if (cow != null) cow.discard();
                })
                .teardown("恢复输入", ctx -> {
                    var mc=Minecraft.getInstance(); mc.options.keyAttack.setDown(false); mc.options.keyUse.setDown(false);
                    mc.options.hideGui=previousHideGui; camera(CameraType.FIRST_PERSON); mc.setCameraEntity(mc.player);
                });
    }
}
