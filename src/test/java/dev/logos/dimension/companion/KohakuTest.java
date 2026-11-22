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
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 琥珀同伴（docs/KOHAKU_COMPANION.md）：游戏里用到的动画都在她的模型里、时间点和模型的标记一致；法术强度、生命、魔力、护膜的成长；
 * 护甲不继承；长杖随爪之契换；她的载页编得过；日常的姿势和过渡、给你吃的概率、帮你看载页怎么评、一天的事一天算；
 * 台词、对话、面板的语言键中英文都齐；三位同伴各有自己的契。
 */
class KohakuTest {
    private static final String TALK = "talk.logos_dimension.kohaku.", LINE = "line.logos_dimension.kohaku.";

    private static JsonObject read(String path) throws Exception {
        try (var in = KohakuTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static JsonObject animations() throws Exception {
        return read("/assets/logos_dimension/geckolib/animations/entity/kohaku_companion.animation.json").getAsJsonObject("animations");
    }

    private static long ticks(JsonObject anim) {
        return Math.round(anim.get("animation_length").getAsDouble() * 20);
    }

    /** 时间轴标记 {@code name} 在第几刻。 */
    private static long marker(JsonObject anim, String name) {
        JsonObject timeline = anim.getAsJsonObject("timeline");
        assertNotNull(timeline, "没有时间轴");
        for (var e : timeline.entrySet()) {
            JsonElement v = e.getValue();
            String text = v.isJsonArray() ? v.getAsJsonArray().toString() : v.getAsString();
            if (text.contains(name)) return Math.round(Double.parseDouble(e.getKey()) * 20);
        }
        fail("没有标记 " + name);
        return -1;
    }

    /** 表情和小动作的长度（刻）和模型一致：不然小动作没播完就被情绪盖掉，或者播完了还占着 misc。 */
    @Test void moodLengthsMatchTheModel() throws Exception {
        JsonObject anims = animations();
        for (var e : KohakuMood.LENGTH.entrySet()) {
            assertTrue(anims.has(e.getKey()), "模型里没有 " + e.getKey());
            assertEquals((long) e.getValue(), ticks(anims.getAsJsonObject(e.getKey())), e.getKey());
        }
    }

    /** 游戏里会播的每一段动画都在她的模型里。 */
    @Test void everyClipTheGameUsesIsInTheModel() throws Exception {
        JsonObject anims = animations();
        List<String> used = new ArrayList<>(List.of("idle", "walk", "run", "sleep", "summon", "death", "eat", "refuse",
                "hold_mainhand#logos_dimension:companion_staffs", "cast_staff", "cast_ward", "conjure_sweep", "blink_out", "blink_in",
                "face_lazy", "face_bored", "face_focus", "face_smug", "face_content", "face_happy", "face_guard", "face_flustered",
                "face_surprised", "face_sleepy", "face_sad", "face_curious", "act_cat_stretch", "act_sigh", "act_coin_flip", "act_formula",
                "act_petted", "act_groom", "act_shake", "act_guard", "act_wave", "act_head_tilt", "pre_parallel0", "pre_parallel1", "pre_parallel2",
                "pick", "give", "act_yawn", "face_rain"));
        for (KohakuLife.Pose p : KohakuLife.Pose.values()) {
            for (String n : new String[] {p.down, p.loop, p.up}) if (n != null) used.add(n);
        }
        used.addAll(List.of("wake_linger", "face_sleepy"));
        for (String name : used) assertTrue(anims.has(name), "模型里没有 " + name);
    }

    /** 施法、入场、倒下的时间点和模型的标记一致。 */
    @Test void timingsMatchTheMarkers() throws Exception {
        JsonObject anims = animations();
        assertEquals(KohakuMagic.CAST_AT, marker(anims.getAsJsonObject("cast_staff"), "release"));
        assertEquals(KohakuMagic.CAST_TICKS, ticks(anims.getAsJsonObject("cast_staff")));
        assertEquals(KohakuMagic.WARD_AT, marker(anims.getAsJsonObject("cast_ward"), "ward"));
        assertEquals(KohakuMagic.WARD_TICKS, ticks(anims.getAsJsonObject("cast_ward")));
        JsonObject summon = anims.getAsJsonObject("summon");
        assertEquals(KohakuCompanion.SUMMON_TICKS, ticks(summon));
        assertEquals(KohakuCompanion.SUMMON_LAND, marker(summon, "land"));
        assertEquals(KohakuCompanion.DEATH_TICKS, ticks(anims.getAsJsonObject("death")));
        assertEquals(CompanionEntity.EAT_TICKS, ticks(anims.getAsJsonObject("eat")));
        // 凝辉、猫步（第 7 版）
        JsonObject sweep = anims.getAsJsonObject("conjure_sweep");
        assertEquals(KohakuMoves.SWEEP_TICKS, ticks(sweep));
        assertEquals(KohakuMoves.SWEEP_CONTACT, marker(sweep, "sweep"));
        assertEquals(KohakuMoves.SHATTER_AT, marker(sweep, "shatter"));
        assertEquals(KohakuMoves.HOP_AT, marker(sweep, "hop"));
        assertEquals(KohakuMoves.VANISH_AT, marker(anims.getAsJsonObject("blink_out"), "vanish"));
        assertEquals(KohakuMoves.LAND_TICKS, ticks(anims.getAsJsonObject("blink_in")));
    }

    /**
     * 日常的姿势（第 8 版）：坐下、趴下、撑伞那一段的长度和代码等它播完的刻数一致，循环的那一段真的循环；起身那一段同理。
     * 递东西在 give 标记那一刻交出去。
     */
    @Test void posesMatchTheModel() throws Exception {
        JsonObject anims = animations();
        for (KohakuLife.Pose p : KohakuLife.Pose.values()) {
            if (p == KohakuLife.Pose.NONE) continue;
            if (p.down != null) assertEquals(p.downTicks, ticks(anims.getAsJsonObject(p.down)), p.down);
            assertEquals(p.upTicks, ticks(anims.getAsJsonObject(p.up)), p.up);
            if (p.loop != null) assertTrue(anims.getAsJsonObject(p.loop).get("loop").getAsBoolean(), p.loop + " 要循环");
        }
        // 侧卧躺下以后交给 misc 的 sleep；赖床那一段的长度、撑起来的那一刻
        assertNull(KohakuLife.Pose.CURL.loop);
        JsonObject linger = anims.getAsJsonObject("wake_linger");
        assertEquals(KohakuLife.WAKE_LINGER, ticks(linger));
        assertEquals(KohakuLife.WAKE_RISE, marker(linger, "rise"));
        JsonObject give = anims.getAsJsonObject("give");
        assertEquals(KohakuLife.GIVE_TICKS, ticks(give));
        assertEquals(KohakuLife.GIVE_AT, marker(give, "give"));
    }

    /** 给你吃的（第 8.3 节）：六样加起来正好一百，按概率落到对应的那一样。 */
    @Test void foodTableMatchesTheDoc() {
        assertArrayEquals(new int[] {30, 30, 25, 8, 5, 2}, KohakuLife.FOOD_WEIGHTS);
        int[] count = new int[KohakuLife.FOOD_WEIGHTS.length];
        for (int roll = 0; roll < 100; roll++) count[KohakuLife.foodIndex(roll)]++;
        assertArrayEquals(KohakuLife.FOOD_WEIGHTS, count);
        assertEquals(0, KohakuLife.foodIndex(29));
        assertEquals(1, KohakuLife.foodIndex(30));
        assertEquals(5, KohakuLife.foodIndex(99));
    }

    /** 睡姿（第 9.2 节）：羁绊到「信赖」或第五枚爪之契以后四成露肚皮；猫饼三成；不然侧卧。 */
    @Test void sleepPoseFollowsTheBond() {
        var stranger = CompanionBond.Stage.STRANGER;
        assertEquals(KohakuLife.Pose.CURL, KohakuSleep.pose(stranger, 0, 0, .9f));
        assertEquals(KohakuLife.Pose.LOAF, KohakuSleep.pose(stranger, 0, 0, .1f), "露肚皮要羁绊或契印够了才有");
        assertEquals(KohakuLife.Pose.BELLY, KohakuSleep.pose(CompanionBond.Stage.TRUSTED, 0, .2f, .9f));
        assertEquals(KohakuLife.Pose.BELLY, KohakuSleep.pose(stranger, 5, .2f, .9f));
        assertEquals(KohakuLife.Pose.CURL, KohakuSleep.pose(CompanionBond.Stage.BONDED, 9, .5f, .9f));
    }

    /**
     * 醒来的礼物（第 9.4 节）：七成起、羁绊每段 +5%、最多九成；药水 45、猫币 30、稀有 25（收了零花钱的第二天早上稀有翻倍）；
     * 稀有里的几样加起来一百。琥珀相伴：魔力回复 +50% 起，羁绊每段 +10%。
     */
    @Test void morningGiftAndCompanyAsTheDocSays() {
        assertEquals(.7f, KohakuSleep.giftChance(0), 1e-6);
        assertEquals(.9f, KohakuSleep.giftChance(4), 1e-6);
        assertEquals(.9f, KohakuSleep.giftChance(9), 1e-6);
        assertEquals(100, KohakuSleep.total(false));
        assertEquals(125, KohakuSleep.total(true));
        int[] count = new int[3];
        for (int roll = 0; roll < KohakuSleep.total(true); roll++) count[KohakuSleep.category(roll, true)]++;
        assertArrayEquals(new int[] {45, 30, 50}, count, "零花钱：稀有那一档翻倍");
        assertEquals(100, KohakuSleep.sum(KohakuSleep.RARE_WEIGHTS));
        assertEquals(0, KohakuSleep.pick(KohakuSleep.RARE_WEIGHTS, 39));
        assertEquals(1, KohakuSleep.pick(KohakuSleep.RARE_WEIGHTS, 40));
        assertEquals(5, KohakuSleep.pick(KohakuSleep.RARE_WEIGHTS, 99));
        assertEquals(.5, KohakuCompany.regen(0), 1e-9);
        assertEquals(.9, KohakuCompany.regen(CompanionBond.Stage.BONDED.ordinal()), 1e-9);
        // 拓本只拓你也能用的页：不用「主人」
        for (KohakuSpells s : KohakuSleep.COPIES) assertNotEquals(KohakuSpells.Aim.SELF, s.aim(), s.id());
    }

    /** 一天的事一天算：今天塞了几次吃的，到了第二天从零数；地毯、零花钱各记各的。 */
    @Test void daysCountByTheDay() {
        KohakuDays d = KohakuDays.NONE.withFood(10).withFood(10);
        assertEquals(2, d.foodsOn(10));
        assertEquals(0, d.foodsOn(11));
        assertEquals(1, d.withFood(11).foodsOn(11));
        d = d.withAllowance(10).withCritique(10).withRugNap(9);
        assertEquals(10, d.allowanceDay());
        assertEquals(10, d.critiqueDay());
        assertEquals(9, d.rugNapDay());
        assertEquals(2, d.foodsOn(10), "别的记录不动吃的次数");
    }

    /**
     * 帮你看载页（第 10 节第 6 条）：纸撑不住、你的魔力放不起、注入的不够花、有一段永远走不到（条件写死）、注入的大半用不上、
     * 都还行（打人的另说一句）。
     */
    @Test void critiqueReadsThePage() {
        String bolt = "(spell :name \"弹\" :mana %s :trigger cast (projectile (on-hit (damage target 3 arcane))))";
        var probe = Casting.compile(String.format(bolt, 100));
        double use = probe.profile().estimate();
        assertTrue(use > 2 && use < 60, "一发弹大约花 " + use);
        var fit = Casting.compile(String.format(bolt, (int) Math.ceil(use) + 1));
        assertEquals("good_strike", KohakuTalk.verdict(fit, 1000, 1000));
        assertEquals("unstable", KohakuTalk.verdict(fit, 0, 1000));
        assertEquals("costly", KohakuTalk.verdict(fit, 1000, use / 2));
        assertEquals("short", KohakuTalk.verdict(Casting.compile(String.format(bolt, 1)), 1000, 1000));
        assertEquals("waste", KohakuTalk.verdict(Casting.compile(String.format(bolt, 400)), 1000, 1000));
        var dead = Casting.compile("(spell :name \"死路\" :mana 60 :trigger cast (projectile (on-hit (if true (damage target 3 arcane) (damage target 1 arcane)))))");
        assertTrue(KohakuTalk.dead(dead.body()));
        assertFalse(KohakuTalk.dead(fit.body()));
        assertEquals("dead", KohakuTalk.verdict(dead, 1000, 1000));
    }

    /** 凝辉的招式文件：出手时刻和动作的 sweep 标记一致，伤害、张角同代码里的备用数。 */
    @Test void conjureMovesetMatchesTheClip() throws Exception {
        JsonObject move = read("/data/logos_dimension/combat/movesets/kohaku_companion.json").getAsJsonObject("moves").getAsJsonObject(KohakuMoves.SWEEP);
        assertEquals(KohakuMoves.SWEEP_CONTACT, Math.round(move.get("contact").getAsDouble() * 20));
        assertEquals(KohakuMoves.SWEEP_TICKS, Math.round(move.get("length").getAsDouble() * 20));
        assertEquals(KohakuMoves.SWEEP_DAMAGE, move.get("damage").getAsDouble(), 1e-9);
        // 招式文件要能照常解析（cone 最多 180°，这一斩的 220° 写在代码里）
        assertNotNull(dev.logos.dimension.combat.Moveset.parse(read("/data/logos_dimension/combat/movesets/kohaku_companion.json")).move(KohakuMoves.SWEEP));
    }

    /** 凝辉的网格：切片合并以后一百来块（美术稿 210 块），贴图坐标都在贴图里，原点在剑柄上。 */
    @Test void vesperglassMeshIsSlimAndInBounds() throws Exception {
        JsonObject mesh = read("/assets/logos_dimension/combat/weapons/vesperglass_greatsword.json");
        int w = mesh.getAsJsonArray("texture_size").get(0).getAsInt(), h = mesh.getAsJsonArray("texture_size").get(1).getAsInt();
        var cubes = mesh.getAsJsonArray("cubes");
        assertTrue(cubes.size() <= 120, "还有 " + cubes.size() + " 块");
        double minY = 1e9, maxY = -1e9;
        for (var e : cubes) {
            JsonObject cube = e.getAsJsonObject();
            minY = Math.min(minY, cube.getAsJsonArray("start").get(1).getAsDouble());
            maxY = Math.max(maxY, cube.getAsJsonArray("end").get(1).getAsDouble());
            for (var f : cube.getAsJsonObject("uv").entrySet()) {
                var uv = f.getValue().getAsJsonArray();
                for (int i = 0; i < 4; i++) {
                    double v = uv.get(i).getAsDouble();
                    assertTrue(v >= -1e-6 && v <= (i % 2 == 0 ? w : h) + 1e-6, cube.get("name").getAsString() + " " + f.getKey());
                }
            }
        }
        assertTrue(minY < 0 && maxY > 20, "原点在剑柄上，剑尖朝 +Y");
    }

    /**
     * 第 11 节的数：法强 0.9 起、每枚 +0.08、羁绊每段 +0.06，再继承（三成起、每枚 +8%）；主人法强 1、没盖契、生疏时 1.2，
     * 九枚、羁绊满、主人法强 2 时 3.86。生命 24 起，只继承两成（每枚 +4%，最多 56%）；护甲不继承。
     */
    @Test void statsGrowAsTheDocSays() {
        CompanionDef.Growth g = KohakuCompanion.GROWTH;
        int bonded = CompanionBond.Stage.BONDED.ordinal();
        assertEquals(1.2, CompanionStats.power(g, 1, 0, 0).total(), 1e-9);
        assertEquals(3.86, CompanionStats.power(g, 2, 9, bonded).total(), 1e-9);
        CompanionStats.Owner owner = new CompanionStats.Owner(20, 20, 8, 1, 1);
        CompanionStats.Parts start = CompanionStats.parts(g, owner, 0, 0);
        assertEquals(24 + 4, start.health().total(), 1e-9, "她自己 24，继承主人 20 的两成");
        assertEquals(0, start.armor().total(), 1e-9, "护甲不继承");
        assertEquals(0, start.toughness().total(), 1e-9, "韧性不继承");
        CompanionStats.Parts full = CompanionStats.parts(g, owner, 9, bonded);
        assertEquals(24 + 18 + 8 + 20 * .56, full.health().total(), 1e-9, "九枚：生命继承最多 56%");
        assertEquals(0, full.armor().total(), 1e-9);
        assertEquals(.56, g.healthRatio(20), 1e-9);
        assertEquals(1, g.ratio(9), 1e-9);
        // 白梅、精灵公主不受影响：生命和别的数同一个比例，护甲照样继承
        CompanionStats.Parts shiraume = CompanionStats.parts(CompanionStats.SHIRAUME, owner, 0, 0);
        assertEquals(.3 * 20, shiraume.armor().inherited(), 1e-9);
        assertEquals(.3 * 20, shiraume.health().inherited(), 1e-9);
    }

    /** 魔力上限 400 起、每枚 +100、羁绊每段 +50，继承主人的长期上限（比例同法强）；回复 4 起、每枚 +0.75、羁绊每段 +0.25。 */
    @Test void manaGrowsAsTheDocSays() {
        assertEquals(400 + .3 * 200, KohakuCompanion.manaParts(200, 0, 0).total(), 1e-9);
        CompanionStats.Part full = KohakuCompanion.manaParts(1000, 9, CompanionBond.Stage.BONDED.ordinal());
        assertEquals(1300, full.own(), 1e-9);
        assertEquals(200, full.bond(), 1e-9);
        assertEquals(1000, full.inherited(), 1e-9);
        assertEquals(4, KohakuCompanion.regen(0, 0), 1e-9);
        assertEquals(4 + 6.75 + 1, KohakuCompanion.regen(9, CompanionBond.Stage.BONDED.ordinal()), 1e-9);
    }

    /** 琥珀护膜：一倍生命上限起，每枚爪之契 +0.25，第九枚 3.25 倍。 */
    @Test void wardGrowsWithSeals() {
        assertEquals(1, KohakuMagic.wardMultiplier(0), 1e-9);
        assertEquals(3.25, KohakuMagic.wardMultiplier(9), 1e-9);
        assertEquals(3.25, KohakuMagic.wardMultiplier(20), 1e-9);
    }

    /** 她自己的长杖：紫晶长杖起（维护者定的），契印多了换更好的，到贤者为止。 */
    @Test void herOwnStaffFollowsTheSeals() {
        assertEquals(10, KohakuStaff.OWN.size());
        assertEquals("crystal_staff", KohakuStaff.OWN.get(0).getId().getPath());
        assertEquals("echo_staff", KohakuStaff.OWN.get(1).getId().getPath());
        assertEquals("transmuted_staff", KohakuStaff.OWN.get(3).getId().getPath());
        assertEquals("chalk_staff", KohakuStaff.OWN.get(5).getId().getPath());
        assertEquals("firmament_staff", KohakuStaff.OWN.get(7).getId().getPath());
        assertEquals("sage_staff", KohakuStaff.OWN.get(9).getId().getPath());
    }

    /** 她的载页（攻击玩家开着、关着两种）都编得过；注入量不超过解锁时她手里那根杖的同档容量。 */
    @Test void herPagesCompile() throws Exception {
        int[] capacity = {150, 400, 400, 800, 800, 1600, 1600, 3200, 3200, 6400};
        for (KohakuSpells s : KohakuSpells.ALL) {
            String source;
            try (var in = KohakuTest.class.getResourceAsStream("/data/logos_dimension/companion/spells/kohaku/" + s.id() + ".logos")) {
                assertNotNull(in, s.id());
                source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            var spell = Casting.compile(source);
            assertNotNull(spell);
            assertNotNull(Casting.compile(source.replace(":trigger cast", ":trigger cast :players true")));
            assertTrue(spell.mana() <= capacity[s.seal()], s.id() + " 注入 " + spell.mana() + " 超过第 " + s.seal() + " 枚时长杖的容量");
            assertEquals(s.intent(), spell.profile().primary(), s.id() + " 的意图");
        }
    }

    /** 八种意图她都有自己的页（维护者定的）；本来就会的之外，第一到第九印每枚都有一页新的。 */
    @Test void herPagesCoverTheIntentsAndSeals() {
        java.util.Set<dev.logos.lang.Intent> intents = java.util.EnumSet.noneOf(dev.logos.lang.Intent.class);
        for (KohakuSpells s : KohakuSpells.ALL) intents.add(s.intent());
        for (var i : dev.logos.lang.Intent.values()) assertTrue(intents.contains(i), "没有 " + i + " 的页");
        for (int seal = 0; seal <= 9; seal++) {
            int n = seal;
            assertTrue(KohakuSpells.ALL.stream().anyMatch(s -> s.seal() == n), "第 " + seal + " 枚没有新的页");
        }
    }

    /** 你给的页：召唤、附身这类非玩家用不了的符文的页她不放；按意图决定怎么放，位移、杂务她不自己放。 */
    @Test void yourPagesAreCheckedAndAimedByIntent() {
        assertTrue(KohakuSpells.playerOnly(Casting.compile("(spell :name \"附身\" :mana 50 :trigger cast (possess (foe) 5))")));
        assertTrue(KohakuSpells.playerOnly(Casting.compile("(spell :name \"藏在里面\" :mana 50 :trigger cast (projectile (on-hit (possess target 5))))")));
        assertFalse(KohakuSpells.playerOnly(Casting.compile("(spell :name \"弹\" :mana 50 :trigger cast (projectile (on-hit (damage target 3 arcane))))")));
        assertEquals(KohakuSpells.Aim.FOE, KohakuSpells.aimFor(dev.logos.lang.Intent.STRIKE));
        assertEquals(KohakuSpells.Aim.CLUSTER, KohakuSpells.aimFor(dev.logos.lang.Intent.AREA));
        assertEquals(KohakuSpells.Aim.ALLY, KohakuSpells.aimFor(dev.logos.lang.Intent.HEAL));
        assertNull(KohakuSpells.aimFor(dev.logos.lang.Intent.MOVE));
        assertNull(KohakuSpells.aimFor(dev.logos.lang.Intent.UTILITY));
        // 支援页按作用对象：群体的落在那位脚下，单体的对着那位放，只作用自己的给她自己，写给自己的强化她不放
        var self = java.util.Set.of(dev.logos.lang.Reach.SELF);
        assertEquals(KohakuSpells.Aim.AT, KohakuSpells.aimFor(dev.logos.lang.Intent.WARD, java.util.Set.of(dev.logos.lang.Reach.ONE)));
        assertEquals(KohakuSpells.Aim.SELF, KohakuSpells.aimFor(dev.logos.lang.Intent.HEAL, self));
        assertNull(KohakuSpells.aimFor(dev.logos.lang.Intent.EMPOWER, self));
    }

    /** 台词、对话、面板用到的语言键，中英文都有。 */
    @Test void everyLineAndPanelKeyIsInBothLanguages() throws Exception {
        List<String> keys = new ArrayList<>(List.of("entity.logos_dimension.kohaku_companion", "item.logos_dimension.paw_sovereign",
                "container.logos_dimension.kohaku_pouch", "gui.logos_dimension.companion.stat.spell_power", "gui.logos_dimension.companion.stat.mana",
                TALK + "name", TALK + "kana", TALK + "epithet", TALK + "seal_name", TALK + "option.outfit." + KohakuTalk.OUTFIT,
                TALK + "skill.staff.yours", TALK + "skill.staff.own", "effect.logos_dimension.kohaku_company"));
        for (String t : new String[]{"blank", "blank_use", "owner", "use"}) keys.add("tooltip.logos_dimension.paw_sovereign." + t);
        TalkMenu.Option chat = TalkMenu.find(KohakuTalk.MENU, TalkMenu.CHAT);
        assertNotNull(chat);
        for (TalkMenu.Option o : chat.children()) if (o.id().startsWith("chat.")) keys.add(TALK + "option." + o.id());
        for (String s : new String[]{"one_drop", "ward", "staff", "mana", "genius", "withdraw", "cat_eye", "boiling_flask", "nap_ward",
                "greenhouse", "quick_enchant", "time_amber", "misty_umbrella", "starbloom", "your_pages", "conjure", "cat_step", "two_step", "company"}) {
            for (String part : new String[]{"name", "icon", "desc"}) keys.add(TALK + "skill." + s + "." + part);
        }
        for (int n = 0; n < KohakuTalk.MEMORY_LINES.length; n++) {
            keys.add(TALK + "memory." + n + ".title");
            for (int i = 1; i <= KohakuTalk.MEMORY_LINES[n]; i++) keys.add(LINE + "talk.past_" + n + "_" + i);
        }
        for (CompanionBond.Stage st : CompanionBond.Stage.values()) {
            String s = st.name().toLowerCase(Locale.ROOT);
            keys.add(LINE + "talk.us_" + s);
            keys.add(LINE + "talk.greet_" + s + "_1");
            keys.add(LINE + "talk.greet_" + s + "_2");
        }
        for (String l : new String[]{"stay", "follow", "refuse", "full", "stuck", "hungry", "seal", "remake", "two_step", "talk.bye", "talk.danger",
                "talk.rest", "rug_claim", "sunned", "give_food", "fetch", "morning_gift"}) {
            keys.add(LINE + l);
        }
        for (String l : new String[]{"greet_hurt", "greet_hungry", "greet_night", "food_1", "food_2", "food_hungry", "alchemy_1", "alchemy_2",
                "alchemy_3", "lazy_1", "lazy_2", "city_1", "city_2", "city_3", "money_1", "money_2", "enforcers_1", "enforcers_2", "past_more",
                "seals_none_1", "seals_none_2", "seals_1", "seals_2", "seals_full", "follow", "stay", "pvp_on", "pvp_off", "pvp_off_server",
                "coin_have", "coin_need", "coin_given", "pouch_back", "allowance_given", "allowance_change", "allowance_today", "allowance_none",
                "critique_none", "critique_blank", "critique_broken", "critique_unstable", "critique_costly", "critique_short", "critique_dead",
                "critique_waste", "critique_recursive", "critique_good_strike", "critique_good"}) keys.add(LINE + "talk." + l);
        for (CompanionTactic t : CompanionTactic.values()) keys.add(LINE + "talk.tactic_" + t.id);
        for (String k : new String[]{"title", "call", "dismiss", "resting", "unknown", "with_you"}) keys.add("gui.logos_dimension.companion_book." + k);
        keys.add("key.logos_dimension.companion_book");
        keys.add("key.category.logos_dimension.companions");
        for (String locale : new String[]{"zh_cn", "en_us"}) {
            JsonObject lang = read("/assets/logos_dimension/lang/" + locale + ".json");
            for (String k : keys) assertTrue(lang.has(k), locale + " 缺 " + k);
        }
    }

    /** 友人帐的每一页在网络上来回不走样。 */
    @Test void bookPagesSurviveTheNetwork() {
        var state = new CompanionBook.State(List.of(new CompanionBook.Page("shiraume", false, false, 0, "", 0, ""),
                new CompanionBook.Page("kohaku", true, true, 42, "cardigan", 5, "TRUSTED")), CompanionBook.OPEN);
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        CompanionBook.State.CODEC.encode(buf, state);
        CompanionBook.State back = CompanionBook.State.CODEC.decode(buf);
        assertEquals(state, back);
        assertTrue(back.opens() && !back.done());
    }

    /** 三位同伴各有自己的契和包袱。 */
    @Test void eachCompanionHasHerOwnBond() {
        assertEquals("kohaku", Companions.KOHAKU_DEF.id());
        assertNotSame(Companions.KOHAKU_DEF.bond(), Companions.PRINCESS_DEF.bond());
        assertNotSame(Companions.KOHAKU_DEF.bond(), Companions.SHIRAUME_DEF.bond());
        assertNotSame(Companions.KOHAKU_DEF.pouch(), Companions.PRINCESS_DEF.pouch());
        assertEquals(Companions.KOHAKU_DEF, Companions.def("kohaku"));
        assertEquals(3, Companions.ALL.size());
    }
}
