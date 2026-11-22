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
 * 护甲不继承；长杖随爪之契换；她的载页编得过；台词、对话、面板的语言键中英文都齐；三位同伴各有自己的契。
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
                "hold_mainhand#logos_dimension:companion_staffs", "cast_staff", "cast_ward",
                "face_lazy", "face_bored", "face_focus", "face_smug", "face_content", "face_happy", "face_guard", "face_flustered",
                "face_surprised", "face_sleepy", "face_sad", "face_curious", "act_cat_stretch", "act_sigh", "act_coin_flip", "act_formula",
                "act_petted", "act_groom", "act_shake", "act_guard", "act_wave", "act_head_tilt", "pre_parallel0", "pre_parallel1", "pre_parallel2"));
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

    /** 台词、对话、面板用到的语言键，中英文都有。 */
    @Test void everyLineAndPanelKeyIsInBothLanguages() throws Exception {
        List<String> keys = new ArrayList<>(List.of("entity.logos_dimension.kohaku_companion", "item.logos_dimension.paw_sovereign",
                "container.logos_dimension.kohaku_pouch", "gui.logos_dimension.companion.stat.spell_power", "gui.logos_dimension.companion.stat.mana",
                TALK + "name", TALK + "kana", TALK + "epithet", TALK + "seal_name", TALK + "option.outfit." + KohakuTalk.OUTFIT,
                TALK + "skill.staff.yours", TALK + "skill.staff.own"));
        for (String t : new String[]{"blank", "blank_use", "owner", "use"}) keys.add("tooltip.logos_dimension.paw_sovereign." + t);
        TalkMenu.Option chat = TalkMenu.find(KohakuTalk.MENU, TalkMenu.CHAT);
        assertNotNull(chat);
        for (TalkMenu.Option o : chat.children()) if (o.id().startsWith("chat.")) keys.add(TALK + "option." + o.id());
        for (String s : new String[]{"one_drop", "ward", "staff", "mana", "genius", "withdraw", "sealed"}) {
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
        for (String l : new String[]{"stay", "follow", "refuse", "full", "stuck", "hungry", "seal", "remake", "talk.bye", "talk.danger", "talk.rest"}) {
            keys.add(LINE + l);
        }
        for (String l : new String[]{"greet_hurt", "greet_hungry", "greet_night", "food_1", "food_2", "food_hungry", "alchemy_1", "alchemy_2",
                "alchemy_3", "lazy_1", "lazy_2", "city_1", "city_2", "city_3", "money_1", "money_2", "enforcers_1", "enforcers_2", "past_more",
                "seals_none_1", "seals_none_2", "seals_1", "seals_2", "seals_full", "follow", "stay", "pvp_on", "pvp_off", "pvp_off_server",
                "coin_have", "coin_need", "coin_given", "pouch_back"}) keys.add(LINE + "talk." + l);
        for (CompanionTactic t : CompanionTactic.values()) keys.add(LINE + "talk.tactic_" + t.id);
        for (String locale : new String[]{"zh_cn", "en_us"}) {
            JsonObject lang = read("/assets/logos_dimension/lang/" + locale + ".json");
            for (String k : keys) assertTrue(lang.has(k), locale + " 缺 " + k);
        }
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
