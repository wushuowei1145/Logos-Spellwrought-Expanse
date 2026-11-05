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

    /**
     * 四款短和服与短浴衣各有短袜、过膝袜两版，都是可随机抽到的衣装；过膝袜的大腿段挂在大腿骨骼上，短袜没有。
     * 侧倒死亡动画给它们的垂袖和裙片都加了关键帧，倒地时布料不穿进地面。
     */
    @Test
    void shortOutfitsAreRollableAndCoveredByDeath() throws Exception {
        Map<String, String> parent = new java.util.HashMap<>();
        for (JsonElement b : read("models/entity/divine_fox.geo.json").getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonArray("bones")) {
            JsonObject o = b.getAsJsonObject();
            parent.put(o.get("name").getAsString(), o.has("parent") ? o.get("parent").getAsString() : null);
        }
        var outfits = FOX.slots().stream().filter(s -> s.name().equals("outfit")).findFirst().orElseThrow().parts().stream()
                .map(NpcParts.Part::id).toList();
        Set<String> death = bonesOf("death");
        for (String style : new String[] {"short_miko_kimono", "short_brocade_kimono", "short_rose_yukata", "short_sakura_yukata"})
            for (String socks : new String[] {"ankle", "overknee"}) {
                String id = "outfit_" + style + "_" + socks;
                assertTrue(outfits.contains(id), id);
                assertEquals(socks.equals("overknee") ? "LeftLeg" : null, parent.get(id + "_left_sock_thigh"), id);
                assertEquals("LeftLowerLeg", parent.get(id + "_left_sock_shin"), id);
                for (String bone : java.util.List.of(id + "_left_sleeve_drop_upper", id + "_right_sleeve_drop_lower", id + "_skirt_front_right_00")) {
                    assertTrue(parent.containsKey(bone), bone);
                    assertTrue(death.contains(bone), bone);
                }
            }
    }

    /** 尾巴静止时就是弯的：每条尾链先往下垂（中间的支点低于尾根），尾尖再翘起来（最后一节支点高于最低点）；三尾中间那条向上拱。 */
    @Test
    void tailsCurveAtRest() throws Exception {
        Map<String, double[]> pivot = new java.util.TreeMap<>();
        for (JsonElement b : read("models/entity/divine_fox.geo.json").getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonArray("bones")) {
            JsonObject o = b.getAsJsonObject();
            String name = o.get("name").getAsString();
            if (name.startsWith("tail_fox_") && name.contains("_seg")) {
                var p = o.getAsJsonArray("pivot");
                pivot.put(name, new double[] {p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble()});
            }
        }
        Map<String, java.util.List<double[]>> chains = new java.util.TreeMap<>();
        pivot.forEach((name, p) -> chains.computeIfAbsent(name.substring(0, name.lastIndexOf("_seg")), k -> new java.util.ArrayList<>()).add(p));
        assertEquals(5, chains.size(), chains.keySet().toString());
        for (var chain : chains.entrySet()) {
            var p = chain.getValue();
            double root = p.get(0)[1], low = p.stream().mapToDouble(v -> v[1]).min().orElseThrow(), tip = p.get(p.size() - 1)[1];
            if (chain.getKey().equals("tail_fox_triple_chain1")) assertTrue(tip > root + 5, chain.getKey());
            else {
                assertTrue(low < root - 3, chain.getKey() + " 应先下垂");
                assertTrue(tip > low + 1, chain.getKey() + " 尾尖应上翘");
            }
        }
    }

    @Test
    void hasEveryStateAndLayer() throws Exception {
        Set<String> names = animations().keySet();
        for (String n : new String[] {"idle", "walk", "jump", "attacked", "ladder_up", "ladder_stillness", "ladder_down", "swim_stand",
                "swing_hand", "death", "pre_parallel0", "pre_parallel1", "pre_parallel2", "pre_parallel3",
                "hold_mainhand:bow", "use_mainhand:bow", "greet", "refuse"})
            assertTrue(names.contains(n), n);
    }

    /**
     * 打刀：四套刀装各有刀身、刀鞘两组，挂在 UpBody 下的 Weapon / Sheath；每个招式的动作都在（加 katana_ 前缀），
     * 时长与招式文件一致（服务端状态机按招式文件计时，客户端按动作文件播放）；佩刀层把刀与鞘停在同一处。
     */
    @Test
    void katanaRigMatchesTheMoveset() throws Exception {
        Map<String, String> parent = new java.util.HashMap<>();
        for (JsonElement b : read("models/entity/divine_fox.geo.json").getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonArray("bones")) {
            JsonObject o = b.getAsJsonObject();
            parent.put(o.get("name").getAsString(), o.has("parent") ? o.get("parent").getAsString() : null);
        }
        assertEquals("UpBody", parent.get("Weapon"));
        assertEquals("UpBody", parent.get("Sheath"));
        for (String v : dev.logos.dimension.npc.DivineFox.KATANAS) {
            assertEquals("Weapon", parent.get("katana_" + v + "_blade"), v);
            assertEquals("Sheath", parent.get("katana_" + v + "_saya"), v);
        }
        JsonObject moves;
        try (var in = DivineFoxAssetsTest.class.getResourceAsStream("/data/logos_dimension/combat/movesets/uchigatana.json")) {
            moves = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("moves");
        }
        JsonObject anims = animations();
        for (var m : moves.entrySet()) {
            JsonObject move = m.getValue().getAsJsonObject();
            String clip = "katana_" + (move.has("clip") ? move.get("clip").getAsString() : m.getKey());
            assertTrue(anims.has(clip), clip);
            assertEquals(move.get("length").getAsDouble(), anims.getAsJsonObject(clip).get("animation_length").getAsDouble(), 1e-4, clip);
        }
        JsonObject carry = anims.getAsJsonObject("pre_parallel3").getAsJsonObject("bones");
        assertEquals(Set.of("Weapon", "Sheath"), carry.keySet());
        assertEquals(carry.get("Weapon"), carry.get("Sheath"), "佩刀时刀在鞘里，两者同一变换");
    }

    /** 狐纹只有一成神狐有：零件表写了 chance 0.1，随机外观大约十只里一只。 */
    @Test
    void markingsAppearOnAboutOneFoxInTen() {
        assertEquals(.1, FOX.slot("markings").chance(), 1e-9);
        RandomSource random = RandomSource.create(11);
        int marked = 0, n = 4000;
        for (int i = 0; i < n; i++) if (!NpcParts.NONE.equals(FOX.random(random).get("markings"))) marked++;
        assertEquals(.1, marked / (double) n, .02);
        assertEquals(1, FOX.slot("outfit").chance(), 1e-9, "其他槽位不受影响");
    }

    /** 语音：九个事件都有素材，文件都在。 */
    @Test
    void voiceEventsHaveSounds() throws Exception {
        JsonObject sounds;
        try (var in = DivineFoxAssetsTest.class.getResourceAsStream("/assets/logos_dimension/sounds.json")) {
            sounds = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (String e : new String[] {"ambient", "greet", "yes", "no", "alert", "attack", "cast", "hurt", "death"}) {
            var list = sounds.getAsJsonObject("entity.divine_fox." + e).getAsJsonArray("sounds");
            assertFalse(list.isEmpty(), e);
            for (JsonElement s : list) {
                String path = "/assets/logos_dimension/sounds/" + s.getAsString().substring("logos_dimension:".length()) + ".ogg";
                try (var in = DivineFoxAssetsTest.class.getResourceAsStream(path)) {
                    assertNotNull(in, path);
                }
            }
        }
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
