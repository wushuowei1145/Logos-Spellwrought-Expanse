package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.companion.CompanionLines;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.companion.LacertaCompanion;
import dev.logos.dimension.companion.LacertaDays;
import dev.logos.dimension.companion.LacertaWarmth;
import dev.logos.dimension.companion.OwnerWatch;
import dev.logos.dimension.companion.ShiraumeCompanion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 拉塞塔的日常（阶段 D，docs/LACERTA_COMPANION.md 第 20 节），同组 {@code lacerta_companion}：
 * <ul>
 * <li>{@code lacerta_hoard_edge}：金块凸在地上、只有东边空着，你站着发呆，她认下它、坐到东边那条边上面朝外，宣告一句；守满一会儿 +2 羁绊。</li>
 * <li>{@code lacerta_hoard_top}：下界合金块嵌在地里、四边都挨着方块，她坐在它顶上正中、面朝你。</li>
 * <li>{@code lacerta_dragon_curl}：巨龙形态你在床上睡下，她盘成一圈，怀里的空位正好圈住床；睡够醒来你有绯翼余温和抗火，她伸个懒腰。</li>
 * <li>{@code lacerta_dragon_pet}：巨龙形态站在她头前空手右键是撸龙头（{@code petted}），从侧面右键是骑上去。</li>
 * <li>{@code lacerta_roast}：对话「帮我烤一下」，包袱里的生牛肉烤熟给你（她先尝一块）；强行烤糊时是木炭。</li>
 * <li>{@code lacerta_cold}：雪地里她觉得冷，收起翅膀裹住自己、贴着你、嘴硬一句；你陷进细雪时她在身边你冻得慢。</li>
 * <li>{@code lacerta_gift}：你饿了，她把包袱里的熟牛排塞给你一块。</li>
 * <li>{@code lacerta_jealous}：你当着她的面摸白梅的头，她撇开脸哼一声；摸摸她就好。</li>
 * <li>{@code lacerta_loot}：打完一场，她捡起地上的钻石喊「我的！」，捡完捧出来给你。</li>
 * </ul>
 */
public abstract class LacertaDailyViews extends LacertaViews {
    /** 日常计数（{@link LacertaCompanion#lifeCountsForTest}）的下标。 */
    static final int CLAIMS = 0, BONDS = 1, GIFTS = 2, ROASTS = 3, BURNT = 4, PRESENTS = 5, MINES = 6, WARMED = 7, JEALOUS = 8, WARMTH = 9;

    static int count(int i) {
        return LacertaCompanion.lifeCountsForTest()[i];
    }

    static boolean lastLine(String id) {
        return CompanionLines.last().endsWith(id);
    }

    /** 宝藏场景：{@code hoard} 放好以后你站在北边发呆，她认下、坐上去。 */
    abstract static class Hoard extends LacertaDailyViews {
        LacertaCompanion c;
        int claims;

        abstract BlockPos hoard();

        abstract void build(ServerLevel level);

        /** 她坐在哪、朝哪（前方的水平向量）。 */
        abstract Vec3 seat();

        abstract Vec3 facing();

