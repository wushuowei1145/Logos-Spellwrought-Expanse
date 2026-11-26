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
}
