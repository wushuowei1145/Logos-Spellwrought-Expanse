package dev.logos.dimension.companion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 装束（docs/SHIRAUME_COMPANION.md 第 8.10 节，模型第 9 版）：两套装束的组都挂在同一组衣服骨骼下，组名 {@code outfit_<id>_<骨骼>}，
 * 渲染时隐藏别的装束的顶层组；浴衣的梅灯、梅花簪在浴衣的组里，而且有动画。
 */
class CompanionOutfitTest {
    private static JsonObject read(String path) throws Exception {
        try (var in = CompanionOutfitTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** 骨骼名 → 父骨骼名。 */
    private static Map<String, String> bones() throws Exception {
        var geo = read("/assets/logos_dimension/geckolib/models/entity/shiraume_companion.geo.json");
        Map<String, String> parent = new HashMap<>();
        for (var b : geo.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones")) {
            var o = b.getAsJsonObject();
            parent.put(o.get("name").getAsString(), o.has("parent") ? o.get("parent").getAsString() : null);
        }
        return parent;
    }

    /** 这套装束的组挂在哪些骨骼下。 */
    private static Set<String> hosts(Map<String, String> parent, CompanionOutfit o) {
        Set<String> out = new TreeSet<>();
        for (var e : parent.entrySet()) {
            if (e.getKey().startsWith(CompanionOutfit.PREFIX + o.id + "_")) {
                assertFalse(e.getValue().startsWith(CompanionOutfit.PREFIX), e.getKey() + " 挂在另一个装束组下");
                assertEquals(CompanionOutfit.PREFIX + o.id + "_" + e.getValue(), e.getKey(), "组名要是 outfit_<id>_<父骨骼>");
                out.add(e.getValue());
            }
        }
        return out;
    }

    @Test void bothOutfitsDressTheSameClothingBones() throws Exception {
        var parent = bones();
        Set<String> miko = hosts(parent, CompanionOutfit.MIKO), yukata = hosts(parent, CompanionOutfit.UMEAKARI);
        assertEquals(13, miko.size(), miko.toString());
        assertTrue(yukata.containsAll(miko), "浴衣要换掉巫女服的每一件：" + miko + " / " + yukata);
        assertEquals(Set.of("RightSideHair"), new TreeSet<>(yukata) {{ removeAll(miko); }}, "浴衣只多出发上的梅花簪");
    }

    @Test void hidingTheOtherOutfitNeverHidesSharedBones() throws Exception {
        for (String bone : bones().keySet()) {
            boolean outfit = bone.startsWith(CompanionOutfit.PREFIX);
            for (CompanionOutfit o : CompanionOutfit.values()) {
                if (!outfit) assertFalse(o.hides(bone), bone);
                else assertEquals(!bone.startsWith(CompanionOutfit.PREFIX + o.id + "_"), o.hides(bone), bone);
            }
        }
    }

    @Test void theLanternAndKanzashiBelongToTheYukataAndSway() throws Exception {
        var parent = bones();
        assertEquals("outfit_umeakari_Obi", parent.get("UmeLantern"));
        assertEquals("outfit_umeakari_RightSideHair", parent.get("UmeKanzashi"));
        var anims = read("/assets/logos_dimension/geckolib/animations/entity/shiraume_companion.animation.json").getAsJsonObject("animations");
        for (String clip : new String[]{"idle", "walk", "run", "act_wave"}) {
            var bonesOf = anims.getAsJsonObject(clip).getAsJsonObject("bones");
            assertTrue(bonesOf.has("UmeLantern") && bonesOf.has("UmeKanzashi"), clip + " 里梅灯、梅花簪要摆");
        }
    }
}