        ScenarioBuilder sitDown(ScenarioBuilder s, String shot) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("摆好宝藏；你站在北边，她在旁边", ctx -> {
                        build(ctx.server().overworld());
                        ServerPlayer p = ctx.player();
                        standAt(p, new Vec3(3.5, FLOOR + 1, -1.5));
                        c = summonLacerta(p, new Vec3(.5, FLOOR + 1, .5));
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("你站着发呆", ctx -> {
                        claims = count(CLAIMS);
                        OwnerWatch.pretendStill(ctx.player(), 220);
                    })
                    .waitUntilServer("她坐到宝藏上守着", ctx -> "hoard".equals(c.poseForTest()))
                    .checkServer("坐的位置对", ctx -> c.position().distanceTo(seat()) < .12)
                    .checkServer("坐着", ctx -> c.isNpcSitting())
                    .checkServer("朝向对", ctx -> {
                        double yaw = Math.toRadians(c.yBodyRot);
                        return new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)).dot(facing()) > .9;
                    })
                    .waitUntilServer("认下了，宣告一句", ctx -> count(CLAIMS) > claims && lastLine("hoard_claim"))
                    .checkServer("记在你身上", ctx -> LacertaDays.NONE != null && ctx.player().getData(Companions.LACERTA_DAYS).hoard()
                            .equals(java.util.Optional.of(GlobalPos.of(Level.OVERWORLD, hoard()))));
            return shootHer(s.ticks(10), shot, () -> c, 40, 3.6, 2.2).checkServer("拍的时候还坐着", ctx -> c.isNpcSitting() && "hoard".equals(c.poseForTest()));
        }
    }

    @LDLRegisterClient(name = "lacerta_hoard_edge", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class HoardEdge extends Hoard {
        static final BlockPos GOLD = new BlockPos(3, FLOOR + 1, 3);
        private int bonds;
        private float affection;

        @Override BlockPos hoard() { return GOLD; }

        @Override
        void build(ServerLevel level) {
            level.setBlockAndUpdate(GOLD, Blocks.GOLD_BLOCK.defaultBlockState());
            // 北、南、西三边挨着石头，只有东边空着（你站在北边：她不坐朝着你的那一边，坐空着的那一边）
            for (BlockPos n : new BlockPos[]{GOLD.north(), GOLD.south(), GOLD.west()}) level.setBlockAndUpdate(n, Blocks.STONE.defaultBlockState());
        }

        @Override Vec3 seat() { return new Vec3(3.5 + .28, FLOOR + 2, 3.5); }

        @Override Vec3 facing() { return new Vec3(1, 0, 0); }

        @Override
        public void define(ScenarioBuilder s) {
            sitDown(s, "lacerta_hoard_edge");
            back(s).server("记下羁绊", ctx -> {
                        bonds = count(BONDS);
                        affection = c.bond(ctx.player()).affection();
                    })
                    .waitUntilServer("守满一会儿：今天头一次 +2 羁绊", ctx -> count(BONDS) > bonds && c.bond(ctx.player()).affection() >= affection + 1.99f);
        }
    }

    @LDLRegisterClient(name = "lacerta_hoard_top", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class HoardTop extends Hoard {
        static final BlockPos NETHERITE = new BlockPos(3, FLOOR, 3);

        @Override BlockPos hoard() { return NETHERITE; }

        @Override
        void build(ServerLevel level) {
            // 嵌在地里：四边都是地面
            level.setBlockAndUpdate(NETHERITE, Blocks.NETHERITE_BLOCK.defaultBlockState());
        }

        @Override Vec3 seat() { return new Vec3(3.5, FLOOR + 1, 3.5); }

        @Override Vec3 facing() { return new Vec3(0, 0, -1); }

        @Override
        public void define(ScenarioBuilder s) {
            sitDown(s, "lacerta_hoard_top");
        }
    }

    @LDLRegisterClient(name = "lacerta_dragon_curl", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class DragonCurl extends LacertaDailyViews {
        /** 怀里的空位要对准的地方：床头那一格往床尾半格（躺着的你的身子中间）；床朝南。 */
        static final Vec3 POCKET = Vec3.atBottomCenterOf(HEAD).subtract(0, 0, .5);
        private LacertaCompanion c;
        private int warmth;

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful").runCommand("time set midnight").runCommand("gamerule advance_time false")
                    .runCommand("gamerule players_sleeping_percentage 101")
                    .server("一张床，站在床边；她在远一点的地方", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        bed(level, FOOT);
                        standAt(p, BEDSIDE);
                        c = summonLacerta(p, new Vec3(-5.5, FLOOR + 1, -4.5), 9);
                        fillMana(p, 20000);
                        p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 20 * 120, 0, false, false));
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("化身巨龙", ctx -> c.transformForTest(ctx.player(), false))
                    .ticks(20)
                    .server("看着床", ctx -> aim(ctx.player(), BEDSIDE.add(0, 1.62, 0), Vec3.atCenterOf(HEAD)))
                    .checkServer("主人躺下", ctx -> sleep(ctx.player()))
                    .waitUntilServer("她盘成一圈（sleep）", ctx -> "night".equals(c.poseForTest()) && "sleep".equals(misc(c)))
                    .checkServer("怀里的空位正好圈住床", ctx -> {
                        double yaw = Math.toRadians(c.yBodyRot);
                        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)), left = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
                        Vec3 pocket = c.position().add(left.scale(.78)).subtract(forward.scale(.39));
                        return Math.abs(pocket.x - POCKET.x) < .05 && Math.abs(pocket.z - POCKET.z) < .05;
                    });
            shoot(s.ticks(20), "lacerta_dragon_curl_top", POCKET.add(0, 11, .02), POCKET);
            shootDragon(s, "lacerta_dragon_curl", () -> c, -50, 9, 5);
            back(s).waitUntilServer("睡够了", ctx -> ctx.player().isSleepingLongEnough())
                    .server("醒来", ctx -> {
                        warmth = count(WARMTH);
                        ctx.player().stopSleepInBed(true, true);
                    })
                    .waitUntilServer("你身上有绯翼余温和抗火", ctx -> count(WARMTH) > warmth && ctx.player().hasEffect(LacertaWarmth.EFFECT)
                            && ctx.player().hasEffect(MobEffects.FIRE_RESISTANCE))
                    .waitUntilServer("她伸个懒腰", ctx -> "act_stretch".equals(misc(c)))
                    .teardown("夜晚照常过去", ctx -> {
                        var server = ctx.server();
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamerule players_sleeping_percentage 100");
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamerule advance_time true");
                    });
        }
    }

    @LDLRegisterClient(name = "lacerta_dragon_pet", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class DragonPet extends LacertaDailyViews {
        private LacertaCompanion c;

        private static void click(ServerPlayer p, LacertaCompanion c) {
            p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            c.interact(p, InteractionHand.MAIN_HAND, Vec3.ZERO);
        }

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("召唤、化身巨龙；让她留在原地", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, new Vec3(.5, FLOOR + 1, -6.5));
                        c = summonLacerta(p, SPOT, 9);
                        fillMana(p, 20000);
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("变身", ctx -> {
                        c.transformForTest(ctx.player(), false);
                        c.stayForTest(true);
                    })
                    .ticks(30)
                    .server("站到她头前，空手右键", ctx -> {
                        ServerPlayer p = ctx.player();
                        c.setYRot(0);
                        c.yBodyRot = 0;
                        c.setYHeadRot(0);
                        standAt(p, SPOT.add(0, 0, 5));
                        click(p, c);
                    })
                    .checkServer("这是她头前", ctx -> c.inFrontOfHeadForTest(ctx.player().position()))
                    .checkServer("没骑上去", ctx -> ctx.player().getVehicle() == null)
                    .waitUntilServer("撸龙头（petted）", ctx -> "petted".equals(misc(c)));
            shootDragon(s.ticks(10), "lacerta_dragon_petted", () -> c, 40, 10, 4.5);
            back(s).server("站到她侧面，空手右键", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, c.position().add(3.5, 0, 0));
                        click(p, c);
                    })
                    .waitUntilServer("骑上去了", ctx -> ctx.player().getVehicle() == c)
                    .teardown("下来", ctx -> ctx.player().stopRiding());
        }
    }

    @LDLRegisterClient(name = "lacerta_roast", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Roast extends LacertaDailyViews {
        private static final Vec3 TALK_EYE = new Vec3(.5, FLOOR + 2.62, -1.9), AT = new Vec3(.5, FLOOR + 1, 1.2);
        private LacertaCompanion c;

        /** 回到话题、选「帮我烤一下」，等她说 {@code line}。 */
        private static ScenarioBuilder roast(ScenarioBuilder s, String line) {
            return s.waitUntil("话题里有「帮我烤一下」", ctx -> CompanionTalkViews.talk() != null && CompanionTalkViews.talk().options().contains("chat.roast"))
                    .step("帮我烤一下", ctx -> CompanionTalkViews.talk().choose("chat.roast"))
                    .waitUntil("她说：" + line, ctx -> CompanionTalkViews.said(line));
        }

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("她面前；包袱里五块生牛肉；这回不烤糊", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, new Vec3(TALK_EYE.x, FLOOR + 1, TALK_EYE.z));
                        aim(p, TALK_EYE, AT.add(0, .9, 0));
                        c = summonLacerta(p, AT);
                        c.pouch(p).setItem(0, new ItemStack(Items.BEEF, 5));
                        LacertaCompanion.lifeForTest(false, false, false);
                    })
                    .step("对准", ctx -> aimClient(AT.add(0, .9, 0)))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("潜行右键她", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        p.setShiftKeyDown(true);
                        c.interact(p, InteractionHand.MAIN_HAND, Vec3.ZERO);
                        p.setShiftKeyDown(false);
                    })
                    .waitUntil("对话打开", ctx -> CompanionTalkViews.talk() != null)
                    .step("字出完", ctx -> CompanionTalkViews.talk().skip()).ticks(6)
                    .step("聊天", ctx -> CompanionTalkViews.talk().choose("chat")).ticks(6);
            roast(s, "roast_done")
                    .checkServer("烤好的给你（她先尝了一块）", ctx -> ctx.player().getInventory().countItem(Items.COOKED_BEEF) == 4)
                    .checkServer("包袱里没有生牛肉了", ctx -> c.pouch(ctx.player()).countItem(Items.BEEF) == 0);
            s.ticks(10).screenshot("lacerta_roast_done")
                    .step("跳过", ctx -> {
                        CompanionTalkViews.talk().skip();
                        CompanionTalkViews.talk().skip();
                    })
                    .server("再放三块生鸡肉；这回烤糊", ctx -> {
                        c.pouch(ctx.player()).setItem(0, new ItemStack(Items.CHICKEN, 3));
                        LacertaCompanion.lifeForTest(true, false, false);
                    });
            roast(s, "roast_burnt")
                    .checkServer("烤成了三块木炭", ctx -> ctx.player().getInventory().countItem(Items.CHARCOAL) == 3)
                    .step("跳过", ctx -> {
                        CompanionTalkViews.talk().skip();
                        CompanionTalkViews.talk().skip();
                    })
                    .server("包袱空了", ctx -> LacertaCompanion.lifeForTest(null, false, false));
            roast(s, "roast_none")
                    .step("关掉对话", ctx -> net.minecraft.client.Minecraft.getInstance().setScreen(null))
                    .teardown("不再强行定", ctx -> LacertaCompanion.lifeForTest(null, false, false));
        }
    }

    @LDLRegisterClient(name = "lacerta_cold", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Cold extends LacertaDailyViews {
        private LacertaCompanion c;
        private int warmed;

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("一片雪地；你站着，她在两步外", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        for (int x = -6; x <= 6; x++) {
                            for (int z = -6; z <= 6; z++) level.setBlockAndUpdate(new BlockPos(x, FLOOR, z), Blocks.SNOW_BLOCK.defaultBlockState());
                        }
                        ServerPlayer p = ctx.player();
                        standAt(p, new Vec3(.5, FLOOR + 1, -2.5));
                        c = summonLacerta(p, new Vec3(.5, FLOOR + 1, 1.5));
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .waitUntilServer("她觉得冷", ctx -> c.coldForTest())
                    .waitUntilServer("收起翅膀裹住自己", ctx -> c.wingsFolded())
                    .waitUntilServer("嘴硬说一句", ctx -> lastLine("cold_snuggle"))
                    .waitUntilServer("委屈、鼓嘴轮着来", ctx -> "face_sad".equals(misc(c)) || "face_pout".equals(misc(c)))
                    .waitUntilServer("贴着你", ctx -> c.distanceTo(ctx.player()) < 2.4);
            shootHer(s.ticks(10), "lacerta_cold", () -> c, 30, 3.2, 1.8);
            back(s).server("你陷进细雪", ctx -> {
                        warmed = count(WARMED);
                        ServerPlayer p = ctx.player();
                        ctx.server().overworld().setBlockAndUpdate(p.blockPosition(), Blocks.POWDER_SNOW.defaultBlockState());
                    })
                    .waitUntilServer("她在身边，你冻得慢一半", ctx -> count(WARMED) > warmed + 5);
        }
    }

    @LDLRegisterClient(name = "lacerta_gift", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Gift extends LacertaDailyViews {
        private LacertaCompanion c;
        private int gifts;

        @Override
        public void define(ScenarioBuilder s) {
            // 和平难度会自己把饱食度补回去
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("站好、召唤", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, new Vec3(.5, FLOOR + 1, -1.5));
                        c = summonLacerta(p, SPOT);
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("你饿了；她包袱里有三块熟牛排", ctx -> {
                        ServerPlayer p = ctx.player();
                        gifts = count(GIFTS);
                        p.getFoodData().setFoodLevel(4);
                        c.pouch(p).setItem(0, new ItemStack(Items.COOKED_BEEF, 3));
                    })
                    .waitUntilServer("她塞给你一块", ctx -> count(GIFTS) > gifts && ctx.player().getInventory().countItem(Items.COOKED_BEEF) == 1)
                    .waitUntilServer("嘴硬一句", ctx -> lastLine("give_food"))
                    .checkServer("包袱里少了一块", ctx -> c.pouch(ctx.player()).countItem(Items.COOKED_BEEF) == 2);
        }
    }

    @LDLRegisterClient(name = "lacerta_jealous", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Jealous extends LacertaDailyViews {
        private LacertaCompanion c;
        private ShiraumeCompanion shiraume;
        private int jealous;

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("她和白梅都在；这回一定吃醋", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, new Vec3(.5, FLOOR + 1, -2.5));
                        c = summonLacerta(p, new Vec3(-2.5, FLOOR + 1, .5));
                        shiraume = summonAt(p, new Vec3(3.5, FLOOR + 1, .5));
                        LacertaCompanion.lifeForTest(null, false, true);
                    })
                    .waitUntilServer("都入场了", ctx -> arrived(c) && shiraume.tickCount > 100)
                    .server("当着她的面摸白梅的头", ctx -> {
                        ServerPlayer p = ctx.player();
                        jealous = count(JEALOUS);
                        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        shiraume.interact(p, InteractionHand.MAIN_HAND, Vec3.ZERO);
                    })
                    .waitUntilServer("她撇开脸哼一声", ctx -> count(JEALOUS) > jealous && "act_hmph".equals(misc(c)))
                    .waitUntilServer("酸溜溜一句", ctx -> lastLine("jealous"));
            shootHer(s.ticks(10), "lacerta_jealous", () -> c, 20, 3.4, 1.8);
            back(s).server("摸摸她", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        c.interact(p, InteractionHand.MAIN_HAND, Vec3.ZERO);
                    })
                    .waitUntilServer("一摸就好", ctx -> "act_petted".equals(misc(c)) || "act_snuggle".equals(misc(c)))
                    .teardown("白梅回去；不再强行定", ctx -> {
                        ServerPlayer p = ctx.server().getPlayerList().getPlayer(ctx.player().getUUID());
                        if (p != null) Companions.dismiss(p, Companions.SHIRAUME_DEF);
                        LacertaCompanion.lifeForTest(null, false, false);
                    });
        }
    }

    @LDLRegisterClient(name = "lacerta_loot", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Loot extends LacertaDailyViews {
        private LacertaCompanion c;
        private Mob zombie;
        private int mines, presents;

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("站好、召唤；这一场打完一定献宝", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, new Vec3(.5, FLOOR + 1, -1.5));
                        c = summonLacerta(p, SPOT);
                        LacertaCompanion.lifeForTest(null, true, false);
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("五格外一只只剩 1 点血、手里攥着钻石的僵尸（站着不动，死了一定掉）", ctx -> {
                        mines = count(MINES);
                        presents = count(PRESENTS);
                        zombie = dummy(ctx.server().overworld(), new Vec3(.5, FLOOR + 1, 5.5), 180, 1);
                        zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND));
                        zombie.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 2f);
                    })
                    .waitUntilServer("她打倒了它", ctx -> !zombie.isAlive())
                    .waitUntilServer("她捡起来喊「我的！」", ctx -> count(MINES) > mines)
                    .waitUntilServer("捡完一会儿，捧出来给你", ctx -> count(PRESENTS) > presents && ctx.player().getInventory().countItem(Items.DIAMOND) == 1)
                    .waitUntilServer("献宝一句", ctx -> lastLine("present"))
                    .teardown("不再强行定", ctx -> LacertaCompanion.lifeForTest(null, false, false));
        }
    }
}
