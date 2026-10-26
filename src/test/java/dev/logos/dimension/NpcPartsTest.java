package dev.logos.dimension;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import dev.logos.dimension.npc.NpcParts;

class NpcPartsTest {
    private static final NpcParts.Race ELF = NpcParts.FOREST_ELF;

    @Test void encodeDecodeRoundTrip() {
        Map<String, String> a = ELF.defaults();
        assertEquals(a, NpcParts.decode(NpcParts.encode(a)));
        assertTrue(NpcParts.decode("").isEmpty());
    }

    @Test void sanitizeFallsBackToDefaultsAndKeepsNoneOnlyForOptionalSlots() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("hair", "hair_removed_long_ago");
        in.put("headwear", NpcParts.NONE);
        in.put("eyes", NpcParts.NONE);
        in.put("unknown_slot", "x");
        Map<String, String> out = ELF.sanitize(in);
        assertEquals("hair_straight_long", out.get("hair"));
        assertEquals(NpcParts.NONE, out.get("headwear"));
        assertEquals("eyes_square", out.get("eyes"), "眼睛不是可选槽位，不能选 none");
        assertEquals(ELF.slots().size(), out.size());
        assertFalse(out.containsKey("unknown_slot"));
    }

    @Test void hiddenGroupsAreEveryUnselectedPart() {
        Map<String, String> a = ELF.defaults();
        a.put("hair", "hair_short_bob");
        a.put("outfit", NpcParts.NONE);
        Set<String> hidden = ELF.hiddenGroups(a);
        assertTrue(hidden.contains("hair_straight_long"));
        assertFalse(hidden.contains("hair_short_bob"));
        assertTrue(hidden.contains("outfit_villager_skirt"));
        assertTrue(hidden.contains("eyes_droopy_left_pupil"));
        assertFalse(hidden.contains("eyes_square_left_pupil"));
    }

    /** 零件表里的每个组都在模型里，模型里每个零件前缀的顶层组也都登记了。 */
    @Test void partGroupsMatchTheModel() throws Exception {
        String text;
        try (var in = NpcPartsTest.class.getResourceAsStream("/assets/logos_dimension/geckolib/models/entity/forest_elf.geo.json")) {
            assertNotNull(in, "找不到森林精灵模型");
            text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var bones = JsonParser.parseString(text).getAsJsonObject()
                .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones");
        Map<String, String> parent = new LinkedHashMap<>();
        for (JsonElement b : bones) {
            var o = b.getAsJsonObject();
            parent.put(o.get("name").getAsString(), o.has("parent") ? o.get("parent").getAsString() : null);
        }
        Set<String> listed = new HashSet<>();
        for (var slot : ELF.slots()) for (var part : slot.parts()) listed.addAll(part.groups());
        for (String g : listed) assertTrue(parent.containsKey(g), "模型里缺少零件组 " + g);
        for (var e : parent.entrySet()) {
            String n = e.getKey(), p = e.getValue();
            boolean partName = n.startsWith("hair_") || n.startsWith("headwear_") || n.startsWith("ear_") || n.startsWith("eyes_")
                    || n.startsWith("mouth_") || n.startsWith("outfit_");
            boolean top = p == null || !(p.startsWith("hair_") || p.startsWith("headwear_") || p.startsWith("ear_")
                    || p.startsWith("eyes_") || p.startsWith("mouth_") || p.startsWith("outfit_"));
            if (partName && top) assertTrue(listed.contains(n), "模型里的零件组没登记：" + n);
        }
    }
}
