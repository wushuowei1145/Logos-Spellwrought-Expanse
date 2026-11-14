package dev.logos.dimension.companion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 和同伴说话、角色面板（docs/SHIRAUME_COMPANION.md 第 8.13 节）的纯数据：行动策略存进契里（旧存档读出来是随机应变）、
 * 通用的选项树、档案在网络上来回不走样、白梅的每一句台词、每个选项、每项能力和往事在中英文里都有。
 */
class CompanionTalkTest {
    private static JsonObject lang(String locale) throws Exception {
        try (var in = CompanionTalkTest.class.getResourceAsStream("/assets/logos_dimension/lang/" + locale + ".json")) {
            assertNotNull(in, locale);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void tacticIsSavedInTheBondAndOldSavesAdapt() {
        JsonObject old = new JsonObject();
        old.addProperty("pact", true);
        old.addProperty("seals", 2);
        Companions.Bond read = Companions.Bond.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow();
        assertEquals(CompanionTactic.ADAPTIVE, read.tacticOf(), "旧存档没有这一项：随机应变");
        Companions.Bond ranged = read.withTactic(CompanionTactic.RANGED);
        JsonElement saved = Companions.Bond.CODEC.encodeStart(JsonOps.INSTANCE, ranged).getOrThrow();
        Companions.Bond back = Companions.Bond.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(CompanionTactic.RANGED, back.tacticOf());
        assertEquals(2, back.seals());
        // 别的改动不会把策略弄丢
        assertEquals(CompanionTactic.RANGED, back.withSeals(5).withAffection(10).withSatiety(3).tacticOf());
        assertEquals(CompanionTactic.ADAPTIVE, CompanionTactic.byId("nonsense"));
    }

    @Test void standardMenuHasTheCommonEntries() {
        List<TalkMenu.Option> root = TalkMenu.standard(List.of(TalkMenu.Option.server("chat.x")), true);
        assertEquals(List.of("chat", "pouch", "tactic", "stats", "bye"), root.stream().map(TalkMenu.Option::id).toList());
        assertEquals(List.of("chat", "tactic", "stats", "bye"), TalkMenu.standard(List.of(), false).stream().map(TalkMenu.Option::id).toList(),
                "没有包袱的同伴不出那一项");
        assertEquals(TalkMenu.Kind.BACK, root.get(0).children().get(root.get(0).children().size() - 1).kind(), "聊天最后一项是返回");
        TalkMenu.Option plan = TalkMenu.find(root, TalkMenu.PLAN);
        assertNotNull(plan);
        for (CompanionTactic t : CompanionTactic.values()) assertNotNull(TalkMenu.find(plan.children(), "tactic." + t.id));
        assertEquals(TalkMenu.Kind.PANEL, TalkMenu.find(root, TalkMenu.STATS).kind());
        assertEquals(TalkMenu.Kind.CLOSE, TalkMenu.find(root, TalkMenu.BYE).kind());
    }

    @Test void currentOptionsFollowTheSheet() {
        CompanionSheet sheet = new CompanionSheet(1, "shiraume", "umeakari", List.of(), List.of(), 0, 9, "STRANGER", 0, 20, 20, true, "melee", 1, 10, .3f);
        assertTrue(TalkMenu.current(TalkMenu.Option.server("tactic.melee", "tactic=melee"), sheet));
        assertFalse(TalkMenu.current(TalkMenu.Option.server("tactic.ranged", "tactic=ranged"), sheet));
        assertTrue(TalkMenu.current(TalkMenu.Option.server(TalkMenu.STAY, "staying=true"), sheet));
        assertFalse(TalkMenu.current(TalkMenu.Option.server(TalkMenu.FOLLOW, "staying=false"), sheet));
        assertTrue(TalkMenu.current(TalkMenu.Option.server("outfit.umeakari", "outfit=umeakari"), sheet));
        assertFalse(TalkMenu.current(TalkMenu.Option.server("chat.food"), sheet));
    }

    @Test void sheetSurvivesTheNetwork() {
        CompanionSheet sheet = new CompanionSheet(42, "shiraume", "miko",
                List.of(new CompanionSheet.Stat("health", 46, 40, 30.5f), new CompanionSheet.Stat("attack", 5.8f, 4, -1)),
                List.of(new CompanionSheet.Skill("foxfire", CompanionSheet.NORMAL, 0, true, List.of("5.8")),
                        new CompanionSheet.Skill("katashiro", CompanionSheet.SPECIAL, 1, false, List.of("#gui.logos_dimension.companion.locked"))),
                3, 9, "CLOSE", .25f, 12, 20, false, "ranged", 4, 10, .45f);
        ByteBuf buf = Unpooled.buffer();
        CompanionSheet.CODEC.encode(buf, sheet);
        assertEquals(sheet, CompanionSheet.CODEC.decode(buf));
        CompanionTalk.Update u = new CompanionTalk.Update(sheet, List.of(CompanionTalk.Line.of("line.x", 3, "a")), CompanionTalk.OPEN);
        ByteBuf b2 = Unpooled.buffer();
        CompanionTalk.Update.CODEC.encode(b2, u);
        CompanionTalk.Update back = CompanionTalk.Update.CODEC.decode(b2);
        assertEquals(u, back);
        assertTrue(back.opens());
        assertFalse(back.ends());
        assertEquals(0, sheet.stat("missing").value(), 1e-6, "没有的那一项是 0");
    }

    @Test void memoriesComeBackOnePerSeal() {
        assertEquals(1, ShiraumeTalk.memories(0), "第 0 段本来就记得");
        assertEquals(4, ShiraumeTalk.memories(3));
        assertEquals(ShiraumeTalk.MEMORY_LINES.length, ShiraumeTalk.memories(ShiraumeTalk.MAX_SEALS), "九枚契印：全想起来");
        assertEquals(ShiraumeTalk.MEMORY_LINES.length, ShiraumeTalk.memories(20));
        assertEquals(ShiraumeTalk.MAX_SEALS + 1, ShiraumeTalk.MEMORY_LINES.length, "九尾九段，加上本来记得的一段");
        assertEquals("5.8", ShiraumeTalk.num(5.8000001));
        assertEquals("13", ShiraumeTalk.num(13.04));
        assertEquals("30", ShiraumeTalk.num(30));
    }

    /** 白梅会说的每一句：招呼（每段羁绊两种）、聊天、往事、尾之契、羁绊、装束、策略、道别。 */
    static List<String> lineKeys() {
        List<String> ids = new ArrayList<>();
        for (CompanionBond.Stage st : CompanionBond.Stage.values()) {
            String s = st.name().toLowerCase(Locale.ROOT);
            ids.add("greet_" + s + "_1");
            ids.add("greet_" + s + "_2");
            ids.add("us_" + s);
        }
        ids.addAll(List.of("greet_hurt", "greet_hungry", "greet_night", "katashiro_have", "katashiro_paper", "katashiro_given", "food_1", "food_2",
                "food_hungry", "past_more", "tails_none_1", "tails_none_2", "tails_1", "tails_2", "tails_full", "rest", "follow", "stay", "bye",
                "danger", "pouch_back"));
        for (int n = 0; n < ShiraumeTalk.MEMORY_LINES.length; n++) for (int i = 1; i <= ShiraumeTalk.MEMORY_LINES[n]; i++) ids.add("past_" + n + "_" + i);
        for (CompanionTactic t : CompanionTactic.values()) ids.add("tactic_" + t.id);
        for (CompanionOutfit o : CompanionOutfit.values()) ids.add("outfit_" + o.id);
        List<String> keys = new ArrayList<>();
        for (String id : ids) keys.add("line.logos_dimension.shiraume.talk." + id);
        keys.add("line.logos_dimension.shiraume.refold");
        return keys;
    }

    private static void options(List<TalkMenu.Option> menu, List<String> out) {
        for (TalkMenu.Option o : menu) {
            out.add(o.id());
            options(o.children(), out);
        }
    }

    @Test void everythingSheSaysIsTranslated() throws Exception {
        for (String locale : List.of("zh_cn", "en_us")) {
            JsonObject l = lang(locale);
            for (String key : lineKeys()) assertTrue(l.has(key), locale + " 缺台词 " + key);
            // 选项：她自己的，或者通用的
            List<String> ids = new ArrayList<>();
            options(ShiraumeTalk.MENU, ids);
            for (String id : ids) {
                assertTrue(l.has("talk.logos_dimension.shiraume.option." + id) || l.has("talk.logos_dimension.option." + id), locale + " 缺选项 " + id);
            }
            for (String id : List.of("foxfire", "ryuka", "execute", "kindle", "katashiro", "lantern", "intercept", "parry", "sealed")) {
                for (String part : List.of("name", "icon", "desc")) {
                    assertTrue(l.has("talk.logos_dimension.shiraume.skill." + id + "." + part), locale + " 缺能力 " + id + "." + part);
                }
            }
            for (int n = 0; n < ShiraumeTalk.MEMORY_LINES.length; n++) assertTrue(l.has("talk.logos_dimension.shiraume.memory." + n + ".title"), locale + " 缺往事 " + n);
            for (String k : List.of("name", "epithet", "kana", "seal_name")) assertTrue(l.has("talk.logos_dimension.shiraume." + k), locale + " 缺 " + k);
            for (CompanionBond.Stage st : CompanionBond.Stage.values()) {
                assertTrue(l.has("gui.logos_dimension.companion.stage." + st.name().toLowerCase(Locale.ROOT)), locale + " 缺羁绊 " + st);
            }
            for (String k : List.of("tab.stats", "tab.skills", "tab.memories", "stat.health", "stat.attack", "stat.armor", "stat.toughness", "kind.normal",
                    "kind.special", "kind.passive", "inherit", "inherit_hint", "bond", "satiety", "now", "seals", "skill_base", "skill_seal", "locked",
                    "ready", "used_today", "locked_skill", "locked_skill_desc", "memories", "memory_locked", "esc", "drag", "base_bonus", "outfit")) {
                assertTrue(l.has("gui.logos_dimension.companion." + k), locale + " 缺面板 " + k);
            }
            for (String k : List.of("state.follow", "state.stay")) assertTrue(l.has("talk.logos_dimension." + k), locale + " 缺 " + k);
            // 往事一段几句，语言文件里不多不少
            for (int n = 0; n < ShiraumeTalk.MEMORY_LINES.length; n++) {
                assertFalse(l.has("line.logos_dimension.shiraume.talk.past_" + n + "_" + (ShiraumeTalk.MEMORY_LINES[n] + 1)), "往事 " + n + " 比 MEMORY_LINES 多一句");
            }
        }
    }
}
