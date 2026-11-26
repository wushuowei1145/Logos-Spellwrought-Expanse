package dev.logos.dimension;

import dev.logos.dimension.firearm.SpellRounds;
import dev.logos.lang.Compiler;
import dev.logos.lang.LogosError;
import dev.logos.lang.Runes;
import dev.logos.lang.Unlocks;
import dev.logos.runtime.HostedSpells;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 术式弹（docs/FIREARMS.md 第 9 节）：三种弹是 Logos 的寄托形体，图纸写成「启式 → 这种弹 → 事件」，蚀刻七档照载页。 */
class SpellRoundTest {
    static {
        SpellRounds.register();
    }

    private static final List<String> WORDS = List.of(SpellRounds.BUCKSHOT, SpellRounds.RIFLE, SpellRounds.BLANK);
    private static final List<String> TIERS = List.of("basic", "crystal", "echo", "transmuted", "chalk", "firmament", "sage");

    @Test void eachRoundIsAHostedFormWithThreeEvents() {
        for (var word : WORDS) {
            var r = Runes.get(word);
            assertNotNull(r, word);
            assertTrue(r.hosted, word);
            assertEquals(List.of("on-hit", "on-impact", "on-expire"), List.copyOf(r.events.keySet()), word);
            assertTrue(r.events.get("on-hit").bindsTarget() && !r.events.get("on-impact").bindsTarget(), word);
            assertFalse(Unlocks.lockable(Unlocks.rune(word)), "anyone may write " + word);
        }
        SpellRounds.register();
        assertNotNull(Runes.get(SpellRounds.RIFLE), "registering twice is harmless");
    }

    @Test void aNewBlueprintIsTheRoundWithNoEventsYet() throws Exception {
        for (var shape : List.of("buckshot", "rifle", "blank")) {
            var r = LongswordMotionTest.read("/data/logos_dimension/recipe/ammo_blueprint_" + shape + ".json");
            var result = r.getAsJsonObject("result");
            assertEquals("logos_dimension:ammo_blueprint", result.get("id").getAsString());
            String source = result.getAsJsonObject("components").get("logos:spell").getAsString();
            var spell = Compiler.compile(source);
            var form = HostedSpells.form(spell);
            assertNotNull(form, source);
            assertEquals(shape + "-round", form.rune.name);
            assertTrue(form.events.isEmpty(), "the events are left for the writer");
            var ingredients = r.getAsJsonArray("ingredients");
            assertEquals("minecraft:paper", ingredients.get(0).getAsString());
        }
    }

    @Test void aWrittenBlueprintCompilesWithItsEvents() {
        String src = "(spell :name \"爆裂弹\" :mana 30 (rifle-round (on-hit (damage target 5 fire)) (on-impact (damage-all (enemies :radius 3) 2)) (on-expire (mark caster #far))))";
        var spell = Compiler.compile(src);
        assertEquals(3, HostedSpells.form(spell).events.size());
        assertEquals(30, spell.mana(), 1e-9);
        // 只在命中生物时才有 target。
        assertThrows(LogosError.class, () -> Compiler.compile("(rifle-round (on-expire (damage target 5)))"));
        assertThrows(LogosError.class, () -> Compiler.compile("(rifle-round :speed 2)"));
    }

