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
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 精灵公主同伴（docs/ELF_PRINCESS_COMPANION.md）：游戏里用到的动画都在她的模型里、时间点和模型的标记一致；法术强度的成长；
 * 台词、对话、面板的语言键中英文都齐；荆棘缠绕的载页编得过。
 */
class PrincessTest {
    private static final String TALK = "talk.logos_dimension.elf_princess.", LINE = "line.logos_dimension.elf_princess.";

    private static JsonObject read(String path) throws Exception {
        try (var in = PrincessTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static JsonObject animations() throws Exception {
        return read("/assets/logos_dimension/geckolib/animations/entity/elf_princess_companion.animation.json").getAsJsonObject("animations");
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
        for (var e : PrincessMood.LENGTH.entrySet()) {
            assertTrue(anims.has(e.getKey()), "模型里没有 " + e.getKey());
            assertEquals((long) e.getValue(), ticks(anims.getAsJsonObject(e.getKey())), e.getKey());
        }
    }

    /** 游戏里会播的每一段动画都在她的模型里。 */
    @Test void everyClipTheGameUsesIsInTheModel() throws Exception {
        JsonObject anims = animations();
        List<String> used = new ArrayList<>(List.of("idle", "walk", "run", "sleep", "summon", "withdraw", "eat", "refuse",
                "hold_mainhand:bow", "use_mainhand:bow", "bow_fan", "backstep", "cast_heal", "cast_bless", "cast_thorns",
                "face_happy", "face_content", "face_determined", "face_worried", "face_daze", "face_flustered", "face_surprised", "face_pout",
                "face_sleepy", "face_sad", "act_curtsy", "act_sheepish", "act_pray", "act_resolve", "act_head_tilt", "act_listen", "act_wave",
                "act_petted", "act_late_startle", "pre_parallel0", "pre_parallel1", "pre_parallel2"));
        for (String name : used) assertTrue(anims.has(name), "模型里没有 " + name);
    }

    /** 施法、撤退、入场、后跳的时间点和模型的标记一致。 */
    @Test void timingsMatchTheMarkers() throws Exception {
        JsonObject anims = animations();
        assertEquals(PrincessMagic.HEAL_AT, marker(anims.getAsJsonObject("cast_heal"), "heal"));
        assertEquals(PrincessMagic.HEAL_TICKS, ticks(anims.getAsJsonObject("cast_heal")));
        assertEquals(PrincessMagic.BLESS_AT, marker(anims.getAsJsonObject("cast_bless"), "bless"));
        assertEquals(PrincessMagic.BLESS_TICKS, ticks(anims.getAsJsonObject("cast_bless")));
        assertEquals(PrincessMagic.THORN_AT, marker(anims.getAsJsonObject("cast_thorns"), "root"));
        assertEquals(PrincessMagic.THORN_TICKS, ticks(anims.getAsJsonObject("cast_thorns")));
        JsonObject withdraw = anims.getAsJsonObject("withdraw");
        assertEquals(ElfPrincessCompanion.WITHDRAW_CLOSED, marker(withdraw, "closed"));
        assertEquals(ElfPrincessCompanion.WITHDRAW_SINK, marker(withdraw, "sink"));
        assertTrue(ElfPrincessCompanion.WITHDRAW_TICKS >= marker(withdraw, "gone"), "花苞沉下去以后才回到树里");
        JsonObject summon = anims.getAsJsonObject("summon");
        assertEquals(ElfPrincessCompanion.SUMMON_TICKS, ticks(summon));
        assertEquals(ElfPrincessCompanion.SUMMON_BLOOM, marker(summon, "bloom"));
        assertEquals(ElfPrincessCompanion.SUMMON_SCATTER, marker(summon, "scatter"));
        assertEquals(PrincessBow.BACKSTEP_TICKS, ticks(anims.getAsJsonObject("backstep")));
        assertEquals(CompanionEntity.EAT_TICKS, ticks(anims.getAsJsonObject("eat")));
    }

    /**
     * 法术强度：她自己 0.7 起、每枚契印 +0.05（到第九枚），羁绊每段 +0.05，再加继承（三成起、每枚 +8%）。
     * 主人法强 1、没盖契印、生疏时正好 1.0；九枚契印、羁绊满、主人法强 2 时 3.35。
     */
    @Test void spellPowerGrowsWithSealsBondAndOwner() {
        CompanionDef.Growth g = ElfPrincessCompanion.GROWTH;
        assertEquals(1.0, CompanionStats.power(g, 1, 0, 0).total(), 1e-9);
        CompanionStats.Part full = CompanionStats.power(g, 2, 9, CompanionBond.Stage.BONDED.ordinal());
        assertEquals(.7 + .45, full.own(), 1e-9);
        assertEquals(.2, full.bond(), 1e-9);
        assertEquals(2, full.inherited(), 1e-9);
        assertEquals(3.35, full.total(), 1e-9);
        assertTrue(CompanionStats.power(g, 1, 3, 0).total() > CompanionStats.power(g, 1, 2, 0).total(), "契印越多越强");
        assertTrue(CompanionStats.power(g, 1.5, 0, 0).total() > CompanionStats.power(g, 1, 0, 0).total(), "主人越强她越强");
        CompanionStats.Parts parts = CompanionStats.parts(g, new CompanionStats.Owner(20, 0, 0, 1, 1), 0, 0);
        assertEquals(32 + 6, parts.health().total(), 1e-9, "她自己 32，继承主人 20 的三成");
    }

    /** 台词、对话、面板用到的语言键，中英文都有。 */
    @Test void everyLineAndPanelKeyIsInBothLanguages() throws Exception {
        List<String> keys = new ArrayList<>(List.of("entity.logos_dimension.elf_princess_companion", "item.logos_dimension.bagged_world_tree",
                "container.logos_dimension.elf_princess_pouch", "gui.logos_dimension.companion.stat.spell_power",
                TALK + "name", TALK + "kana", TALK + "epithet", TALK + "seal_name", TALK + "option.outfit." + PrincessTalk.OUTFIT));
        for (String t : new String[]{"blank", "blank_use", "owner", "use"}) keys.add("tooltip.logos_dimension.bagged_world_tree." + t);
        TalkMenu.Option chat = TalkMenu.find(PrincessTalk.MENU, TalkMenu.CHAT);
        assertNotNull(chat);
        for (TalkMenu.Option o : chat.children()) if (o.id().startsWith("chat.")) keys.add(TALK + "option." + o.id());
        for (String s : new String[]{"bow", "fan", "mend", "evergreen", "bless", "shield", "thorns", "chance", "withdraw", "lifeforce", "sealed"}) {
            for (String part : new String[]{"name", "icon", "desc"}) keys.add(TALK + "skill." + s + "." + part);
        }
        for (int n = 0; n < PrincessTalk.MEMORY_LINES.length; n++) {
            keys.add(TALK + "memory." + n + ".title");
            for (int i = 1; i <= PrincessTalk.MEMORY_LINES[n]; i++) keys.add(LINE + "talk.past_" + n + "_" + i);
        }
        for (CompanionBond.Stage st : CompanionBond.Stage.values()) {
            String s = st.name().toLowerCase(Locale.ROOT);
            keys.add(LINE + "talk.us_" + s);
            keys.add(LINE + "talk.greet_" + s + "_1");
            keys.add(LINE + "talk.greet_" + s + "_2");
        }
        for (String l : new String[]{"stay", "follow", "refuse", "full", "stuck", "hungry", "seal", "remake", "chance", "bless", "thorns",
                "late_startle", "talk.bye", "talk.danger", "talk.rest"}) keys.add(LINE + l);
        for (String l : new String[]{"greet_hurt", "greet_hungry", "greet_night", "food_1", "food_2", "food_hungry", "forest_1", "forest_2", "forest_3",
                "mission_1", "mission_2", "mission_3", "past_more", "leaves_none_1", "leaves_none_2", "leaves_1", "leaves_2", "leaves_full", "follow",
                "stay", "pvp_on", "pvp_off", "pvp_off_server", "bag_have", "bag_sapling", "bag_given", "pouch_back"}) keys.add(LINE + "talk." + l);
        for (CompanionTactic t : CompanionTactic.values()) keys.add(LINE + "talk.tactic_" + t.id);
        for (String locale : new String[]{"zh_cn", "en_us"}) {
            JsonObject lang = read("/assets/logos_dimension/lang/" + locale + ".json");
            for (String k : keys) assertTrue(lang.has(k), locale + " 缺 " + k);
        }
    }

    /** 荆棘缠绕的载页（攻击玩家开着、关着两种）编得过。 */
    @Test void theThornSnarePageCompiles() throws Exception {
        String source;
        try (var in = PrincessTest.class.getResourceAsStream("/data/logos_dimension/companion/spells/thorn_snare.logos")) {
            assertNotNull(in);
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertNotNull(Casting.compile(source));
        assertNotNull(Casting.compile(source.replace(":trigger cast", ":trigger cast :players true")));
    }

    /** 两位同伴各有自己的契和包袱：白梅的附件 ID 还是第一版的 shiraume_bond，旧存档读得出来。 */
    @Test void eachCompanionHasHerOwnBond() {
        assertEquals("shiraume", Companions.SHIRAUME_DEF.id());
        assertEquals("elf_princess", Companions.PRINCESS_DEF.id());
        assertNotSame(Companions.SHIRAUME_DEF.bond(), Companions.PRINCESS_DEF.bond());
        assertEquals(Companions.SHIRAUME_DEF, Companions.def("shiraume"));
        assertEquals(Companions.PRINCESS_DEF, Companions.def("elf_princess"));
        assertEquals(Map.of(), Map.of());
    }
}
