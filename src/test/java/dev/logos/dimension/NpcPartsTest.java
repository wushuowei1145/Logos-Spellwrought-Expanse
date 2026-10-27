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
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.RandomSource;

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

    /** 动画专用表情平时隐藏；给出表情时显示它、隐藏常态眼嘴；不参与随机抽取。 */
    @Test void expressionsAreHiddenUntilShownAndReplaceTheFace() {
        Map<String, String> a = ELF.defaults();
        assertEquals(1, ELF.expressions().size());
        var tears = ELF.expressions().getFirst();
        assertEquals("expression_dazed_tears", tears.id());
        assertEquals(java.util.List.of("Eyes", "Eyelid", "EyeBrow", "Mouth"), tears.hides());
        Set<String> calm = ELF.hiddenGroups(a), dazed = ELF.hiddenGroups(a, tears.id());
        assertTrue(calm.containsAll(tears.groups()));
        for (String g : tears.hides()) assertFalse(calm.contains(g), g);
        for (String g : tears.groups()) assertFalse(dazed.contains(g), g);
        assertTrue(dazed.containsAll(tears.hides()));
        for (var slot : ELF.slots()) for (var part : slot.parts()) assertFalse(part.id().startsWith("expression_"), part.id());
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
        for (var e : ELF.expressions()) {
            for (String g : e.groups()) assertEquals("Head", parent.get(g), "表情要挂在 Head 下：" + g);
            for (String g : e.hides()) {
                String p = g;
                while (p != null && !p.equals("Head")) p = parent.get(p);
                assertEquals("Head", p, "表情替换的常态五官要在 Head 之下：" + g);
            }
        }
        for (var e : parent.entrySet()) {
            String n = e.getKey(), p = e.getValue();
            boolean partName = n.startsWith("hair_") || n.startsWith("headwear_") || n.startsWith("ear_") || n.startsWith("eyes_")
                    || n.startsWith("mouth_") || n.startsWith("outfit_");
            boolean top = p == null || !(p.startsWith("hair_") || p.startsWith("headwear_") || p.startsWith("ear_")
                    || p.startsWith("eyes_") || p.startsWith("mouth_") || p.startsWith("outfit_"));
            if (partName && top) assertTrue(listed.contains(n), "模型里的零件组没登记：" + n);
        }
    }

    @Test void tableComesFromDataAndHasTheNewParts() {
        assertNotNull(ELF.slot("hair").find("hair_hooded"));
        assertNotNull(ELF.slot("hair").find("hair_short_bob"));
        assertNotNull(ELF.slot("hair").find("hair_high_ponytail"));
        assertNotNull(ELF.slot("outfit").find("outfit_ranger"));
        for (String id : Set.of("outfit_gardener", "outfit_casual", "outfit_scholar"))
            assertNotNull(ELF.slot("outfit").find(id), id);
        assertEquals("hair_straight_long", ELF.slot("hair").parts().getFirst().id(), "默认零件排第一个");
        assertTrue(ELF.conflicts("hair_hooded", "headwear_twig_crown"));
        assertFalse(ELF.conflicts("hair_short_bob", "headwear_twig_crown"));
    }

    /** 新零件确实进入出生随机池，外观同步后不回退，隐藏组只包含未选中的零件。 */
    @Test void newAppearancePartsCanBeRolledAndRoundTripWithoutShowingOtherOutfits() {
        RandomSource random = RandomSource.create(20261027);
        Set<String> rolledHair = new HashSet<>(), rolledOutfits = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            Map<String, String> appearance = ELF.random(random);
            rolledHair.add(appearance.get("hair"));
            rolledOutfits.add(appearance.get("outfit"));
            assertEquals(appearance, ELF.sanitize(NpcParts.decode(NpcParts.encode(appearance))));
        }
        assertTrue(rolledHair.contains("hair_high_ponytail"), "高马尾没有进入随机池");
        for (String outfit : Set.of("outfit_gardener", "outfit_casual", "outfit_scholar")) {
            assertTrue(rolledOutfits.contains(outfit), outfit + " 没有进入随机池");
            Map<String, String> selected = ELF.defaults();
            selected.put("hair", "hair_high_ponytail");
            selected.put("outfit", outfit);
            assertEquals(selected, ELF.sanitize(selected));
            Set<String> hidden = ELF.hiddenGroups(selected);
            assertFalse(hidden.contains("hair_high_ponytail"));
            for (var part : ELF.slot("outfit").parts()) for (String group : part.groups())
                assertEquals(!part.id().equals(outfit), hidden.contains(group), group);
        }
    }

    /** 兜帽和枝冠互斥：随机抽不会同时出现；手动给出的组合会把后面的头饰换成 none。 */
    @Test void conflictingPartsNeverAppearTogether() {
        RandomSource random = RandomSource.create(42);
        int hooded = 0;
        for (int i = 0; i < 2000; i++) {
            Map<String, String> a = ELF.random(random);
            assertFalse("hair_hooded".equals(a.get("hair")) && "headwear_twig_crown".equals(a.get("headwear")), a.toString());
            if ("hair_hooded".equals(a.get("hair"))) {
                hooded++;
                assertEquals(NpcParts.NONE, a.get("headwear"), "戴兜帽时头饰槽只能空着");
            }
        }
        assertTrue(hooded > 0, "抽了 2000 次一次兜帽都没有");
        Map<String, String> clash = ELF.defaults();
        clash.put("hair", "hair_hooded");
        clash.put("headwear", "headwear_twig_crown");
        assertEquals(NpcParts.NONE, ELF.sanitize(clash).get("headwear"));
    }

    private static int residents(String template) throws Exception {
        try (var in = NpcPartsTest.class.getResourceAsStream("/data/logos_dimension/structure/elven/" + template + ".nbt")) {
            assertNotNull(in, template);
            var entities = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()).getListOrEmpty("entities");
            int n = 0;
            for (int i = 0; i < entities.size(); i++)
                if ("logos_dimension:forest_elf".equals(entities.getCompoundOrEmpty(i).getCompoundOrEmpty("nbt").getStringOr("id", ""))) n++;
            return n;
        }
    }

    /** 生成脚本写进模板的居民：每间树屋两位，没有屋子的巨木没有；世界树（大厅三位、三层回廊各两位、两间树屋各两位）共十三位。 */
    @Test void templatesCarryResidents() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertEquals(2, residents("giant_" + i + "_home"), "giant_" + i + "_home");
            assertEquals(0, residents("giant_" + i), "giant_" + i);
        }
        int world = 0;
        for (int x = 0; x < 7; x++) for (int y = 0; y < 7; y++) for (int z = 0; z < 7; z++) world += residents("tile_" + x + "_" + y + "_" + z);
        assertEquals(13, world);
    }
}