    @Test void etchingFollowsThePageTiersAndBlankRoundsHoldTwice() throws Exception {
        int last = 0;
        for (int i = 0; i < TIERS.size(); i++) {
            var r = LongswordMotionTest.read("/data/logos_dimension/recipe/round_etching/" + TIERS.get(i) + ".json");
            assertEquals("logos_dimension:round_etching", r.get("type").getAsString());
            assertEquals(i + 1, r.get("tier").getAsInt());
            int capacity = r.get("capacity").getAsInt();
            assertTrue(capacity > last, TIERS.get(i) + " holds more than the tier below");
            last = capacity;
            String conductor = r.get("conductor").getAsString();
            assertEquals(i < 2 ? "minecraft:gold_ingot" : "logos:living_gold_ingot", conductor, TIERS.get(i));
            assertEquals(i > 0, r.has("catalyst"), "only the basic tier needs no catalyst");
        }
        assertEquals(10, SpellRounds.capacity(SpellRounds.RIFLE, 10));
        assertEquals(20, SpellRounds.capacity(SpellRounds.BLANK, 10));
    }
    @Test void everyRoundLooksDifferentForEachTierAndModification() throws Exception {
        var spells = List.of("spell_1", "spell_2", "spell_3", "spell_4", "spell_5", "spell_6", "spell_7");
        var mods = List.of("sulfur", "mercury", "firework", "glowing", "ender", "blast", "potion");
        for (var round : List.of("copper_buckshot_shell", "buckshot_shell", "netherite_buckshot_shell",
                "copper_rifle_round", "iron_rifle_round", "netherite_rifle_round", "blank_round")) {
            var model = LongswordMotionTest.read("/assets/logos_dimension/items/" + round + ".json").getAsJsonObject("model");
            assertEquals("minecraft:select", model.get("type").getAsString(), round);
            assertEquals("logos_dimension:round_finish", model.get("component").getAsString(), round);
            List<String> cases = new java.util.ArrayList<>();
            for (var c : model.getAsJsonArray("cases")) {
                String when = c.getAsJsonObject().get("when").getAsString();
                cases.add(when);
                String path = c.getAsJsonObject().getAsJsonObject("model").get("model").getAsString().replace("logos_dimension:", "");
                assertNotNull(SpellRoundTest.class.getResource("/assets/logos_dimension/models/" + path + ".json"), path);
                assertNotNull(SpellRoundTest.class.getResource("/assets/logos_dimension/textures/" + path + ".png"), path);
            }
            var expected = new java.util.ArrayList<>(spells);
            // 空弹头没有弹丸，不改装
            if (!round.equals("blank_round")) expected.addAll(mods);
            assertEquals(expected, cases, round);
            assertEquals("logos_dimension:item/" + round, model.getAsJsonObject("fallback").get("model").getAsString());
        }
    }

    @Test void modifyingTakesOneAdditiveAndGivesTheBottleBack() throws Exception {
        // 改装 → 添加物、一份改几发（不写就是八发）
        var additives = java.util.Map.of("sulfur", "logos:sulfur_powder", "mercury", "logos:mercury_bottle", "firework", "minecraft:firework_star",
                "glowing", "minecraft:glowstone_dust", "ender", "minecraft:ender_pearl", "blast", "minecraft:tnt", "potion", "logos:potion_powder");
        var rounds = java.util.Map.of("ender", 1, "blast", 4, "potion", 1);
        for (var e : additives.entrySet()) {
            var r = LongswordMotionTest.read("/data/logos_dimension/recipe/round_modifying/" + e.getKey() + ".json");
            assertEquals("logos_dimension:round_modifying", r.get("type").getAsString());
            assertEquals(e.getKey(), r.get("finish").getAsString());
            assertEquals(e.getValue(), r.get("additive").getAsString());
            assertEquals(e.getKey().equals("mercury"), r.has("remainder"), e.getKey());
            assertEquals((int) rounds.getOrDefault(e.getKey(), 8), r.has("rounds") ? r.get("rounds").getAsInt() : 8, e.getKey());
        }
    }

    @Test void potionRoundsAreTintedLikeThePowder() throws Exception {
        for (var round : List.of("buckshot_shell", "iron_rifle_round")) {
            var model = LongswordMotionTest.read("/assets/logos_dimension/items/" + round + ".json").getAsJsonObject("model");
            com.google.gson.JsonObject potion = null;
            for (var c : model.getAsJsonArray("cases")) {
                if (c.getAsJsonObject().get("when").getAsString().equals("potion")) potion = c.getAsJsonObject().getAsJsonObject("model");
            }
            assertNotNull(potion, round);
            var tints = potion.getAsJsonArray("tints");
            assertEquals("minecraft:potion", tints.get(1).getAsJsonObject().get("type").getAsString(), round);
            var textures = LongswordMotionTest.read("/assets/logos_dimension/models/item/" + round + "_potion.json").getAsJsonObject("textures");
            assertTrue(textures.get("layer1").getAsString().startsWith("logos_dimension:item/potion_mark_"), round);
        }
    }
}
