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

    /** 骨盆封口使用肤色，正面 90° UV 不丢失，胸顶精确增加 0.1 深度。 */
    @Test void revisedBodyKeepsThePelvisCapAndRotatedSkinUv() throws Exception {
        com.google.gson.JsonArray bones;
        try (var in = getClass().getResourceAsStream("/assets/logos_dimension/geckolib/models/entity/forest_elf.geo.json")) {
            assertNotNull(in);
            bones = JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones");
        }
        com.google.gson.JsonObject pelvis = null, chestTop = null;
        for (var element : bones) {
            var bone = element.getAsJsonObject();
            if (bone.get("name").getAsString().equals("DownBody")) for (var cube : bone.getAsJsonArray("cubes"))
                if (cube.getAsJsonObject().getAsJsonArray("size").get(0).getAsDouble() == 7.5) pelvis = cube.getAsJsonObject();
            if (bone.get("name").getAsString().equals("UpperBody")) for (var cube : bone.getAsJsonArray("cubes"))
                if (cube.getAsJsonObject().getAsJsonArray("size").get(0).getAsDouble() == 6.0) chestTop = cube.getAsJsonObject();
        }
        assertNotNull(pelvis);
        var uv = pelvis.getAsJsonObject("uv");
        assertEquals(90, uv.getAsJsonObject("north").get("uv_rotation").getAsInt());
        var cap = uv.getAsJsonObject("up");
        assertNotNull(cap, "骨盆顶面未封口");
        try (var in = getClass().getResourceAsStream("/assets/logos_dimension/textures/entity/forest_elf.png")) {
            assertNotNull(in);
            var image = javax.imageio.ImageIO.read(in);
            var point = cap.getAsJsonArray("uv");
            assertEquals(0xFFFFE1D5, image.getRGB((int) point.get(0).getAsDouble(), (int) point.get(1).getAsDouble()));
        }
        assertNotNull(chestTop);
        assertEquals(4.1, chestTop.getAsJsonArray("size").get(2).getAsDouble(), 0.00001);
    }

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

    /** 第三批进入随机池，存档回读保留选择，其他衣服和发型全部隐藏。 */
    @Test void thirdBatchRollsAndKeepsOnlyTheSelectedOutfitAndHairVisible() {
        Set<String> outfits = Set.of("outfit_vine_guard", "outfit_priestess", "outfit_floral_gown");
        Set<String> rolled = new HashSet<>();
        boolean braidsRolled = false;
        RandomSource random = RandomSource.create(20261031);
        for (int i = 0; i < 2000; i++) {
            var appearance = ELF.random(random);
            rolled.add(appearance.get("outfit"));
            braidsRolled |= "hair_priestess_braids".equals(appearance.get("hair"));
        }
        assertTrue(rolled.containsAll(outfits), "第三批服装未进入随机池");
        assertTrue(braidsRolled, "双麻花辫未进入随机池");
        for (String outfit : outfits) {
            var selected = ELF.defaults();
            selected.put("hair", "hair_priestess_braids");
            selected.put("outfit", outfit);
            assertEquals(selected, ELF.sanitize(NpcParts.decode(NpcParts.encode(selected))));
            var hidden = ELF.hiddenGroups(selected);
            for (String slot : Set.of("outfit", "hair")) for (var part : ELF.slot(slot).parts()) {
                for (String group : part.groups())
                    assertEquals(!part.id().equals(selected.get(slot)), hidden.contains(group), group);
            }
        }
        var gownWithPonytail = ELF.defaults();
        gownWithPonytail.put("hair", "hair_high_ponytail");
        gownWithPonytail.put("outfit", "outfit_floral_gown");
        assertEquals(gownWithPonytail, ELF.sanitize(gownWithPonytail), "祭祀发型不能与新衣服强制绑定");
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

    /** 兜帽一定带披风；披风仍能搭配其他发型，不强制反向绑定。 */
    @Test void hoodRequiresRangerButRangerCanAppearWithoutHood() {
        RandomSource random = RandomSource.create(20261028);
        int hood = 0, capeWithoutHood = 0;
        for (int i = 0; i < 2000; i++) {
            Map<String, String> a = ELF.random(random);
            if ("hair_hooded".equals(a.get("hair"))) {
                hood++;
                assertEquals("outfit_ranger", a.get("outfit"), a.toString());
                assertEquals(NpcParts.NONE, a.get("headwear"));
            } else if ("outfit_ranger".equals(a.get("outfit"))) capeWithoutHood++;
            assertEquals(a, ELF.sanitize(a));
        }
        assertTrue(hood > 0);
        assertTrue(capeWithoutHood > 0);
    }

    /** 手动组合或旧存档里的孤立兜帽补齐披风；其他发型与服装保持原组合。 */
    @Test void sanitizeCompletesHoodOutfitWithoutChangingOtherCombinations() {
        for (String outfit : new String[]{NpcParts.NONE, "missing", "outfit_casual", "outfit_scholar", "outfit_ranger"}) {
            Map<String, String> a = ELF.defaults();
            a.put("hair", "hair_hooded");
            a.put("outfit", outfit);
            Map<String, String> fixed = ELF.sanitize(a);
            assertEquals("hair_hooded", fixed.get("hair"));
            assertEquals("outfit_ranger", fixed.get("outfit"));
            assertEquals(NpcParts.NONE, fixed.get("headwear"));
            assertEquals(fixed, ELF.sanitize(fixed));
        }
        for (String hair : new String[]{NpcParts.NONE, "hair_straight_long", "hair_short_bob", "hair_high_ponytail"}) {
            for (var outfit : ELF.slot("outfit").parts()) {
                Map<String, String> a = ELF.defaults();
                a.put("hair", hair);
                a.put("outfit", outfit.id());
                assertEquals(a, ELF.sanitize(a));
            }
        }
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

    /** 原有十三位世界树居民保留，顶层额外两位守卫；独立树屋仍各两位。 */
    @Test void templatesCarryResidents() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertEquals(2, residents("giant_" + i + "_home"), "giant_" + i + "_home");
            assertEquals(0, residents("giant_" + i), "giant_" + i);
        }
        int world = 0;
        for (int x = 0; x < 7; x++) for (int y = 0; y < 7; y++) for (int z = 0; z < 7; z++) world += residents("tile_" + x + "_" + y + "_" + z);
        try (var in = getClass().getResourceAsStream("/data/logos_dimension/elven_forest_manifest.json")) {
            assertNotNull(in);
            var manifest = JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(manifest.getAsJsonObject("world_tree").getAsJsonArray("residents").size(), world);
            assertTrue(world >= 15, "原有居民与两位顶楼守卫必须保留");
        }
    }
}
