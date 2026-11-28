package dev.logos.dimension.companion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.logos.runtime.Casting;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 拉塞塔同伴第一阶段（docs/LACERTA_COMPANION.md，接入方案第八节阶段 A）：游戏里用到的动画都在她的模型里、长度和标记对得上；
 * 她的招式文件和模型里的 crimson_* 一致、和玩家的炽焰长剑同一套数；防御向继承分段上升不封顶、魔力法强慢慢长、超出原版上限的护甲和魔咒
 * 折成有上限的减伤；防复读的罚分；床边的朝向；一天的事一天算；掌心赤焰的术式编得过；台词、对话、面板的语言键中英文都齐。
 * 看武器怎么评要真的物品（单元测试里物品的组件还没绑定），放在客户端测试 {@code lacerta_talk} 里。
 */
class LacertaTest {
    private static final String TALK = "talk.logos_dimension.lacerta.", LINE = "line.logos_dimension.lacerta.";

    private static JsonObject read(String path) throws Exception {
        try (var in = LacertaTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static JsonObject animations() throws Exception {
        return read("/assets/logos_dimension/geckolib/animations/entity/lacerta_companion.animation.json").getAsJsonObject("animations");
    }

    private static JsonObject dragonAnimations() throws Exception {
        return read("/assets/logos_dimension/geckolib/animations/entity/lacerta_dragon_beast.animation.json").getAsJsonObject("animations");
    }

    private static JsonObject moves(String moveset) throws Exception {
        return read("/data/logos_dimension/combat/movesets/" + moveset + ".json").getAsJsonObject("moves");
    }

    private static long ticks(JsonObject anim) {
        return Math.round(anim.get("animation_length").getAsDouble() * 20);
    }

    /** 时间轴里叫 {@code name} 的标记都在几秒。 */
    private static List<Double> markers(JsonObject anim, String name) {
        List<Double> at = new ArrayList<>();
        JsonObject timeline = anim.getAsJsonObject("timeline");
        if (timeline == null) return at;
        for (var e : timeline.entrySet()) {
            JsonElement v = e.getValue();
            String text = v.isJsonArray() ? v.getAsJsonArray().toString() : v.getAsString();
            if (text.contains(name)) at.add(Double.parseDouble(e.getKey()));
        }
        at.sort(Double::compare);
        return at;
    }

    private static long marker(JsonObject anim, String name) {
        List<Double> at = markers(anim, name);
        assertFalse(at.isEmpty(), "没有标记 " + name);
        return Math.round(at.get(0) * 20);
    }

    /** 表情和小动作的长度（刻）和模型一致。 */
    @Test void moodLengthsMatchTheModel() throws Exception {
        JsonObject anims = animations();
        for (var e : LacertaMood.LENGTH.entrySet()) {
            assertTrue(anims.has(e.getKey()), "模型里没有 " + e.getKey());
            assertEquals((long) e.getValue(), ticks(anims.getAsJsonObject(e.getKey())), e.getKey());
        }
        for (String a : LacertaMood.INTERACT) assertTrue(LacertaMood.LENGTH.containsKey(a) || LacertaMood.DRAGON_LENGTH.containsKey(a), a + " 没有长度");
        for (String a : LacertaMood.WING_ACTS) assertTrue(LacertaMood.LENGTH.containsKey(a), a + " 没有长度");
    }

    /** 游戏里会播的每一段动画都在她的模型里：主控制器、翅膀、表情、睡姿、招式文件里每一招的 crimson_*。 */
    @Test void everyClipTheGameUsesIsInTheModel() throws Exception {
        JsonObject anims = animations();
        List<String> used = new ArrayList<>(List.of("idle", "walk", "run", "jump", "sit", "swim_stand", "attacked", "summon", "death", "eat",
                "refuse", "pick", "wings_fold", "wings_folded", "wings_unfold", "pre_parallel0", "pre_parallel1", "pre_parallel2",
                "face_poise", "face_aloof", "face_smug", "face_happy", "face_sweet", "face_pout", "face_flustered", "face_foodie", "face_curious",
                "face_surprised", "face_angry", "face_sad", "face_sleepy", "face_battle", "sleep_together_down", "sleep_together_up",
                "fly_hover", "fly_move", "catch_hand", "catch_embrace"));
        for (LacertaRest.Tier t : LacertaRest.Tier.values()) used.add(t.clip);
        used.addAll(LacertaCompanion.WHOLE_BODY);
        for (String m : moves("lacerta_companion").keySet()) used.add(LacertaBlade.PREFIX + m);
        for (String name : used) assertTrue(anims.has(name), "模型里没有 " + name);
    }

    /** 入场、倒下、吃东西、掌心赤焰、嘲讽、收展翅的时间点和模型一致。 */
    @Test void timingsMatchTheMarkers() throws Exception {
        JsonObject anims = animations();
        JsonObject summon = anims.getAsJsonObject("summon");
        assertEquals(LacertaCompanion.SUMMON_TICKS, ticks(summon));
        assertEquals(LacertaCompanion.SUMMON_LAND, marker(summon, "land"));
        assertEquals(LacertaCompanion.DEATH_TICKS, ticks(anims.getAsJsonObject("death")));
        JsonObject eat = anims.getAsJsonObject("eat");
        assertEquals(CompanionEntity.EAT_TICKS, ticks(eat), "eat 要正好 2.4 秒");
        assertEquals(CompanionEntity.EAT_SWALLOW, marker(eat, "puff"));
        List<Double> bites = markers(eat, "bite");
        assertEquals(CompanionEntity.EAT_BITES.length, bites.size());
        for (int i = 0; i < bites.size(); i++) assertEquals(CompanionEntity.EAT_BITES[i], Math.round(bites.get(i) * 20));
        JsonObject flame = anims.getAsJsonObject("flame_throw");
        assertEquals(LacertaCompanion.FLAME_TICKS, ticks(flame));
        assertEquals(LacertaCompanion.FLAME_RELEASE, marker(flame, "throw"));
        assertEquals(24, ticks(anims.getAsJsonObject("taunt")));
        assertEquals(LacertaCompanion.WINGS_FOLD, ticks(anims.getAsJsonObject("wings_fold")));
        assertEquals(LacertaCompanion.WINGS_UNFOLD, ticks(anims.getAsJsonObject("wings_unfold")));
        assertTrue(anims.getAsJsonObject("wings_folded").get("loop").getAsBoolean());
    }

    /**
     * 她的招式文件：每一招的时长和模型里的 crimson_* 一致、接触时刻对得上 contact 标记；招式、时长、接触、张角、倍数和玩家的炽焰长剑一样；
     * 没有按键（下一招由心智挑）；心智里提到的招式（冷却、后继）都在文件里。
     */
    @Test void movesetMatchesTheClipsAndThePlayersSword() throws Exception {
        JsonObject anims = animations();
        JsonObject mine = moves("lacerta_companion"), player = moves("crimson_flame_longsword");
        for (var e : mine.entrySet()) {
            String name = e.getKey();
            JsonObject m = e.getValue().getAsJsonObject();
            JsonObject clip = anims.getAsJsonObject(LacertaBlade.PREFIX + name);
            assertEquals(ticks(clip), Math.round(m.get("length").getAsDouble() * 20), name + " 的时长");
            assertFalse(m.has("input"), name + " 不该有按键");
            if (m.has("contact")) {
                List<Double> want = new ArrayList<>();
                JsonElement c = m.get("contact");
                if (c.isJsonArray()) c.getAsJsonArray().forEach(x -> want.add(x.getAsDouble()));
                else want.add(c.getAsDouble());
                assertEquals(want, markers(clip, "contact"), name + " 的接触时刻");
            }
            JsonObject p = player.getAsJsonObject(name);
            if (p == null) continue;
            for (String k : new String[]{"length", "contact", "cone", "damage"}) {
                assertEquals(p.get(k), m.get(k), name + " 的 " + k + " 和玩家的不一样");
            }
        }
        for (String m : LacertaMind.COOLDOWN.keySet()) assertTrue(mine.has(m), "冷却里的 " + m + " 不在招式文件里");
        for (var e : LacertaMind.FLOW.entrySet()) {
            assertTrue(mine.has(e.getKey()), e.getKey());
            for (String next : e.getValue().keySet()) assertTrue(mine.has(next), next);
        }
    }

    /** 防御向继承：0 枚 50%、4 枚 100%、9 枚 200%，之后每枚 +10%；魔力法强 10% 起每枚 +5%。 */
    @Test void ratiosFollowThePlan() {
        assertEquals(.5, LacertaStats.tankRatio(0), 1e-9);
        assertEquals(.75, LacertaStats.tankRatio(2), 1e-9);
        assertEquals(1, LacertaStats.tankRatio(4), 1e-9);
        assertEquals(1.4, LacertaStats.tankRatio(6), 1e-9);
        assertEquals(2, LacertaStats.tankRatio(9), 1e-9);
        assertEquals(2.1, LacertaStats.tankRatio(10), 1e-9);
        assertEquals(3, LacertaStats.tankRatio(19), 1e-9, "不封顶");
        assertEquals(.5, LacertaStats.tankRatio(-3), 1e-9);
        for (int s = 0; s < 30; s++) assertTrue(LacertaStats.tankRatio(s + 1) > LacertaStats.tankRatio(s), "第 " + (s + 1) + " 枚没涨");
        assertEquals(.1, LacertaStats.magicRatio(0), 1e-9);
        assertEquals(.3, LacertaStats.magicRatio(4), 1e-9);
        assertEquals(.55, LacertaStats.magicRatio(9), 1e-9);
        assertEquals(.7, LacertaStats.magicRatio(12), 1e-9);
    }

    /**
     * 她自己的：生命 80 / 护甲 14 / 韧性 6 / 攻击 10 起，每枚 +12 / +2 / +1 / +1.5（第 9 枚以后生命每枚 +6），羁绊每段生命 +8、攻击 +1；
     * 继承的五样都乘 tankRatio。魔力 120 起、每枚 +20、羁绊每段 +15；回复 1.2 起；法强 0.6 起。
     */
    @Test void statsGrowAsThePlanSays() {
        CompanionStats.Owner owner = new CompanionStats.Owner(20, 20, 8, 4, 0);
        CompanionStats.Parts start = LacertaStats.parts(owner, 0, 0);
        assertEquals(.5, start.ratio(), 1e-9);
        assertEquals(80 + 10, start.health().total(), 1e-9);
        assertEquals(14 + 10, start.armor().total(), 1e-9);
        assertEquals(6 + 4, start.toughness().total(), 1e-9);
        assertEquals(10 + 2, start.attack().total(), 1e-9);
        int bonded = CompanionBond.Stage.BONDED.ordinal();
        CompanionStats.Parts nine = LacertaStats.parts(owner, 9, bonded);
        assertEquals(80 + 108, nine.health().own(), 1e-9);
        assertEquals(8 * bonded, nine.health().bond(), 1e-9);
        assertEquals(40, nine.health().inherited(), 1e-9, "九枚继承两倍");
        assertEquals(14 + 18 + 40, nine.armor().total(), 1e-9);
        assertEquals(10 + 13.5 + bonded + 8, nine.attack().total(), 1e-9);
        assertEquals(80 + 108 + 6, LacertaStats.ownHealth(10), 1e-9, "第 9 枚以后生命每枚只长一半");
        assertEquals(14 + 20, LacertaStats.ownArmor(10), 1e-9, "护甲照常长");
        assertEquals(120 + .1 * 200, LacertaStats.mana(200, 0, 0).total(), 1e-9);
        assertEquals(120 + 180 + 15 * bonded + .55 * 200, LacertaStats.mana(200, 9, bonded).total(), 1e-9);
        assertEquals(1.2, LacertaStats.regen(0, 0), 1e-9);
        assertEquals(1.2 + 1.8 + .1 * bonded, LacertaStats.regen(9, bonded), 1e-9);
        assertEquals(.6 + .1, LacertaStats.power(1, 0, 0).total(), 1e-9);
        assertEquals(.6 + .225 + .02 * bonded + .55 * 2, LacertaStats.power(2, 9, bonded).total(), 1e-9);
    }

    /**
     * 护甲 30、韧性 20 以内不折；超出的越多减得越多、永远到不了五成。魔咒保护 20 点以内照原版（满了减八成），超出的再减一点，
     * 总共不超过九成。
     */
    @Test void overflowIsBoundedAndKeepsGrowing() {
        assertEquals(0, LacertaStats.overflow(30, 20), 1e-12);
        assertEquals(0, LacertaStats.overflow(10, 5), 1e-12);
        assertEquals(.5 * (1 - Math.exp(-1)), LacertaStats.overflow(70, 20), 1e-9);
        assertEquals(LacertaStats.overflow(50, 20), LacertaStats.overflow(30, 60), 1e-9, "韧性按一半折");
        double last = 0;
        for (int a = 30; a <= 400; a += 10) {
            double o = LacertaStats.overflow(a, 20);
            assertTrue(o >= last && o < LacertaStats.OVERFLOW_MAX, a + " 点护甲折成 " + o);
            last = o;
        }
        assertEquals(10, LacertaStats.afterProtection(10, 0), 1e-6);
        assertEquals(2, LacertaStats.afterProtection(10, 20), 1e-5, "满魔咒减八成");
        float more = LacertaStats.afterProtection(10, 40);
        assertTrue(more < 2 && more > 1, "超出的再减一点：" + more);
        assertTrue(LacertaStats.afterProtection(10, 10_000) >= 1 - 1e-4, "总共不超过九成");
    }

    /**
     * 飞（阶段 B，模型第 22 版）：悬停、飞行移动是循环的主状态；拉住手、抱住是整身的，抓住的那一刻和模型的 catch 标记一致，
     * 播的时候翅膀控制器不管。
     */
    @Test void flightClipsMatchTheModel() throws Exception {
        JsonObject anims = animations();
        for (String n : new String[]{"fly_hover", "fly_move"}) assertTrue(anims.getAsJsonObject(n).get("loop").getAsBoolean(), n + " 要循环");
        assertEquals(LacertaFlight.CATCH_HAND_AT, marker(anims.getAsJsonObject("catch_hand"), "catch"));
        assertEquals(LacertaFlight.CATCH_EMBRACE_AT, marker(anims.getAsJsonObject("catch_embrace"), "catch"));
        assertTrue(LacertaCompanion.WHOLE_BODY.contains("catch_hand") && LacertaCompanion.WHOLE_BODY.contains("catch_embrace"));
        assertEquals(LacertaFlight.clipFor(CompanionBond.Stage.CLOSE), "catch_hand");
        assertEquals(LacertaFlight.clipFor(CompanionBond.Stage.TRUSTED), "catch_embrace");
        assertEquals(LacertaFlight.clipFor(CompanionBond.Stage.BONDED), "catch_embrace");
    }

    /**
     * 巨龙（阶段 C）：用到的每一段都在巨龙模型里；登场落地、昂首一吼、龙爪砍中的那一刻和时间轴标记一致；
     * 蓄压、收龙炎、起飞、落地、振翅嘲讽的长度和模型一致；飞的几段、吐息喷着那段循环。
     */
    @Test void dragonClipsMatchTheModel() throws Exception {
        JsonObject anims = dragonAnimations();
        for (String n : new String[]{"idle", "walk", "run", "appear", "claw_attack", "breath_start", "breath_loop", "breath_end", "takeoff", "land",
                "wing_flap", "fly_flap", "fly_glide", "fly_hover", "fly_dive", "fly_bank_left", "fly_bank_right"}) {
            assertTrue(anims.has(n), "巨龙模型里没有 " + n);
        }
        JsonObject appear = anims.getAsJsonObject("appear");
        assertEquals(LacertaDragon.APPEAR_TICKS, ticks(appear));
        assertEquals(LacertaDragon.APPEAR_LAND, marker(appear, "dragon_land_impact"));
        assertEquals(LacertaDragon.APPEAR_ROAR, marker(appear, "dragon_roar"));
        JsonObject claw = anims.getAsJsonObject("claw_attack");
        assertEquals(LacertaDragon.CLAW_TICKS, ticks(claw));
        assertEquals(LacertaDragon.CLAW_HIT, marker(claw, "claw_slash"));
        assertEquals(LacertaDragon.BREATH_START, ticks(anims.getAsJsonObject("breath_start")));
        assertEquals(LacertaDragon.BREATH_END, ticks(anims.getAsJsonObject("breath_end")));
        assertEquals(LacertaFlight.DRAGON_TAKEOFF, ticks(anims.getAsJsonObject("takeoff")));
        assertEquals(LacertaFlight.DRAGON_LAND, ticks(anims.getAsJsonObject("land")));
        assertEquals(32, ticks(anims.getAsJsonObject("wing_flap")), "巨龙嘲讽振翅一次 32 刻");
        for (String n : new String[]{"breath_loop", "fly_flap", "fly_glide", "fly_hover", "fly_dive", "fly_bank_left", "fly_bank_right"}) {
            assertTrue(anims.getAsJsonObject(n).get("loop").getAsBoolean(), n + " 要循环");
        }
        assertEquals("hold_on_last_frame", anims.getAsJsonObject("breath_start").get("loop").getAsString(), "蓄压停在最后一帧");
    }

    /** 巨龙形态的小动作、被摸、坐趴睡（阶段 D）：都在巨龙模型里，长度和模型一致；坐、趴、盘睡循环。 */
    @Test void dragonDailyClipsMatchTheModel() throws Exception {
        JsonObject anims = dragonAnimations();
        for (var e : LacertaMood.DRAGON_LENGTH.entrySet()) {
            assertTrue(anims.has(e.getKey()), "巨龙模型里没有 " + e.getKey());
            assertEquals((long) e.getValue(), ticks(anims.getAsJsonObject(e.getKey())), e.getKey());
        }
        for (String n : new String[]{"sit", "lie_flat", "sleep", "petted"}) {
            assertTrue(anims.getAsJsonObject(n).get("loop").getAsBoolean(), n + " 要循环");
        }
        assertTrue(LacertaMood.INTERACT.contains("petted") && LacertaMood.INTERACT.contains("act_sniff_nudge"), "挨打时掐掉");
    }

    /** 巨龙盘睡：她站的位置让怀里的空位（她的左边 0.78、身后 0.39）正好落在要圈住的地方，四个朝向都对。 */
    @Test void dragonCurlsAroundThePocket() {
        Vec3 pocket = new Vec3(10.5, 64, -3.5);
        for (float yaw : new float[]{0, 90, 180, -90, 37}) {
            Vec3 at = LacertaRest.curlAt(pocket, yaw);
            double r = Math.toRadians(yaw);
            Vec3 forward = new Vec3(-Math.sin(r), 0, Math.cos(r)), left = new Vec3(Math.cos(r), 0, Math.sin(r));
            Vec3 back = at.add(left.scale(LacertaRest.POCKET_LEFT)).subtract(forward.scale(LacertaRest.POCKET_BACK));
            assertEquals(0, back.distanceTo(pocket), 1e-9, "朝 " + yaw);
            assertEquals(Math.hypot(LacertaRest.POCKET_LEFT, LacertaRest.POCKET_BACK), at.distanceTo(pocket), 1e-9);
        }
        // 模型 sleep_pocket 的中心 (-50, 0, 25) 单位，2 倍模型 32 单位一格、缩 0.5
        assertEquals(50 / 32.0 * .5, LacertaRest.POCKET_LEFT, .01);
        assertEquals(25 / 32.0 * .5, LacertaRest.POCKET_BACK, .01);
    }

    /** 绯翼余温：近战伤害、减伤基础各 5%，羁绊每段 +2.5%；5 分钟。 */
    @Test void warmthGrowsWithTheBond() {
        assertEquals(.05, LacertaWarmth.bonus(0), 1e-9);
        assertEquals(.15, LacertaWarmth.bonus(CompanionBond.Stage.BONDED.ordinal()), 1e-9);
        assertEquals(.05, LacertaWarmth.bonus(-1), 1e-9);
        assertEquals(6000, LacertaWarmth.TICKS);
    }

    /** 巨龙的碰撞箱 3.2×2.4、眼高 1.85；挨打只受四分之一；嘴在身前、离地，喷得比龙爪远。 */
    @Test void dragonBodyFollowsThePlan() {
        assertEquals(3.2f, LacertaDragon.SIZE.width(), 1e-6);
        assertEquals(2.4f, LacertaDragon.SIZE.height(), 1e-6);
        assertEquals(1.85f, LacertaDragon.SIZE.eyeHeight(), 1e-6);
        assertEquals(.25, LacertaDragon.TAKEN, 1e-9);
        assertEquals(.95, LacertaDragon.KNOCKBACK, 1e-9);
        Vec3 mouth = LacertaDragon.mouth(Vec3.ZERO, 0);
        assertEquals(LacertaDragon.MOUTH_FORWARD, mouth.z, 1e-6, "面朝南（yaw 0）时嘴在南边");
        assertEquals(LacertaDragon.MOUTH_UP, mouth.y, 1e-6);
        assertTrue(LacertaDragon.BREATH_RANGE > LacertaDragon.CLAW_REACH * 2);
        assertTrue(LacertaMind.BREATH_TICKS >= LacertaDragon.BREATH_START + LacertaDragon.BREATH_PULSE * 5, "一口龙炎至少喷五下");
    }

    /** 你出的魔力：每秒 50 起，每枚鳞之契少 2，最少 20。 */
    @Test void dragonUpkeepShrinksWithSeals() {
        assertEquals(50, LacertaDragon.upkeep(0));
        assertEquals(50, LacertaDragon.upkeep(-3));
        assertEquals(40, LacertaDragon.upkeep(5));
        assertEquals(20, LacertaDragon.upkeep(15));
        assertEquals(32, LacertaDragon.upkeep(LacertaTalk.MAX_SEALS), "九枚 32");
        assertEquals(42, LacertaDragon.upkeep(4));
    }

    /** 她自己变身的门槛：首领、生命上限 40 以上的强敌、或者身边三只以上的敌人。 */
    @Test void sheTransformsForStrongFoesAndCrowds() {
        assertTrue(LacertaMind.worthDragon(LacertaMind.STRONG_HEALTH, false, 0));
        assertFalse(LacertaMind.worthDragon(LacertaMind.STRONG_HEALTH - 1, false, LacertaMind.CROWD - 1));
        assertTrue(LacertaMind.worthDragon(20, true, 0), "首领");
        assertTrue(LacertaMind.worthDragon(20, false, LacertaMind.CROWD), "被围");
        assertTrue(LacertaMind.DRAGON_FAR > LacertaMind.DRAGON_NEAR);
    }

    /** 巨龙飞的那一段：俯冲、左右压坡、悬停、振翅往上、滑翔。 */
    @Test void dragonFlightPicksTheRightClip() {
        assertEquals("fly_dive", LacertaCompanion.dragonFlyClip(.5, -.5, 0));
        assertEquals("fly_bank_right", LacertaCompanion.dragonFlyClip(.5, 0, 5));
        assertEquals("fly_bank_left", LacertaCompanion.dragonFlyClip(.5, 0, -5));
        assertEquals("fly_hover", LacertaCompanion.dragonFlyClip(.05, 0, 0));
        assertEquals("fly_flap", LacertaCompanion.dragonFlyClip(.4, .1, 0));
        assertEquals("fly_glide", LacertaCompanion.dragonFlyClip(.4, -.05, 0));
        assertEquals("fly_hover", LacertaCompanion.dragonFlyClip(.05, 0, 6), "悬停时转身不压坡");
    }

    /**
     * 骑龙飞：按住 W 顺着视线、疾跑更快；松开慢慢下沉；空格往上抬；A 往左（面朝南时往东）；每刻只往想要的速度靠一点（有惯性）。
     */
    @Test void ridingFliesAlongYourView() {
        Vec3 look = new Vec3(0, 0, 1);
        Vec3 first = LacertaRide.fly(Vec3.ZERO, look, 0, 1, 0, false, false);
        assertEquals(LacertaRide.FLY_SPEED * LacertaRide.ACCEL, first.z, 1e-9, "有惯性");
        assertEquals(LacertaRide.FLY_SPEED, settle(look, 1, 0, false, false).z, 1e-3);
        assertEquals(LacertaRide.FLY_SPEED * LacertaRide.SPRINT, settle(look, 1, 0, false, true).z, 1e-3);
        Vec3 glide = settle(look, 0, 0, false, false);
        assertEquals(-LacertaRide.SINK, glide.y, 1e-3, "松开慢慢下沉");
        assertTrue(settle(look, 0, 0, true, false).y >= LacertaRide.CLIMB - 1e-3, "空格往上抬");
        assertEquals(LacertaRide.STRAFE, settle(look, 0, 1, false, false).x, 1e-3, "A 往左（面朝南时往东），不越按越快");
        Vec3 braked = LacertaRide.fly(new Vec3(0, 0, LacertaRide.FLY_SPEED), look, 0, -1, 0, false, false);
        assertEquals(LacertaRide.FLY_SPEED * (1 - LacertaRide.BRAKE), braked.z, 1e-9, "S 刹住");
        assertEquals(0, settle(look, -1, 0, true, false).z, 1e-6, "按着 S 停在半空往上抬");
        Vec3 up = new Vec3(0, .6, .8);
        assertEquals(.6 * LacertaRide.FLY_SPEED, settle(up, 1, 0, false, false).y, 1e-3, "仰着头往上飞");
    }

    private static Vec3 settle(Vec3 look, float forward, float strafe, boolean up, boolean sprint) {
        Vec3 v = Vec3.ZERO;
        for (int i = 0; i < 400; i++) v = LacertaRide.fly(v, look, 0, forward, strafe, up, sprint);
        return v;
    }

    /**
     * 护落：原版的摔伤算法；会摔死（剩不到 1 点）或者摔掉三成生命上限才接；抓住以前那一段掉落拉住手减 75%、抱住减 80%。
     */
    @Test void catchesHeavyFalls() {
        assertEquals(20, LacertaFlight.fallDamage(23, 3, 1), 1e-9);
        assertEquals(0, LacertaFlight.fallDamage(2, 3, 1), 1e-9);
        assertEquals(11, LacertaFlight.fallDamage(13.5, 3, 1), 1e-9, "向上取整");
        assertTrue(LacertaFlight.worthCatching(20, 20, 20), "会摔死");
        assertTrue(LacertaFlight.worthCatching(3, 3.5, 20), "血少时一点也会摔死");
        assertTrue(LacertaFlight.worthCatching(6, 20, 20), "三成生命上限");
        assertFalse(LacertaFlight.worthCatching(5, 20, 20));
        assertFalse(LacertaFlight.worthCatching(0, 1, 20), "不掉血的不接");
        assertEquals(5, LacertaFlight.reducedFall(20, false), 1e-9);
        assertEquals(4, LacertaFlight.reducedFall(20, true), 1e-9);
    }

    /** 伴飞点在你身后、左边、比你的脚高：面朝南（yaw 0）时在北边、东边（你的左手）。 */
    @Test void escortFliesBehindToTheLeft() {
        Vec3 at = new Vec3(10, 64, 10);
        for (float yaw : new float[]{0, 90, -135, 180}) {
            Vec3 p = LacertaFlight.escortPoint(at, yaw).subtract(at);
            double r = Math.toRadians(yaw);
            Vec3 forward = new Vec3(-Math.sin(r), 0, Math.cos(r)), left = new Vec3(Math.cos(r), 0, Math.sin(r));
            assertEquals(-LacertaFlight.ESCORT_BACK, p.dot(forward), 1e-6, "身后 " + yaw);
            assertEquals(LacertaFlight.ESCORT_SIDE, p.dot(left), 1e-6, "左边 " + yaw);
            assertEquals(LacertaFlight.ESCORT_UP, p.y, 1e-6);
        }
        Vec3 south = LacertaFlight.escortPoint(Vec3.ZERO, 0);
        assertTrue(south.z < 0 && south.x > 0, south.toString());
    }

    /** 防复读：刚放过的那一招罚 0.45，往前每一招减半，放过几次就叠几次；顺手的后继加分。 */
    @Test void repeatsArePenalised() {
        assertEquals(.45, LacertaMind.repeat(List.of("flame_sweep", "cleave_1"), "flame_sweep"), 1e-9);
        assertEquals(.225, LacertaMind.repeat(List.of("cleave_1", "flame_sweep"), "flame_sweep"), 1e-9);
        assertEquals(.45 + .1125, LacertaMind.repeat(List.of("vortex_rush", "cleave_1", "vortex_rush"), "vortex_rush"), 1e-9);
        assertEquals(0, LacertaMind.repeat(List.of(), "cleave_1"), 1e-9);
        assertEquals(.5, LacertaMind.flow("vortex_rush", "cross_rend"), 1e-9);
        assertEquals(0, LacertaMind.flow(null, "cross_rend"), 1e-9);
        assertEquals(0, LacertaMind.flow("cleave_1", "skyfall_judgement"), 1e-9);
    }

    /**
     * 睡姿按羁绊：生疏守夜、熟悉正坐打盹、亲近靠着床小憩、信赖起共枕。朝向：她的前方是 (-sin yaw, cos yaw)，左边是 (cos yaw, sin yaw)。
     */
    @Test void vigilFollowsTheBond() {
        assertEquals(LacertaRest.Tier.WATCH, LacertaRest.Tier.of(CompanionBond.Stage.STRANGER));
        assertEquals(LacertaRest.Tier.DOZE, LacertaRest.Tier.of(CompanionBond.Stage.FAMILIAR));
        assertEquals(LacertaRest.Tier.REST, LacertaRest.Tier.of(CompanionBond.Stage.CLOSE));
        assertEquals(LacertaRest.Tier.TOGETHER, LacertaRest.Tier.of(CompanionBond.Stage.TRUSTED));
        assertEquals(LacertaRest.Tier.TOGETHER, LacertaRest.Tier.of(CompanionBond.Stage.BONDED));
        for (Vec3 d : new Vec3[]{new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)}) {
            double yaw = Math.toRadians(LacertaRest.yawFacing(d));
            assertEquals(d.x, -Math.sin(yaw), 1e-6, "面朝 " + d);
            assertEquals(d.z, Math.cos(yaw), 1e-6, "面朝 " + d);
            double left = Math.toRadians(LacertaRest.yawLeftTo(d));
            assertEquals(d.x, Math.cos(left), 1e-6, "左边朝 " + d);
            assertEquals(d.z, Math.sin(left), 1e-6, "左边朝 " + d);
        }
    }

    /** 夸她、看武器每天头一次加羁绊：一天记一次，各记各的；旧存档没有这一项时是没记过；存了读回来不变。 */
    @Test void daysAreKeptOnYou() {
        LacertaDays d = LacertaDays.NONE.withPraise(10).withCritique(12);
        assertEquals(10, d.praiseDay());
        assertEquals(12, d.critiqueDay());
        assertEquals(10, d.withCritique(13).praiseDay(), "别的记录不动");
        assertFalse(d.dragonForm(), "默认人形登场");
        LacertaDays dragon = d.withDragonForm(true);
        assertTrue(dragon.dragonForm());
        assertTrue(dragon.withPraise(11).dragonForm(), "别的记录不动");
        assertEquals(12, dragon.critiqueDay());
        var savedDragon = LacertaDays.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, dragon).getOrThrow();
        assertEquals(dragon, LacertaDays.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, savedDragon).getOrThrow());
        // 认下的宝藏、哪天守过：存了读回来不变，别的记录不动
        var hoard = net.minecraft.core.GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, new net.minecraft.core.BlockPos(3, 70, -8));
        LacertaDays guarded = dragon.withHoard(java.util.Optional.of(hoard)).withHoardDay(5);
        assertEquals(hoard, guarded.hoard().orElseThrow());
        assertEquals(5, guarded.hoardDay());
        assertTrue(guarded.dragonForm());
        assertTrue(LacertaDays.NONE.hoard().isEmpty());
        var savedHoard = LacertaDays.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, guarded).getOrThrow();
        assertEquals(guarded, LacertaDays.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, savedHoard).getOrThrow());
        var empty = new JsonObject();
        assertEquals(LacertaDays.NONE, LacertaDays.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, empty).getOrThrow());
        var saved = LacertaDays.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, d).getOrThrow();
        assertEquals(d, LacertaDays.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, saved).getOrThrow());
    }

    /** 掌心赤焰的术式编得过（攻击玩家开着、关着两种），注入的魔力就是她一发花的。 */
    @Test void palmFlameCompiles() throws Exception {
        String source;
        try (var in = LacertaTest.class.getResourceAsStream("/data/logos_dimension/companion/spells/lacerta/flame_bolt.logos")) {
            assertNotNull(in);
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var spell = Casting.compile(source);
        assertNotNull(spell);
        assertNotNull(Casting.compile(source.replace(":trigger cast", ":trigger cast :players true")));
        assertEquals(LacertaCompanion.FLAME_COST, spell.mana(), 1e-9);
    }

    /** 面板（档案）在网络上来回不走样：九枚以后照样涨的契印，面板上最多画九枚。 */
    @Test void sheetSurvivesTheNetwork() {
        var sheet = new CompanionSheet(7, LacertaCompanion.ID, LacertaTalk.OUTFIT,
                List.of(new CompanionSheet.Stat("health", 188, 108, 32, 150), new CompanionSheet.Stat("attack", 42, 31.5f, 4, -1)),
                List.of(new CompanionSheet.Skill("tank", CompanionSheet.PASSIVE, 0, true, List.of("210", "12.5")),
                        new CompanionSheet.Skill("vigil", CompanionSheet.PASSIVE, 0, true, List.of("#" + TALK + "skill.vigil.together"))),
                Math.min(12, LacertaTalk.MAX_SEALS), LacertaTalk.MAX_SEALS, "BONDED", 1, 10, 20, false, "adaptive", false, 9, 10, 2.3f);
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        CompanionSheet.CODEC.encode(buf, sheet);
        assertEquals(sheet, CompanionSheet.CODEC.decode(buf));
    }

    /** 她有自己的契和包袱，友人帐里有她；龙炎同族（自己的龙炎不伤她）。 */
    @Test void sheHasHerOwnBond() throws Exception {
        assertEquals("lacerta", Companions.LACERTA_DEF.id());
        assertEquals(Companions.LACERTA_DEF, Companions.def("lacerta"));
        assertTrue(Companions.ALL.contains(Companions.LACERTA_DEF));
        for (CompanionDef other : Companions.ALL) {
            if (other == Companions.LACERTA_DEF) continue;
            assertNotSame(other.bond(), Companions.LACERTA_DEF.bond());
            assertNotSame(other.pouch(), Companions.LACERTA_DEF.pouch());
        }
        var kin = read("/data/logos_dimension/tags/entity_type/dragonfire_kin.json").getAsJsonArray("values").toString();
        assertTrue(kin.contains("logos_dimension:lacerta_companion"), kin);
    }

    /** 台词、对话、面板用到的语言键，中英文都有。 */
    @Test void everyLineAndPanelKeyIsInBothLanguages() throws Exception {
        List<String> keys = new ArrayList<>(List.of("entity.logos_dimension.lacerta_companion", "item.logos_dimension.dragon_scale",
                "effect.logos_dimension.lacerta_warmth",
                "container.logos_dimension.lacerta_pouch", TALK + "name", TALK + "kana", TALK + "epithet", TALK + "seal_name", TALK + "inherit",
                TALK + "inherit_hint", TALK + "option.outfit." + LacertaTalk.OUTFIT));
        for (String t : new String[]{"blank", "blank_use", "owner", "use"}) keys.add("tooltip.logos_dimension.dragon_scale." + t);
        TalkMenu.Option chat = TalkMenu.find(LacertaTalk.MENU, TalkMenu.CHAT);
        assertNotNull(chat);
        for (TalkMenu.Option o : chat.children()) if (o.id().startsWith("chat.")) keys.add(TALK + "option." + o.id());
        for (String s : new String[]{"blade", "thrust", "vortex", "sweep", "skyfall", "parry", "taunt", "flame", "scale", "glide", "tank", "mana",
                "vigil", "withdraw", "flight", "catch", "dragon", "claw", "breath", "ride", "hoard", "warmth"}) {
            for (String part : new String[]{"name", "icon", "desc"}) keys.add(TALK + "skill." + s + "." + part);
        }
        for (LacertaRest.Tier t : LacertaRest.Tier.values()) keys.add(TALK + "skill.vigil." + t.name().toLowerCase(Locale.ROOT));
        for (int n = 0; n < LacertaTalk.MEMORY_LINES.length; n++) {
            keys.add(TALK + "memory." + n + ".title");
            for (int i = 1; i <= LacertaTalk.MEMORY_LINES[n]; i++) keys.add(LINE + "talk.past_" + n + "_" + i);
        }
        for (CompanionBond.Stage st : CompanionBond.Stage.values()) {
            String s = st.name().toLowerCase(Locale.ROOT);
            keys.add(LINE + "talk.us_" + s);
            keys.add(LINE + "talk.greet_" + s + "_1");
            keys.add(LINE + "talk.greet_" + s + "_2");
        }
        for (String l : new String[]{"stay", "follow", "refuse", "full", "stuck", "hungry", "seal", "remake", "withdraw", "taunt", "catch_hand", "catch_embrace",
                "catch_after_hand", "catch_after_embrace", "talk.bye", "dragon_mana_out", "dragon_auto", "dragon_revert", "appear_room", "appear_mana",
                "hoard_claim", "cold_snuggle", "give_food", "mine", "present", "jealous",
                "talk.danger", "talk.rest"}) {
            keys.add(LINE + l);
        }
        for (String l : new String[]{"greet_hurt", "greet_hungry", "food_1", "food_2", "food_3", "slept_1", "slept_2", "pauldron_1", "pauldron_2",
                "pauldron_3", "knight_early_1", "knight_early_2", "knight_close_1", "knight_close_2", "cold_1", "cold_2", "airships_1", "airships_2",
                "praise_early_1", "praise_early_2", "praise_close_1", "praise_close_2", "past_more", "seals_none_1", "seals_none_2", "seals_1",
                "seals_2", "seals_full", "follow", "stay", "pvp_on", "pvp_off", "pvp_off_server", "pouch_back", "scale_have", "scale_given",
                "form_dragon", "form_human", "form_room", "form_mana", "roast_none", "roast_burnt", "roast_done"}) {
            keys.add(LINE + "talk." + l);
        }
        for (String v : new String[]{"none", "crimson", "rival", "joke", "broken", "ranged", "enchanted", "melee"}) keys.add(LINE + "talk.weapon_" + v);
        for (CompanionTactic t : CompanionTactic.values()) keys.add(LINE + "talk.tactic_" + t.id);
        JsonObject zh = read("/assets/logos_dimension/lang/zh_cn.json"), en = read("/assets/logos_dimension/lang/en_us.json");
        for (String k : keys) {
            assertTrue(zh.has(k), "zh_cn 缺 " + k);
            assertTrue(en.has(k), "en_us 缺 " + k);
            // 带参数的那几句，两种语言的参数个数一样
            assertEquals(args(zh.get(k).getAsString()), args(en.get(k).getAsString()), k + " 的参数个数");
        }
    }

    private static int args(String s) {
        return s.replace("%%", "").split("%s", -1).length - 1;
    }
}
