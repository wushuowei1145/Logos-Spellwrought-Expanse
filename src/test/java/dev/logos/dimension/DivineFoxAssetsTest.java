package dev.logos.dimension;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.logos.dimension.npc.NpcParts;
import net.minecraft.util.RandomSource;

/** 神狐的模型、零件表与动画文件（tools/divine_fox_assets.py 导入）的静态检查。 */
class DivineFoxAssetsTest {
    private static final String ROOT = "/assets/logos_dimension/geckolib/";
    private static final NpcParts.Race FOX = NpcParts.DIVINE_FOX;
    /** 常驻叠加层：眨眼、耳朵抖动、尾巴轻摆。 */
    private static final String[] LAYERS = {"pre_parallel0", "pre_parallel1", "pre_parallel2"};

    private static JsonObject read(String path) throws Exception {
        try (var in = DivineFoxAssetsTest.class.getResourceAsStream(ROOT + path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static JsonObject animations() throws Exception {
        return read("animations/entity/divine_fox.animation.json").getAsJsonObject("animations");
    }

    private static Set<String> modelBones() throws Exception {
        Set<String> bones = new HashSet<>();
        for (JsonElement b : read("models/entity/divine_fox.geo.json").getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonArray("bones")) bones.add(b.getAsJsonObject().get("name").getAsString());
        return bones;
    }

    private static Set<String> bonesOf(String animation) throws Exception {
        return animations().getAsJsonObject(animation).getAsJsonObject("bones").keySet();
    }

    @Test
    void partGroupsExistInTheModelAndRandomLooksRoundTrip() throws Exception {
        Set<String> bones = modelBones();
        for (var slot : FOX.slots()) for (var part : slot.parts()) for (String g : part.groups())
            assertTrue(bones.contains(g), "模型里缺少零件组 " + g);
        RandomSource random = RandomSource.create(7);
        for (int i = 0; i < 50; i++) {
            Map<String, String> look = FOX.random(random);
            assertEquals(look, FOX.sanitize(NpcParts.decode(NpcParts.encode(look))));
        }
    }

    /** 五种表情登记在零件表的 expressions 里，挂在 Head 下，换掉常态眼嘴；它们的零件不在任何随机槽位里。 */
    @Test
    void expressionsReplaceEyesAndMouthAndAreNeverRolled() throws Exception {
        Map<String, String> parent = new java.util.HashMap<>();
        for (JsonElement b : read("models/entity/divine_fox.geo.json").getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonArray("bones")) {
            JsonObject o = b.getAsJsonObject();
            parent.put(o.get("name").getAsString(), o.has("parent") ? o.get("parent").getAsString() : null);
        }
        var ids = FOX.expressions().stream().map(NpcParts.Expression::id).toList();
        assertEquals(java.util.List.of("expression_teasing", "expression_kitsune", "expression_happy", "expression_surprised",
                "expression_angry"), ids);
        Set<String> rolled = new HashSet<>();
        for (var slot : FOX.slots()) for (var part : slot.parts()) rolled.addAll(part.groups());
        for (var e : FOX.expressions()) {
            assertEquals(java.util.List.of("Eyelid", "Mouth"), e.hides(), e.id());
            for (String g : e.groups()) {
                assertEquals("Head", parent.get(g), g);
                assertFalse(rolled.contains(g), g);
            }
            for (String g : rolled) assertFalse(g.startsWith(e.id().replace("expression_", "eyes_fox_")), g);
        }
        Map<String, String> look = FOX.defaults();
        assertTrue(FOX.hiddenGroups(look, null).containsAll(ids));
        var shown = FOX.hiddenGroups(look, "expression_happy");
        assertFalse(shown.contains("expression_happy"));
        assertTrue(shown.containsAll(java.util.List.of("Eyelid", "Mouth")));
    }

    @Test
    void hasEveryStateAndLayer() throws Exception {
        Set<String> names = animations().keySet();
        for (String n : new String[] {"idle", "walk", "jump", "attacked", "ladder_up", "ladder_stillness", "ladder_down", "swim_stand",
                "swing_hand", "death", "pre_parallel0", "pre_parallel1", "pre_parallel2"})
            assertTrue(names.contains(n), n);
    }

    @Test
    void everyAnimatedBoneExistsInTheModel() throws Exception {
        Set<String> bones = modelBones();
        for (Map.Entry<String, JsonElement> a : animations().entrySet())
            for (String bone : a.getValue().getAsJsonObject().getAsJsonObject("bones").keySet())
                assertTrue(bones.contains(bone), a.getKey() + " → " + bone);
    }

    @Test
    void noPlayerModelOnlyMolang() throws Exception {
        assertFalse(animations().toString().contains("ysm."), "动画里有 ysm.* 变量");
    }

    /** 侧倒后面朝地面的死亡动画播完停在最后一帧，接在受击动画之后。 */
    @Test
    void deathHoldsItsLastFrame() throws Exception {
        JsonObject death = animations().getAsJsonObject("death");
        assertEquals("hold_on_last_frame", death.get("loop").getAsString());
        assertEquals(1.55, death.get("animation_length").getAsDouble(), 1e-6);
    }

    /** 叠加层循环且首尾一致，循环接缝处不抽动。 */
    @Test
    void layersLoopSeamlessly() throws Exception {
        for (String layer : LAYERS) {
            JsonObject anim = animations().getAsJsonObject(layer);
            assertTrue(anim.get("loop").getAsBoolean(), layer);
            for (Map.Entry<String, JsonElement> bone : anim.getAsJsonObject("bones").entrySet())
                for (Map.Entry<String, JsonElement> channel : bone.getValue().getAsJsonObject().entrySet()) {
                    JsonObject frames = channel.getValue().getAsJsonObject();
                    String first = frames.keySet().iterator().next(), last = null;
                    for (String k : frames.keySet()) last = k;
                    assertEquals(0, Double.parseDouble(first), 1e-9, layer + " " + bone.getKey());
                    assertEquals(anim.get("animation_length").getAsDouble(), Double.parseDouble(last), 1e-6, layer + " " + bone.getKey());
                    assertEquals(frames.get(first), frames.get(last), layer + " " + bone.getKey());
                }
        }
    }

    /**
     * 叠加层在主状态之前播放、被主状态的同一骨骼盖过：待机和移动类动作不碰这些骨骼，叠加层一直有效；
     * 死亡动画全都接管，倒下后不再眨眼、抖耳、摇尾巴。
     */
    @Test
    void layersRunInMostStatesAndStopOnDeath() throws Exception {
        Set<String> death = bonesOf("death");
        for (String layer : LAYERS) {
            Set<String> bones = bonesOf(layer);
            for (String state : new String[] {"idle", "walk", "jump", "attacked", "swim_stand", "swing_hand"}) {
                Set<String> shared = new HashSet<>(bones);
                shared.retainAll(bonesOf(state));
                assertTrue(shared.isEmpty(), layer + " 被 " + state + " 盖过：" + shared);
            }
            assertTrue(death.containsAll(bones), layer + " 在死亡时仍会播放");
        }
    }
}
