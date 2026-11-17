package dev.logos.dimension.client.worldtree;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.item.LogosNatureBridge;
import dev.logos.dimension.item.WorldtreeBowItem;
import dev.logos.dimension.worldtree.Worldtree;
import dev.logos.dimension.worldtree.WorldtreeAim;
import dev.logos.dimension.worldtree.WorldtreeArrow;
import dev.logos.dimension.worldtree.WorldtreeDraw;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 世界树之弓的实际按键试玩：前方六只僵尸在框里（正中那只身后同一条线上还有一只），一只在框外，一头牛在框里（不是敌人）。按住右键：藤蔓框展开、
 * 每 0.2 秒锁定一个（先锁没锁过的）、最多 16 个、弓上多出虚影箭；拉满一秒后中间出现实箭。松开：追踪箭打中每个锁定，
 * 实箭笔直射中正前方那只并施加生命易伤；只用掉一支箭、扣了魔力；飞行中的虚影箭箭头指着飞的方向。
 * 再按住左键横着拉弓：没有框、不锁定，攒满 16 支后松开，所有箭水平扇形射出、穿透（正中那只身后的一只也挨到）。第一、第三人称都截图。
 */
@LDLRegisterClient(name = "worldtree_bow", group = "worldtree_bow", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class WorldtreeBowView implements UIScenario {
    private final List<Integer> inFrame = new ArrayList<>();
    private int outside, cow, center, behind;
    private int framesBefore;
    private int arrows;
    private double manaBefore, manaAfterLocks;
    private final List<Float> healthBefore = new ArrayList<>();
    /** 每个服务端刻记下的（锁定数，魔力）；查扣费时看锁定数加一的那一刻魔力掉了多少。 */
    private static final List<double[]> ledger = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile boolean listening, recording;

    @Override public void configure(ScenarioOptions o) { o.tags("worldtree_bow").defaultTimeoutMs(60000).scenarioTimeoutMs(180000); }

    private static WorldtreeDraw draw() {
        var p = Minecraft.getInstance().player;
        return p.getUseItem().get(Worldtree.DRAW.get());
    }

    private static LivingEntity mob(MinecraftServer server, int id) {
        return (LivingEntity) server.overworld().getEntity(id);
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty normal")
                .runCommand("gamerule spawn_mobs false")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance();
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                    mc.options.hideGui = false; mc.options.fov().set(70); mc.options.setCameraType(CameraType.FIRST_PERSON);
                    mc.player.getInventory().setSelectedSlot(0);
                })
                .server("射场、靶子与弓", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x = -16; x <= 16; x++) for (int z = -4; z <= 24; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.MOSS_BLOCK.defaultBlockState());
                        for (int y = 100; y < 110; y++) level.removeBlock(new BlockPos(x, y, z), false);
                    }
                    level.getEntitiesOfClass(LivingEntity.class, new AABB(-20, 95, -8, 20, 115, 30), e -> !(e instanceof net.minecraft.world.entity.player.Player)).forEach(e -> e.discard());
                    DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, 100, .5), 0, 0);
                    inFrame.clear();
                    healthBefore.clear();
                    // 朝向 0 看向 +Z：五只在框里（左右不超过约 25°），正中那只挨实箭
                    double[][] spots = {{.5, 12}, {-3.5, 10}, {4.5, 11}, {-1.5, 16}, {3, 18}};
                    for (double[] spot : spots) inFrame.add(zombie(level, spot[0], spot[1]).getId());
                    center = inFrame.getFirst();
                    // 正中那只身后同一条线上再放一只：横着拉弓的实箭穿透后也要打到它
                    behind = zombie(level, .5, 21).getId();
                    inFrame.add(behind);
                    outside = zombie(level, 12.5, 6).getId();      // 偏 64°，在框外
                    var c = EntityType.COW.create(level, EntitySpawnReason.COMMAND);
                    c.setNoAi(true); c.setPos(-2, 100, 8); c.setPersistenceRequired(); level.addFreshEntity(c);
                    cow = c.getId();
                    ctx.player().getInventory().clearContent();
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(WorldtreeBowItem.ITEM.get()));
                    ctx.player().getInventory().setItem(8, new ItemStack(Items.ARROW, 16));
                })
                .setGameMode(GameType.SURVIVAL)
                .server("记录箭数与魔力", ctx -> {
                    var p = ctx.player();
                    record(p);
                    LogosNatureBridge.mana(p, LogosNatureBridge.maxMana(p));
                    manaBefore = LogosNatureBridge.mana(p);
                    arrows = p.getInventory().countItem(Items.ARROW);
                    for (int id : inFrame) healthBefore.add(mob(ctx.server(), id).getHealth());
                })
                .waitUntil("弓到客户端", ctx -> Minecraft.getInstance().player.getMainHandItem().is(WorldtreeBowItem.ITEM.get()))
                .ticks(10).screenshot("idle")
                .step("按住右键拉弓", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(true); mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                })
                .ticks(14).screenshot("first_locks")
                .check("框展开、锁定几个、只锁框里的敌人", ctx -> {
                    var d = draw();
                    return d != null && d.locks().size() >= 2 && WorldtreeHud.lastFrame[2] > WorldtreeHud.lastFrame[0]
                            && d.locks().stream().allMatch(l -> inFrame.contains(l.entity()));
                })
                .waitUntil("五只都锁过（先锁没锁过的）", ctx -> draw() != null && draw().locks().size() >= 5).ticks(80)
                .check("前五次锁的是五只不同的僵尸", ctx -> new HashSet<>(draw().locks().subList(0, 5).stream().map(WorldtreeDraw.Lock::entity).toList()).size() == 5)
                .check("拉满后中间出现实箭", ctx -> draw().hasFull())
                .check("每只锁定的都有屏幕标记", ctx -> WorldtreeHud.marksDrawn == new HashSet<>(draw().locks().stream().map(WorldtreeDraw.Lock::entity).toList()).size())
                .screenshot("five_locked")
                .waitUntil("锁满 16 个", ctx -> draw() != null && draw().locks().size() == WorldtreeAim.MAX_LOCKS).ticks(120)
                .ticks(10)
                .check("没锁框外的僵尸、没锁牛", ctx -> draw().locks().stream().noneMatch(l -> l.entity() == outside || l.entity() == cow))
                .screenshot("sixteen_locked")
                .step("第三人称正面", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT)).ticks(3)
                .screenshot("third_person_front")
                .step("第三人称背后", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK)).ticks(3)
                .screenshot("third_person_back")
                .step("回到第一人称", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                .server("记录锁满时的魔力", ctx -> manaAfterLocks = LogosNatureBridge.mana(ctx.player()))
                .checkServer("每锁定一个扣 5 点魔力（逐刻核对，扣掉这一刻的自然回复）", ctx -> {
                    int locks = 0;
                    double regen = regen(ctx.player(), 1);
                    for (int i = 1; i < ledger.size(); i++) {
                        double[] a = ledger.get(i - 1), b = ledger.get(i);
                        if (b[0] == a[0] + 1) {
                            locks++;
                            double spent = a[1] - b[1] + regen;
                            System.out.println("[worldtree] lock " + (int) b[0] + " spent " + spent);
                            if (Math.abs(spent - WorldtreeBowItem.LOCK_MANA) > .01 + 2 * regen) return false;
                        }
                    }
                    return locks == WorldtreeAim.MAX_LOCKS;
                })
                .step("松开放箭", ctx -> { var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(mc.player); })
                .waitUntil("客户端看到在飞的虚影箭", ctx -> phantomsFaceTheirFlight() != null)
                .check("飞行中的虚影箭箭头指着飞的方向", ctx -> Boolean.TRUE.equals(phantomsFaceTheirFlight()))
                .screenshot("release")
                .waitUntilServer("实箭命中正中那只", ctx -> LogosNatureBridge.lifeVulnerability().map(effect -> mob(ctx.server(), center).hasEffect(effect)).orElse(false)).ticks(40)
                .server("停止记录", ctx -> recording = false)
                .waitUntilServer("追踪箭都落下", ctx -> ctx.server().overworld().getEntitiesOfClass(WorldtreeArrow.class,
                        new AABB(-30, 90, -10, 30, 120, 40), WorldtreeArrow::phantom).isEmpty()).ticks(160)
                .checkServer("锁定的僵尸都受了生命伤害", ctx -> {
                    for (int i = 0; i < inFrame.size(); i++) {
                        var z = mob(ctx.server(), inFrame.get(i));
                        if (z != null && z.isAlive() && z.getHealth() >= healthBefore.get(i)) return false;
                    }
                    return true;
                })
                .checkServer("框外的僵尸、牛没受伤", ctx -> mob(ctx.server(), outside).getHealth() == mob(ctx.server(), outside).getMaxHealth()
                        && mob(ctx.server(), cow).getHealth() == mob(ctx.server(), cow).getMaxHealth())
                .checkServer("正中那只挨了实箭：生命易伤 I（5 秒）", ctx -> LogosNatureBridge.lifeVulnerability().map(effect -> {
                    var e = mob(ctx.server(), center).getEffect(effect);
                    return e == null || e.getAmplifier() == 0;
                }).orElse(false))
                .checkServer("只用掉一支箭", ctx -> ctx.player().getInventory().countItem(Items.ARROW) == arrows - 1)
                .checkServer("放箭时扣 10 点魔力（实箭）", ctx -> {
                    double[] last = ledger.getLast();
                    for (int i = 1; i < ledger.size(); i++) {
                        double[] a = ledger.get(i - 1), b = ledger.get(i);
                        if (a[0] > 0 && b[0] == 0) {
                            double spent = a[1] - b[1] + regen(ctx.player(), 1);
                            System.out.println("[worldtree] release spent " + spent);
                            return Math.abs(spent - WorldtreeBowItem.STRAIGHT_MANA) < .01 + 2 * regen(ctx.player(), 1);
                        }
                    }
                    return last[0] < 0;
                })
                .check("放手后弓上不留锁定", ctx -> !Minecraft.getInstance().player.getMainHandItem().has(Worldtree.DRAW.get()))
                // ---- 左键横着拉弓
                .server("补满魔力、记下血量", ctx -> {
                    LogosNatureBridge.mana(ctx.player(), LogosNatureBridge.maxMana(ctx.player()));
                    healthBefore.clear();
                    for (int id : inFrame) healthBefore.add(mob(ctx.server(), id).getHealth());
                })
                .step("按住左键", ctx -> {
                    var mc = Minecraft.getInstance();
                    framesBefore = WorldtreeHud.framesDrawn;
                    mc.options.keyAttack.setDown(true);
                    net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.Type.MOUSE.getOrCreate(0));
                })
                .waitUntil("开始横着拉", ctx -> draw() != null && draw().sideways())
                .ticks(14).screenshot("sideways_first_locks")
                .waitUntil("攒满 16 支", ctx -> draw() != null && draw().locks().size() == WorldtreeAim.MAX_LOCKS).ticks(80)
                .check("横着拉：没有框、不锁定任何生物、拉满出了实箭", ctx -> WorldtreeHud.framesDrawn == framesBefore && WorldtreeHud.marksDrawn == 0
                        && draw().locks().stream().allMatch(l -> l.entity() == -1) && draw().hasFull())
                .screenshot("sideways_full")
                .step("第三人称正面", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT)).ticks(3)
                .screenshot("sideways_third_person_front")
                .step("第三人称背后", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK)).ticks(3)
                .screenshot("sideways_third_person_back")
                .step("回到第一人称", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                .step("松开左键", ctx -> Minecraft.getInstance().options.keyAttack.setDown(false))
                .ticks(3).screenshot("sideways_release")
                .waitUntilServer("扇形箭都落下", ctx -> ctx.server().overworld().getEntitiesOfClass(WorldtreeArrow.class,
                        new AABB(-40, 90, -10, 40, 120, 60), WorldtreeArrow::phantom).isEmpty()).ticks(120)
                .checkServer("扇形箭打到好几只僵尸", ctx -> {
                    int hurt = 0;
                    for (int i = 0; i < inFrame.size(); i++) if (mob(ctx.server(), inFrame.get(i)).getHealth() < healthBefore.get(i)) hurt++;
                    System.out.println("[worldtree] sideways hurt " + hurt);
                    return hurt >= 3;
                })
                .checkServer("穿透：正中那只身后的也挨到了", ctx -> mob(ctx.server(), behind).getHealth() < healthBefore.get(inFrame.indexOf(behind)))
                .check("松开后不再横着拉", ctx -> !WorldtreeClient.sideways() && !Minecraft.getInstance().player.isUsingItem())
                .server("收拾", ctx -> ctx.server().overworld().getEntitiesOfClass(LivingEntity.class, new AABB(-20, 95, -8, 20, 115, 30),
                        e -> !(e instanceof net.minecraft.world.entity.player.Player)).forEach(e -> e.discard()))
                .teardown("恢复输入", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false); mc.options.keyAttack.setDown(false);
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                });
    }

    /** 开始逐刻记（锁定数，魔力）；没在拉弓时锁定数记 0。 */
    private static void record(net.minecraft.server.level.ServerPlayer player) {
        ledger.clear();
        recording = true;
        if (listening) return;
        listening = true;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) -> {
            if (!recording) return;
            var p = e.getServer().getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if (p == null) return;
            var d = p.getUseItem().get(Worldtree.DRAW.get());
            ledger.add(new double[] {d == null ? 0 : d.locks().size(), LogosNatureBridge.mana(p)});
        });
    }

    /** 客户端每支在飞的虚影箭：画箭用的朝向（yRot、xRot）与它这一刻实际移动的方向夹角小于 25°；一支在飞的都没有时为 null。 */
    private static Boolean phantomsFaceTheirFlight() {
        var level = Minecraft.getInstance().level;
        int checked = 0;
        for (var e : level.entitiesForRendering()) {
            if (!(e instanceof WorldtreeArrow arrow) || !arrow.phantom()) continue;
            Vec3 moved = arrow.position().subtract(arrow.xo, arrow.yo, arrow.zo);
            if (moved.lengthSqr() < .04) continue;
            double y = Math.toRadians(arrow.getYRot()), x = Math.toRadians(arrow.getXRot());
            Vec3 facing = new Vec3(Math.sin(y) * Math.cos(x), Math.sin(x), Math.cos(y) * Math.cos(x));
            double cos = facing.dot(moved.normalize());
            System.out.println("[worldtree] phantom facing cos " + cos);
            if (cos < Math.cos(Math.toRadians(25))) return Boolean.FALSE;
            checked++;
        }
        return checked > 0 ? Boolean.TRUE : null;
    }

    /** 这段时间里 Logos 自然回复了多少魔力（检查扣费时减掉）。 */
    private static double regen(net.minecraft.server.level.ServerPlayer player, int ticks) {
        return LogosNatureBridge.regenerationPerTick(player) * ticks;
    }

    private static Mob zombie(net.minecraft.server.level.ServerLevel level, double x, double z) {
        var mob = EntityType.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        mob.setNoAi(true);
        mob.setPos(x, 100, z);
        mob.setYRot(180); mob.setYHeadRot(180); mob.yBodyRot = 180;
        mob.setPersistenceRequired();
        // 头盔免得白天着火；血量给够，锁满 16 个也打不死
        mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        mob.setHealth(200);
        level.addFreshEntity(mob);
        return mob;
    }
}
