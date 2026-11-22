package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.companion.KohakuUmbrella;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.companion.KohakuCompanion;
import dev.logos.dimension.companion.OwnerWatch;
import dev.logos.item.SpellPages;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 实际客户端检查琥珀的日常（docs/KOHAKU_COMPANION.md 第 8、10 节，第四步），也在 {@code kohaku_companion} 一组。拍照用第三人称（主人背后），
 * 主人得站在地上不动她才会凑过来：
 * <ul>
 * <li>{@code kohaku_sit}：顶棚底下（晒不到太阳）你站着不动：她走到你身边鸭子坐，长杖收起来；摸头时还坐着；你一走她就站起来、又拄着长杖。</li>
 * <li>{@code kohaku_rain}：下雨、露天：嫌弃脸；你站着不动她在你身边撑伞（羁绊「亲近」时伞变大）；雨停了收伞。</li>
 * <li>{@code kohaku_rug}：屋里有一块地毯：她第一次坐上去就认下它；你挂机了就在上面趴着打盹，加一点羁绊；你一动就起来伸懒腰。</li>
 * <li>{@code kohaku_sun}：晴天露天：在你旁边趴成猫饼晒太阳；晒饱了，下一次的琥珀护膜用掉这一份。</li>
 * <li>{@code kohaku_gift}：你饿了：她走过来把一份吃的递给你。</li>
 * <li>{@code kohaku_fetch}：一场打完：她把掉落里最有意思的一样（带名字的剑，不是腐肉）叼到你脚边。</li>
 * <li>{@code kohaku_page_talk}：「帮我看看这张」（注入的魔力不够它花）、「给你零花钱」（收一先令，同一天再给就不收）。</li>
 * </ul>
 */
public abstract class KohakuLifeViews extends KohakuMoveViews {
    /** 主人站的地方（面朝 +Z，微微低头：第三人称的镜头在背后高一点）。 */
    static final Vec3 STAND = new Vec3(.5, FLOOR + 1, .5);
    static final float PITCH = 22;

