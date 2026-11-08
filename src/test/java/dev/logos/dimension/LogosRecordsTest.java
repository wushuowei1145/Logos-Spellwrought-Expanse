package dev.logos.dimension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.logos.dimension.item.LogosRecords;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 反射造 Logos 记录：Logos 给术式加了字段（2026-11-08 多了 element），按名字填仍然造得出来。 */
class LogosRecordsTest {
    public record Old(String name, String trigger, boolean affectsPlayers, double mana, List<String> notes, Object profile) {}
    public record Grown(String name, String trigger, boolean affectsPlayers, double mana, List<String> notes, Object profile, String element, int extra) {}

    private static final Map<String, Object> VALUES = Map.of("name", "自然之力", "trigger", "cast", "affectsPlayers", true,
            "notes", List.of(), "element", "LIFE");

    @Test void oldShapeIgnoresFieldsItDoesNotHave() throws Exception {
        var spell = (Old) LogosRecords.make(Old.class, VALUES);
        assertEquals("自然之力", spell.name());
        assertEquals(0, spell.mana());
        assertNull(spell.profile());
    }

    @Test void grownShapeGetsNewFieldsAndDefaults() throws Exception {
        var spell = (Grown) LogosRecords.make(Grown.class, VALUES);
        assertEquals("cast", spell.trigger());
        assertEquals(true, spell.affectsPlayers());
        assertEquals("LIFE", spell.element());
        assertEquals(0, spell.extra());
    }
}
