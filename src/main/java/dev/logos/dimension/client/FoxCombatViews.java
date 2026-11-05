package dev.logos.dimension.client;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import dev.logos.dimension.npc.DivineFox;
import dev.logos.dimension.npc.LogosMagicBridge;
import dev.logos.dimension.npc.NpcEntities;
import dev.logos.dimension.npc.NpcParts;
import dev.logos.dimension.combat.Moveset;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.zombie.Husk;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.item.YumiItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 神狐战斗的实际客户端检查（docs/NPC_RACE_DIVINE_FOX.md 第 5 节）：
 * 佩刀与收刀、拔刀与连招动作、刀身狐火（{@code npc_divine_fox_katana}）；真实 AI 对一只 80 血的尸壳：
 * 发现、缠狐火、突进、砍倒（{@code npc_divine_fox_fight}）；格挡几率、魔法减免、重伤逃跑（{@code npc_divine_fox_defense}）；
 * 背弓的神狐射苦力怕、苦力怕躲神狐（{@code npc_divine_fox_bow}）；四套和弓配色的射法与俯仰（{@code npc_divine_fox_yumi}）。
 */
public abstract class FoxCombatViews implements UIScenario {
    static final int FLOOR = 99;
    static final AABB AREA = new AABB(-14, FLOOR, -14, 15, FLOOR + 8, 15);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("npc_preview").defaultTimeoutMs(90_000).scenarioTimeoutMs(240_000);
    }

    /** 白瓷台、白天、无破坏；清掉上次留下的生物。 */
    static void arena(ServerLevel level) {
        level.getGameRules().set(GameRules.MOB_GRIEFING, false, level.getServer());
        for (int x = -14; x <= 15; x++)
            for (int z = -14; z <= 15; z++) {
                level.setBlockAndUpdate(new BlockPos(x, FLOOR, z), CloudseaBlocks.PORCELAIN.get().defaultBlockState());
                for (int y = FLOOR + 1; y <= FLOOR + 8; y++) level.removeBlock(new BlockPos(x, y, z), false);
            }
        level.getEntitiesOfClass(LivingEntity.class, AREA, e -> !(e instanceof net.minecraft.world.entity.player.Player)).forEach(e -> e.discard());
    }

    static DivineFox fox(ServerLevel level, double x, double z, String outfit, boolean ai) {
        DivineFox fox = NpcEntities.DIVINE_FOX.get().create(level, EntitySpawnReason.COMMAND);
        fox.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.ZERO), EntitySpawnReason.COMMAND, null);
        Map<String, String> look = new java.util.LinkedHashMap<>(NpcParts.DIVINE_FOX.defaults());
        look.put("outfit", outfit);
        fox.setAppearance(look);
        fox.snapTo(x, FLOOR + 1, z, 0, 0);
        fox.setYHeadRot(0);
        fox.setYBodyRot(0);
        fox.setNoAi(!ai);
        fox.setPersistenceRequired();
        level.addFreshEntity(fox);
        return fox;
    }

    static void camera(ServerPlayer player, Vec3 eye, Vec3 look) {
        Vec3 d = look.subtract(eye);
        DimensionTravel.teleport(player, player.level(), eye.subtract(0, player.getEyeHeight(), 0),
                (float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) -Math.toDegrees(Math.atan2(d.y, d.horizontalDistance())));
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
    }

    static ScenarioBuilder begin(ScenarioBuilder s) {
        return s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty normal")
                .step("镜头", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.hideGui = true;
                    mc.options.fov().set(50);
                });
    }

    static List<DivineFox> foxes() {
        var level = Minecraft.getInstance().level;
        return level == null ? List.of() : level.getEntitiesOfClass(DivineFox.class, AREA).stream()
                .sorted(Comparator.comparingDouble(DivineFox::getX)).toList();
    }

    static List<DivineFox> serverFoxes(ServerLevel level) {
        return level.getEntitiesOfClass(DivineFox.class, AREA).stream().sorted(Comparator.comparingDouble(DivineFox::getX)).toList();
    }

    /** 当前场景每个服务端刻要做的观察（截图等步骤期间也不漏掉）；没有为空。 */
    private static volatile java.util.function.Consumer<ServerLevel> probe;
    private static boolean listening;

    static synchronized void watch(java.util.function.Consumer<ServerLevel> each) {
        if (!listening) {
            listening = true;
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.LevelTickEvent.Post e) -> {
                var p = probe;
                if (p != null && e.getLevel() instanceof ServerLevel level && level.dimension() == net.minecraft.world.level.Level.OVERWORLD) p.accept(level);
            });
        }
        probe = each;
    }

    static boolean playing(DivineFox fox, String animation) {
        var move = fox.npcCombatMove();
        return move != null && animation.equals(move.animation());
    }

    // ---------------------------------------------------------------- 佩刀、拔刀、连招、狐火

    @LDLRegisterClient(name = "npc_divine_fox_katana", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Katana extends FoxCombatViews {
        private static int firesBefore;

        @Override
        public void define(ScenarioBuilder s) {
            begin(s).server("神乐礼装与巫女各一只", ctx -> {
                        var level = ctx.server().overworld();
                        arena(level);
                        fox(level, -0.7, .5, DivineFox.KAGURA, false).setKatana(1);
                        fox(level, 1.7, .5, "outfit_miko", false).setKatana(2);
                        camera(ctx.player(), new Vec3(.5, FLOOR + 1.9, 3.6), new Vec3(.5, FLOOR + 1.2, .5));
                    })
                    .waitUntil("两只神狐同步", ctx -> foxes().size() == 2).ticks(100)
                    .ticks(20)
                    .check("平时只有神乐礼装的神狐佩着刀", ctx -> foxes().get(0).katanaShown() && !foxes().get(1).katanaShown()
                            && foxes().get(1).npcHiddenExtras().containsAll(List.of("Weapon", "Sheath"))
                            && !foxes().get(0).npcHiddenExtras().contains("katana_vermilion_blade"))
                    .screenshot("divine_fox_katana_carry")
                    .server("发现目标：两只都拔刀", ctx -> {
                        var level = ctx.server().overworld();
                        // 看不见、不动的牛当目标（盔甲架不是有效目标，会被立刻丢掉）
                        var dummy = EntityType.COW.create(level, EntitySpawnReason.COMMAND);
                        dummy.snapTo(.5, FLOOR + 1, -4.5);
                        dummy.setInvisible(true);
                        dummy.setNoAi(true);
                        level.addFreshEntity(dummy);
                        for (DivineFox fox : serverFoxes(level)) {
                            fox.setTarget(dummy);
                            fox.drawKatana();
                        }
                    })
                    .ticks(10)
                    .checkServer("服务端：拔刀招式", ctx -> {
                        for (DivineFox f : serverFoxes(ctx.server().overworld()))
                            System.out.println("[fox] move=" + (f.katanaMove() == null ? null : f.katanaMove().name()) + " hand=" + f.getMainHandItem()
                                    + " target=" + f.getTarget() + " anim=" + f.npcCombatMove());
                        return serverFoxes(ctx.server().overworld()).stream().allMatch(f -> f.katanaMove() != null);
                    })
                    .check("拔刀动作在播，刀露出来", ctx -> foxes().stream().allMatch(f -> playing(f, "katana_draw") && f.katanaShown()))
                    .screenshot("divine_fox_katana_draw")
                    .ticks(30)
                    .check("拔刀后进入持刀待机", ctx -> foxes().stream().allMatch(f -> playing(f, "katana_idle")))
                    .screenshot("divine_fox_katana_idle")
                    .server("出刀", ctx -> serverFoxes(ctx.server().overworld()).forEach(f -> f.katanaInput(Moveset.Input.ATTACK)))
                    .ticks(4)
                    .check("第一刀", ctx -> foxes().stream().allMatch(f -> playing(f, "katana_slash_1")))
                    .screenshot("divine_fox_katana_slash")
                    .ticks(20)
                    .server("缠上狐火", ctx -> {
                        var level = ctx.server().overworld();
                        firesBefore = DivineFoxRenderer.bladeFires();
                        for (DivineFox fox : serverFoxes(level))
                            if (!LogosMagicBridge.cast(fox, LogosMagicBridge.FOX_FIRE_WRAP, false, 100, fox.getTarget()))
                                throw new IllegalStateException("狐火缠绕施放失败");
                    })
                    .checkServer("服务端：两只都缠着魔力缠绕", ctx -> serverFoxes(ctx.server().overworld()).stream().allMatch(DivineFox::wrapped))
                    .ticks(30)
                    .check("客户端看得见缠绕，刀身画了狐火", ctx -> foxes().stream().allMatch(DivineFox::wrapped)
                            && DivineFoxRenderer.bladeFires() > firesBefore)
                    .screenshot("divine_fox_fox_fire")
                    .server("近景", ctx -> camera(ctx.player(), new Vec3(-.4, FLOOR + 1.6, 2.2), new Vec3(.2, FLOOR + 1.1, .2)))
                    .ticks(10)
                    .screenshot("divine_fox_fox_fire_close")
                    .server("右边的神狐换弓拉弦：刀回到腰间，弓上也是狐火", ctx -> {
                        DivineFox fox = serverFoxes(ctx.server().overworld()).get(1);
                        fox.giveBow(new ItemStack(YumiItem.VERMILION.get()));
                        fox.equipBow();
                        camera(ctx.player(), new Vec3(3.2, FLOOR + 1.8, 2.6), new Vec3(1.4, FLOOR + 1.2, .4));
                    })
                    .ticks(14)
                    .server("拉弓", ctx -> serverFoxes(ctx.server().overworld()).get(1).yumiInput(Moveset.Input.USE_PRESS))
                    .ticks(25)
                    .check("拉弓：手里是和弓，放着弓道的会，刀佩在腰间", ctx -> {
                        DivineFox fox = foxes().get(1);
                        return fox.holdsYumi() && playing(fox, "yumi_kai") && fox.katanaShown();
                    })
                    .screenshot("divine_fox_bow_fire")
                    .server("战斗结束", ctx -> {
                        var level = ctx.server().overworld();
                        serverFoxes(level).forEach(f -> f.setTarget(null));
                        level.getEntitiesOfClass(net.minecraft.world.entity.animal.cow.Cow.class, AREA).forEach(e -> e.discard());
                        camera(ctx.player(), new Vec3(.5, FLOOR + 1.9, 3.6), new Vec3(.5, FLOOR + 1.2, .5));
                    })
                    // 换了弓的那只刀本来就在腰间，不播收刀
                    .waitUntil("警戒结束、拿刀的神狐播收刀", ctx -> playing(foxes().get(0), "katana_sheathe")).ticks(200)
                    .ticks(20)
                    .screenshot("divine_fox_katana_sheathe")
                    .waitUntil("收刀播完：巫女收起刀，神乐礼装的仍佩着", ctx -> foxes().get(0).katanaShown() && !foxes().get(1).katanaShown()
                            && foxes().stream().allMatch(f -> f.npcCombatMove() == null)).ticks(120)
                    .screenshot("divine_fox_katana_stowed")
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    // ---------------------------------------------------------------- 真实 AI：发现、缠狐火、突进、砍倒

    @LDLRegisterClient(name = "npc_divine_fox_fight", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Fight extends FoxCombatViews {
        private static double fastest;
        private static boolean slashed, wrapped, targeted;
        private static Husk husk;

        @Override
        public void define(ScenarioBuilder s) {
            begin(s).server("一只神狐与一只 80 血的尸壳", ctx -> {
                        var level = ctx.server().overworld();
                        arena(level);
                        fastest = 0;
                        slashed = wrapped = targeted = false;
                        DivineFox fox = fox(level, .5, 6.5, "outfit_miko", true);
                        fox.setYRot(180);
                        husk = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
                        husk.snapTo(.5, FLOOR + 1, -3.5, 0, 0);
                        husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(80);
                        husk.setHealth(80);
                        // 站着不动，突进与连砍才看得清（会动的尸壳会自己走到神狐跟前）
                        husk.setNoAi(true);
                        husk.setPersistenceRequired();
                        level.addFreshEntity(husk);
                        camera(ctx.player(), new Vec3(8.5, FLOOR + 4, 1.5), new Vec3(.5, FLOOR + 1, 1.5));
                        watch(Fight::observe);
                    })
                    .waitUntilServer("遇上强敌，缠上狐火", ctx -> wrapped).ticks(300)
                    .screenshot("divine_fox_fight_fire")
                    .waitUntilServer("连砍中", ctx -> slashed).ticks(300)
                    .screenshot("divine_fox_fight_slash")
                    .waitUntilServer("尸壳被砍倒", ctx -> !husk.isAlive()).ticks(900)
                    .server("停止观察", ctx -> watch(null))
                    .checkServer("盯上了尸壳", ctx -> targeted)
                    .checkServer("决定攻击后突进（水平速度超过 0.5 格/刻）", ctx -> {
                        System.out.println("[fox] fight fastest " + fastest);
                        return fastest > .5;
                    })
                    .checkServer("神狐还活着", ctx -> !ctx.server().overworld().getEntitiesOfClass(DivineFox.class, AREA.inflate(40), DivineFox::isAlive).isEmpty())
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }

        /** 每刻记下看到的情况（突进速度、出刀、缠绕、目标）。 */
        private static void observe(ServerLevel level) {
            var foxes = level.getEntitiesOfClass(DivineFox.class, AREA.inflate(40));
            if (foxes.isEmpty()) return;
            DivineFox fox = foxes.getFirst();
            fastest = Math.max(fastest, fox.getDeltaMovement().horizontalDistance());
            slashed |= fox.katanaMove() != null && fox.katanaMove().name().startsWith("slash");
            wrapped |= fox.wrapped();
            targeted |= fox.getTarget() == husk;
        }
    }

    // ---------------------------------------------------------------- 格挡、魔法减免、逃跑

    @LDLRegisterClient(name = "npc_divine_fox_defense", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Defense extends FoxCombatViews {
        private static int arrowsBlocked, blowsBlocked;
        private static float afterMagic;
        private static Husk chaser;
        private static double startGap, widest;
        private static boolean fled;

        @Override
        public void define(ScenarioBuilder s) {
            int trials = 60;
            begin(s).server("格挡与减免", ctx -> {
                        var level = ctx.server().overworld();
                        arena(level);
                        DivineFox fox = fox(level, -4.5, .5, "outfit_miko", false);
                        var zombie = EntityType.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
                        zombie.snapTo(-4.5, FLOOR + 1, -2.5);
                        zombie.setNoAi(true);
                        level.addFreshEntity(zombie);
                        arrowsBlocked = blowsBlocked = 0;
                        for (int i = 0; i < trials; i++) {
                            var arrow = EntityType.ARROW.create(level, EntitySpawnReason.COMMAND);
                            arrow.setOwner(zombie);
                            fox.setHealth(fox.getMaxHealth());
                            fox.invulnerableTime = 0;
                            if (!fox.hurtServer(level, level.damageSources().arrow(arrow, zombie), 2)) arrowsBlocked++;
                            fox.setHealth(fox.getMaxHealth());
                            fox.invulnerableTime = 0;
                            if (!fox.hurtServer(level, level.damageSources().mobAttack(zombie), 2)) blowsBlocked++;
                        }
                        DivineFox mage = fox(level, -2.5, .5, "outfit_miko", false);
                        mage.invulnerableTime = 0;
                        mage.hurtServer(level, level.damageSources().magic(), 10);
                        afterMagic = mage.getHealth();
                    })
                    .checkServer("弹射物约八成被格挡", ctx -> {
                        System.out.println("[fox] blocked arrows " + arrowsBlocked + "/" + trials + ", blows " + blowsBlocked + "/" + trials + ", after magic " + afterMagic);
                        return arrowsBlocked >= trials * .65 && arrowsBlocked <= trials * .93;
                    })
                    .checkServer("近战约一半被格挡", ctx -> blowsBlocked >= trials * .33 && blowsBlocked <= trials * .67)
                    .checkServer("10 点魔法伤害只掉 6 点（减免 40%，铁甲不挡魔法）", ctx -> Math.abs(afterMagic - 14) < .01)
                    .server("重伤的神狐被尸壳追打", ctx -> {
                        var level = ctx.server().overworld();
                        arena(level);
                        DivineFox fox = fox(level, .5, .5, "outfit_miko", true);
                        fox.setHealth(4);
                        chaser = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
                        chaser.snapTo(.5, FLOOR + 1, -2.5, 0, 0);
                        chaser.setPersistenceRequired();
                        level.addFreshEntity(chaser);
                        fox.invulnerableTime = 0;
                        // 这一下不让格挡：直接用无法格挡的伤害
                        fox.hurtServer(level, level.damageSources().mobAttack(chaser), 0.01f);
                        fox.setLastHurtByMob(chaser);
                        chaser.setTarget(fox);
                        startGap = widest = fox.distanceTo(chaser);
                        fled = false;
                        camera(ctx.player(), new Vec3(10.5, FLOOR + 6, .5), new Vec3(.5, FLOOR + 1, .5));
                        // 每刻记下逃跑与拉开的最大距离（白瓷台四面悬空，跑到边上会被堵住）
                        watch(l -> {
                            var foxes = l.getEntitiesOfClass(DivineFox.class, AREA.inflate(20));
                            if (foxes.isEmpty() || !chaser.isAlive()) return;
                            fled |= foxes.getFirst().fleeing();
                            widest = Math.max(widest, foxes.getFirst().distanceTo(chaser));
                        });
                    })
                    .waitUntilServer("开始逃跑", ctx -> fled).ticks(100)
                    .waitUntilServer("拉开了距离", ctx -> widest > startGap + 3).ticks(100)
                    .server("停止观察", ctx -> watch(null))
                    .screenshot("divine_fox_flee")
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    // ---------------------------------------------------------------- 弓与苦力怕

    // ---------------------------------------------------------------- 和弓

    @LDLRegisterClient(name = "npc_divine_fox_yumi", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Yumi extends FoxCombatViews {
        private static void pitch(ServerLevel level, float degrees) {
            for (DivineFox fox : serverFoxes(level)) fox.setXRot(degrees);
        }

        @Override
        public void define(ScenarioBuilder s) {
            begin(s).server("四只神狐各拿一套和弓配色", ctx -> {
                        var level = ctx.server().overworld();
                        arena(level);
                        var finishes = List.of(YumiItem.BAMBOO, YumiItem.VERMILION, YumiItem.INDIGO, YumiItem.CHESTNUT);
                        for (int i = 0; i < 4; i++) {
                            // 朝向 0（+Z）：拉弓时身体转向 -X，胸口朝着 -X 一侧的镜头，靶在画面右边。
                            DivineFox fox = fox(level, 0.5, -6 + i * 5, "outfit_miko", false);
                            fox.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(finishes.get(i).get()));
                        }
                        camera(ctx.player(), new Vec3(-13, FLOOR + 2.2, 1.5), new Vec3(.5, FLOOR + 2.0, 1.5));
                    })
                    .ticks(4)
                    .check("四只都拿着和弓，手里不再按物品画", ctx -> foxes().size() == 4 && foxes().stream().allMatch(DivineFox::holdsYumi))
                    .ticks(14)
                    .screenshot("divine_fox_yumi_idle")
                    .check("执弓动作", ctx -> foxes().stream().allMatch(f -> playing(f, "yumi_idle")))
                    .server("拉弓", ctx -> serverFoxes(ctx.server().overworld()).forEach(f -> f.yumiInput(Moveset.Input.USE_PRESS)))
                    .ticks(5)
                    .screenshot("divine_fox_yumi_uchiokoshi")
                    .ticks(13)
                    .screenshot("divine_fox_yumi_daisan")
                    .ticks(14)
                    .check("拉到会", ctx -> foxes().stream().allMatch(f -> playing(f, "yumi_kai")))
                    .screenshot("divine_fox_yumi_kai")
                    .server("近景", ctx -> camera(ctx.player(), new Vec3(-5.2, FLOOR + 2.1, -4.5), new Vec3(.5, FLOOR + 2.1, -5.2)))
                    .ticks(5)
                    .screenshot("divine_fox_yumi_kai_close")
                    .server("抬头", ctx -> pitch(ctx.server().overworld(), -35))
                    .ticks(5)
                    .screenshot("divine_fox_yumi_kai_up")
                    .server("低头", ctx -> pitch(ctx.server().overworld(), 25))
                    .ticks(5)
                    .screenshot("divine_fox_yumi_kai_down")
                    .server("放箭", ctx -> {
                        pitch(ctx.server().overworld(), 0);
                        serverFoxes(ctx.server().overworld()).forEach(f -> f.yumiInput(Moveset.Input.USE_RELEASE));
                    })
                    .ticks(10)
                    .check("离れ与残心", ctx -> foxes().stream().allMatch(f -> playing(f, "yumi_hanare")))
                    .screenshot("divine_fox_yumi_zanshin")
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    @LDLRegisterClient(name = "npc_divine_fox_bow", group = "npc_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Bow extends FoxCombatViews {
        private static Creeper creeper;
        private static double startGap, closest;
        private static boolean drew, targeted, bowInHand, yumiArrow;

        @Override
        public void define(ScenarioBuilder s) {
            begin(s).server("背弓的神狐与一只苦力怕", ctx -> {
                        var level = ctx.server().overworld();
                        arena(level);
                        drew = targeted = bowInHand = yumiArrow = false;
                        DivineFox fox = fox(level, .5, 8.5, "outfit_miko", true);
                        fox.giveBow(new ItemStack(YumiItem.INDIGO.get()));
                        fox.setYRot(180);
                        creeper = EntityType.CREEPER.create(level, EntitySpawnReason.COMMAND);
                        creeper.snapTo(.5, FLOOR + 1, -1.5, 0, 0);
                        creeper.setPersistenceRequired();
                        level.addFreshEntity(creeper);
                        startGap = closest = fox.distanceTo(creeper);
                        camera(ctx.player(), new Vec3(8.5, FLOOR + 3, 6.5), new Vec3(.5, FLOOR + 1.2, 6.5));
                        watch(Bow::observe);
                    })
                    .waitUntilServer("拿弓拉弦", ctx -> drew).ticks(300)
                    .screenshot("divine_fox_bow")
                    .waitUntilServer("苦力怕被射倒", ctx -> !creeper.isAlive()).ticks(900)
                    .server("停止观察", ctx -> watch(null))
                    .checkServer("拉弓时刀收在腰间（露着）、手里是弓", ctx -> bowInHand)
                    .checkServer("盯上了苦力怕", ctx -> targeted)
                    .checkServer("射出的是和弓的箭（标记过、飞得快）", ctx -> yumiArrow)
                    .checkServer("苦力怕一直没走近神狐（像躲猫一样）", ctx -> {
                        System.out.println("[fox] creeper start " + startGap + " closest " + closest);
                        return closest >= 3;
                    })
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }

        private static void observe(ServerLevel level) {
            var foxes = level.getEntitiesOfClass(DivineFox.class, AREA.inflate(40));
            if (foxes.isEmpty()) return;
            DivineFox fox = foxes.getFirst();
            boolean drawing = "kai".equals(fox.yumiMove());
            drew |= drawing;
            bowInHand |= drawing && fox.holdsYumi() && fox.katanaShown();
            // 和弓的箭：标记过、飞得快
            for (var arrow : level.getEntitiesOfClass(net.minecraft.world.entity.projectile.arrow.AbstractArrow.class, AREA.inflate(40),
                    a -> a.getOwner() == fox && a.getDeltaMovement().length() > 3))
                yumiArrow |= arrow.getData(dev.logos.dimension.item.NatureAttachments.YUMI_ARROW);
            targeted |= fox.getTarget() == creeper;
            if (creeper.isAlive()) closest = Math.min(closest, fox.distanceTo(creeper));
        }
    }
}
