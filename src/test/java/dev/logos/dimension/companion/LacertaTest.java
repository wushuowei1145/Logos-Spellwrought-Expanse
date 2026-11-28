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
        for (String a : LacertaMood.INTERACT) assertTrue(LacertaMood.LENGTH.containsKey(a), a + " 没有长度");
        for (String a : LacertaMood.WING_ACTS) assertTrue(LacertaMood.LENGTH.containsKey(a), a + " 没有长度");
    }

    /** 游戏里会播的每一段动画都在她的模型里：主控制器、翅膀、表情、睡姿、招式文件里每一招的 crimson_*。 */
    @Test void everyClipTheGameUsesIsInTheModel() throws Exception {
        JsonObject anims = animations();
        List<String> used = new ArrayList<>(List.of("idle", "walk", "run", "jump", "sit", "swim_stand", "attacked", "summon", "death", "eat",
                "refuse", "pick", "wings_fold", "wings_folded", "wings_unfold", "pre_parallel0", "pre_parallel1", "pre_parallel2",
                "face_poise", "face_aloof", "face_smug", "face_happy", "face_sweet", "face_pout", "face_flustered", "face_foodie", "face_curious",
                "face_surprised", "face_angry", "face_sad", "face_sleepy", "face_battle", "sleep_together_down", "sleep_together_up"));
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
                "container.logos_dimension.lacerta_pouch", TALK + "name", TALK + "kana", TALK + "epithet", TALK + "seal_name", TALK + "inherit",
                TALK + "inherit_hint", TALK + "option.outfit." + LacertaTalk.OUTFIT));
        for (String t : new String[]{"blank", "blank_use", "owner", "use"}) keys.add("tooltip.logos_dimension.dragon_scale." + t);
        TalkMenu.Option chat = TalkMenu.find(LacertaTalk.MENU, TalkMenu.CHAT);
        assertNotNull(chat);
        for (TalkMenu.Option o : chat.children()) if (o.id().startsWith("chat.")) keys.add(TALK + "option." + o.id());
        for (String s : new String[]{"blade", "thrust", "vortex", "sweep", "skyfall", "parry", "taunt", "flame", "scale", "glide", "tank", "mana",
                "vigil", "withdraw"}) {
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
        for (String l : new String[]{"stay", "follow", "refuse", "full", "stuck", "hungry", "seal", "remake", "withdraw", "taunt", "talk.bye",
                "talk.danger", "talk.rest"}) {
            keys.add(LINE + l);
        }
        for (String l : new String[]{"greet_hurt", "greet_hungry", "food_1", "food_2", "food_3", "slept_1", "slept_2", "pauldron_1", "pauldron_2",
                "pauldron_3", "knight_early_1", "knight_early_2", "knight_close_1", "knight_close_2", "cold_1", "cold_2", "airships_1", "airships_2",
                "praise_early_1", "praise_early_2", "praise_close_1", "praise_close_2", "past_more", "seals_none_1", "seals_none_2", "seals_1",
                "seals_2", "seals_full", "follow", "stay", "pvp_on", "pvp_off", "pvp_off_server", "pouch_back", "scale_have", "scale_given"}) {
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