    /** 主人站到 {@code at}，朝 +Z，站在地上（不飞）。 */
    static void standAt(ServerPlayer p, Vec3 at) {
        p.getAbilities().flying = false;
        p.onUpdateAbilities();
        DimensionTravel.teleport(p, p.level(), at, 0, PITCH);
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5);
    }

    static void thirdPerson(boolean on) {
        Minecraft.getInstance().options.setCameraType(on ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
    }

    /** 从前面拍（看得见她的脸）。 */
    static void frontView() {
        Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT);
    }

    /** 在主人头顶 {@code half} 格见方、{@code FLOOR + 4} 高处搭一个石头顶棚（晒不到太阳、算在屋里）。 */
    static void roof(ServerLevel level, int half) {
        for (int x = -half; x <= half; x++)
            for (int z = -half; z <= half; z++) level.setBlockAndUpdate(new BlockPos(x, FLOOR + 4, z), Blocks.SMOOTH_STONE.defaultBlockState());
    }

    /** 背包里有没有这一样（不管名字、附魔这些组件；{@code Inventory.contains(ItemStack)} 连组件一起比）。 */
    static boolean carries(ServerPlayer p, Item item) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(item)) return true;
        return false;
    }

    static String misc(KohakuCompanion c) {
        String m = c.npcMiscAnimation();
        return m == null ? "" : m;
    }

    // ---------------------------------------------------------------- 凑到你身边坐下

    @LDLRegisterClient(name = "kohaku_sit", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Sit extends KohakuLifeViews {
        private KohakuCompanion c;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("搭个顶棚（晒不到太阳）、站好、召唤", ctx -> {
                        ServerPlayer p = ctx.player();
                        roof(ctx.server().overworld(), 6);
                        standAt(p, STAND);
                        c = summonKohaku(p, STAND.add(2, 0, 2));
                    })
                    .step("第三人称", ctx -> thirdPerson(true))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("主人站着不动 10 秒", ctx -> OwnerWatch.pretendStill(ctx.player(), 220))
                    .waitUntilServer("她凑过来坐下", ctx -> "sit".equals(c.poseForTest()) && "sit_beside".equals(KohakuViews.move(c)))
                    .checkServer("坐在你身边", ctx -> c.distanceTo(ctx.player()) < 2 && "beside".equals(c.spotForTest()))
                    .checkServer("长杖收起来了", ctx -> c.getMainHandItem().isEmpty())
                    .ticks(20).screenshot("kohaku_sit")
                    .step("从前面看", ctx -> frontView()).ticks(4)
                    .screenshot("kohaku_sit_front")
                    .server("摸摸头", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        c.interact(p, InteractionHand.MAIN_HAND, Vec3.ZERO);
                    })
                    .ticks(12).screenshot("kohaku_sit_petted")
                    .checkServer("被摸头时还坐着（小动作只动脸）", ctx -> "sit".equals(c.poseForTest()) && "act_petted".equals(misc(c)))
                    .server("主人走开", ctx -> standAt(ctx.player(), STAND.add(-4, 0, 0)))
                    .waitUntilServer("她站起来", ctx -> "none".equals(c.poseForTest()))
                    .waitUntilServer("站好了、又拄着长杖", ctx -> KohakuViews.move(c).isEmpty() && !c.getMainHandItem().isEmpty())
                    .teardown("第一人称", ctx -> thirdPerson(false));
        }
    }

    // ---------------------------------------------------------------- 下雨

    @LDLRegisterClient(name = "kohaku_rain", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Rain extends KohakuLifeViews {
        private KohakuCompanion c;
        private int draws;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful").runCommand("weather rain")
                    .server("站好、召唤，羁绊到「亲近」", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, STAND);
                        c = summonKohaku(p, STAND.add(2, 0, 2));
                        c.affectionForTest(p, 200);
                    })
                    .step("第三人称", ctx -> thirdPerson(true))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .waitUntilServer("下起雨来", ctx -> ctx.server().overworld().isRainingAt(BlockPos.containing(ctx.player().getEyePosition())))
                    .waitUntilServer("她一脸嫌弃", ctx -> "face_rain".equals(misc(c)))
                    .server("主人站着不动 10 秒", ctx -> {
                        draws = KohakuUmbrella.draws();
                        OwnerWatch.pretendStill(ctx.player(), 220);
                    })
                    .waitUntilServer("她在你身边撑起伞", ctx -> "umbrella".equals(c.poseForTest()) && "umbrella".equals(KohakuViews.move(c)))
                    .checkServer("杖就是伞柄：拿在手里", ctx -> !c.getMainHandItem().isEmpty())
                    .checkServer("羁绊亲近、你在身边：伞是大的", ctx -> c.umbrella() == 2)
                    .ticks(20)
                    .check("杖头上画了伞面", ctx -> KohakuUmbrella.draws() > draws)
                    .screenshot("kohaku_umbrella")
                    .step("从前面看", ctx -> frontView()).ticks(4)
                    .screenshot("kohaku_umbrella_front")
                    .server("羁绊回到「熟悉」：她自己的小伞", ctx -> c.affectionForTest(ctx.player(), 60))
                    .waitUntilServer("伞变小", ctx -> c.umbrella() == 1).ticks(6)
                    .screenshot("kohaku_umbrella_small")
                    .runCommand("weather clear")
                    .waitUntilServer("雨停了，她收伞", ctx -> !"umbrella".equals(c.poseForTest()) && c.umbrella() == 0)
                    // 中途失败留下的雨：每个场景开头都会 weather clear
                    .teardown("第一人称", ctx -> thirdPerson(false));
        }
    }

    // ---------------------------------------------------------------- 她的地毯

    @LDLRegisterClient(name = "kohaku_rug", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Rug extends KohakuLifeViews {
        private static final BlockPos CARPET = new BlockPos(3, FLOOR + 1, 3);
        private KohakuCompanion c;
        private int claims, naps;
        private float affection;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("屋里（顶棚）铺一块地毯，站好、召唤", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        roof(level, 6);
                        level.setBlockAndUpdate(CARPET, Blocks.ORANGE_CARPET.defaultBlockState());
                        standAt(p, STAND);
                        c = summonKohaku(p, STAND.add(-2, 0, 2));
                        claims = KohakuCompanion.rugClaims();
                        naps = KohakuCompanion.rugNaps();
                    })
                    .step("第三人称", ctx -> thirdPerson(true))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("主人站着不动 10 秒", ctx -> OwnerWatch.pretendStill(ctx.player(), 220))
                    .waitUntilServer("她去地毯上坐下", ctx -> "sit".equals(c.poseForTest()) && "rug".equals(c.spotForTest()))
                    .checkServer("认下了这块地毯", ctx -> KohakuCompanion.rugClaims() > claims && CARPET.equals(KohakuCompanion.rugForTest(ctx.player())))
                    .checkServer("坐在地毯正中", ctx -> Math.hypot(c.getX() - CARPET.getX() - .5, c.getZ() - CARPET.getZ() - .5) < .5)
                    .ticks(20).screenshot("kohaku_rug")
                    .server("主人挂机", ctx -> {
                        ServerPlayer p = ctx.player();
                        affection = Companions.bond(p, Companions.KOHAKU_DEF).affection();
                        OwnerWatch.pretendStill(p, OwnerWatch.AFK_TICKS + 100);
                    })
                    .waitUntilServer("她在地毯上趴着打盹", ctx -> "loaf".equals(c.poseForTest()) && "sleep_loaf".equals(KohakuViews.move(c)) && c.curled())
                    .ticks(20).screenshot("kohaku_rug_nap")
                    .waitUntilServer("今天第一次在那里睡着：加一点羁绊", ctx -> KohakuCompanion.rugNaps() > naps)
                    .checkServer("羁绊多了", ctx -> Companions.bond(ctx.player(), Companions.KOHAKU_DEF).affection() > affection)
                    .server("主人走开", ctx -> standAt(ctx.player(), STAND.add(-3, 0, -2)))
                    .waitUntilServer("她起来", ctx -> "loaf_up".equals(KohakuViews.move(c)))
                    .waitUntilServer("起来以后伸个懒腰", ctx -> "act_cat_stretch".equals(misc(c)))
                    .teardown("第一人称", ctx -> thirdPerson(false));
        }
    }

    // ---------------------------------------------------------------- 晒太阳

    @LDLRegisterClient(name = "kohaku_sun", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Sun extends KohakuLifeViews {
        private KohakuCompanion c;
        private Mob zombie;
        private int wards, fulls;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("晴天露天，站好、召唤", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, STAND);
                        c = summonKohaku(p, STAND.add(2, 0, 2));
                        fulls = KohakuCompanion.sunFulls();
                    })
                    .step("第三人称", ctx -> thirdPerson(true))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("主人站着不动 10 秒", ctx -> OwnerWatch.pretendStill(ctx.player(), 220))
                    .waitUntilServer("她在你旁边趴成猫饼晒太阳", ctx -> "loaf".equals(c.poseForTest()) && "sun".equals(c.spotForTest())
                            && "sleep_loaf".equals(KohakuViews.move(c)))
                    .checkServer("就在你旁边", ctx -> c.distanceTo(ctx.player()) < 3.5)
                    .ticks(20).screenshot("kohaku_sun")
                    .server("快晒满了", ctx -> c.sunAlmostForTest(40))
                    .waitUntilServer("晒饱了", ctx -> c.sunnedForTest() && KohakuCompanion.sunFulls() > fulls)
                    .runCommand("difficulty normal")
                    .server("来了一只僵尸", ctx -> {
                        c.topUp();
                        wards = KohakuCompanion.wards();
                        zombie = zombie(ctx.server().overworld(), c.position().add(0, 0, 6), c);
                    })
                    .waitUntilServer("她起来凝护膜", ctx -> KohakuCompanion.wards() > wards)
                    .checkServer("晒饱的那一份用在这一次护膜上了", ctx -> !c.sunnedForTest())
                    .server("收掉僵尸", ctx -> zombie.discard())
                    .teardown("第一人称", ctx -> thirdPerson(false));
        }
    }

    // ---------------------------------------------------------------- 给你吃的

    @LDLRegisterClient(name = "kohaku_gift", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Gift extends KohakuLifeViews {
        private static final List<Item> FOODS = List.of(Items.COOKED_COD, Items.COOKED_SALMON, Items.SUSPICIOUS_STEW, Items.BREAD, Items.COOKIE,
                Items.GOLDEN_CARROT);
        private KohakuCompanion c;
        private int gifts;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty easy")
                    .server("站好、饿着肚子，召唤（这一回一定给）", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, STAND);
                        aim(p, STAND.add(0, p.getEyeHeight(), 0), STAND.add(0, .8, 4));
                        p.getFoodData().setFoodLevel(10);
                        p.getFoodData().setSaturation(0);
                        KohakuCompanion.luckyForTest(true);
                        gifts = KohakuCompanion.foodGifts();
                        c = summonKohaku(p, STAND.add(0, 0, 4));
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .waitUntilServer("她走过来递给你", ctx -> "give".equals(misc(c)))
                    .checkServer("手里拿着吃的", ctx -> FOODS.stream().anyMatch(f -> c.getMainHandItem().is(f)))
                    .ticks(10).screenshot("kohaku_gift")
                    .waitUntilServer("给了", ctx -> KohakuCompanion.foodGifts() > gifts)
                    .checkServer("背包里多了一份吃的", ctx -> FOODS.stream().anyMatch(f -> carries(ctx.player(), f)))
                    .checkServer("今天给过一次", ctx -> ctx.player().getData(Companions.KOHAKU_DAYS).foods() == 1)
                    .waitUntilServer("递完，又拄着长杖", ctx -> !"give".equals(misc(c)) && !c.getMainHandItem().isEmpty()
                            && FOODS.stream().noneMatch(f -> c.getMainHandItem().is(f)))
                    .teardown("不再必给", ctx -> KohakuCompanion.luckyForTest(false));
        }
    }

    // ---------------------------------------------------------------- 猫的战利品

    @LDLRegisterClient(name = "kohaku_fetch", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Fetch extends KohakuLifeViews {
        private KohakuCompanion c;
        private Mob zombie;
        private int fetches;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty easy")
                    .server("站好、召唤（这一回一定叼）", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, STAND);
                        aim(p, STAND.add(0, p.getEyeHeight(), 0), STAND.add(0, .8, 5));
                        KohakuCompanion.luckyForTest(true);
                        c = summonKohaku(p, STAND.add(1.5, 0, 1.5));
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("来了一只僵尸", ctx -> {
                        fetches = KohakuCompanion.fetches();
                        zombie = zombie(ctx.server().overworld(), STAND.add(0, 0, 7), ctx.player());
                    })
                    .waitUntilServer("她打起来了", ctx -> c.mind().foe() != null)
                    .server("僵尸倒下，掉了腐肉和一把带名字的剑", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        Vec3 at = zombie.position();
                        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Clawblade"));
                        level.addFreshEntity(new ItemEntity(level, at.x, at.y + .3, at.z, new ItemStack(Items.ROTTEN_FLESH, 2)));
                        level.addFreshEntity(new ItemEntity(level, at.x + .6, at.y + .3, at.z, sword));
                        zombie.discard();
                    })
                    .waitUntilServer("她把剑叼起来", ctx -> c.getMainHandItem().is(Items.DIAMOND_SWORD))
                    .ticks(10).screenshot("kohaku_fetch")
                    .waitUntilServer("放到你脚边", ctx -> KohakuCompanion.fetches() > fetches)
                    .waitUntilServer("你捡到了那把剑", ctx -> carries(ctx.player(), Items.DIAMOND_SWORD))
                    .teardown("不再必叼", ctx -> KohakuCompanion.luckyForTest(false));
        }
    }

    // ---------------------------------------------------------------- 帮我看看这张、零花钱

    @LDLRegisterClient(name = "kohaku_page_talk", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class PageTalk extends KohakuLifeViews {
        private static final Vec3 TALK_EYE = new Vec3(.5, FLOOR + 2.62, -1.9), AT = new Vec3(.5, FLOOR + 1, 1.2);
        private KohakuCompanion c;
        private long day;

        private static Item coin(String id) {
            return BuiltInRegistries.ITEM.getValue(LogosDimension.id(id));
        }

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("站好、召唤", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, TALK_EYE, AT.add(0, .9, 0));
                        c = summonKohaku(p, AT);
                    })
                    .step("对准", ctx -> aimClient(AT.add(0, .9, 0)))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("手里拿一张注入太少的载页，背包里一枚先令；潜行右键她", ctx -> {
                        ServerPlayer p = ctx.player();
                        day = ctx.server().overworld().getOverworldClockTime() / 24000;
                        ItemStack page = SpellPages.inscribed("(spell :name \"弹\" :mana 1 :trigger cast (projectile (on-hit (damage target 3 arcane))))", null);
                        p.setShiftKeyDown(true);
                        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        c.interact(p, InteractionHand.MAIN_HAND, Vec3.ZERO);
                        p.setShiftKeyDown(false);
                        p.setItemInHand(InteractionHand.MAIN_HAND, page);
                        // 先令放在背包里（不放手上：手上是那张载页）
                        p.getInventory().setItem(20, new ItemStack(coin("cat_shilling")));
                    })
                    .waitUntil("对话打开", ctx -> CompanionTalkViews.talk() != null)
                    .step("字出完 → 聊天", ctx -> {
                        CompanionTalkViews.talk().skip();
                        CompanionTalkViews.talk().choose("chat");
                    }).ticks(4)
                    .step("帮我看看这张", ctx -> CompanionTalkViews.talk().choose("chat.critique"))
                    .waitUntil("她说注入的魔力不够它花", ctx -> CompanionTalkViews.said("critique_short"))
                    .step("字出完", ctx -> CompanionTalkViews.talk().skip()).ticks(6)
                    .screenshot("kohaku_critique")
                    .checkServer("今天看过一次", ctx -> ctx.player().getData(Companions.KOHAKU_DAYS).critiqueDay() == day)
                    .step("给你零花钱", ctx -> {
                        CompanionTalkViews.talk().skip();
                        CompanionTalkViews.talk().choose("chat.allowance");
                    })
                    .waitUntil("她收下", ctx -> CompanionTalkViews.said("allowance_given"))
                    .checkServer("先令给出去了，记下今天", ctx -> !carries(ctx.player(), coin("cat_shilling"))
                            && ctx.player().getData(Companions.KOHAKU_DAYS).allowanceDay() == day)
                    .step("再给一次", ctx -> {
                        CompanionTalkViews.talk().skip();
                        CompanionTalkViews.talk().skip();
                        CompanionTalkViews.talk().choose("chat.allowance");
                    })
                    .waitUntil("今天的收过了", ctx -> CompanionTalkViews.said("allowance_today"))
                    .step("关掉", ctx -> Minecraft.getInstance().setScreen(null)).ticks(4);
        }
    }
}
