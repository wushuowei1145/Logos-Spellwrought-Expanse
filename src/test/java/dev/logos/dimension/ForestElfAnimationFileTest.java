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

/** 森林精灵动画文件（tools/forest_elf_animations.py 生成）的静态检查。 */
class ForestElfAnimationFileTest {
    private static final String ROOT = "/assets/logos_dimension/geckolib/";

    private static JsonObject read(String path) throws Exception {
        try (var in = ForestElfAnimationFileTest.class.getResourceAsStream(ROOT + path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static JsonObject animations() throws Exception {
        return read("animations/entity/forest_elf.animation.json").getAsJsonObject("animations");
    }

    @Test
    void hasEveryActionTheElfUses() throws Exception {
        Set<String> names = animations().keySet();
        for (String n : new String[] {"idle", "walk", "jump", "attacked", "ladder_up", "ladder_stillness", "ladder_down", "swim_stand",
                "swing_hand", "hold_mainhand:bow", "use_mainhand:bow", "hold_mainhand:spear", "swing:spear",
                "hold_offhand:shield", "use_offhand:shield", "hold_mainhand:shield", "use_mainhand:shield",
                "heal_touch", "heal_ring", "greet", "refuse", "death"})
            assertTrue(names.contains(n), n);
    }

    @Test
    void everyAnimatedBoneExistsInTheModel() throws Exception {
        Set<String> bones = new HashSet<>();
        for (JsonElement b : read("models/entity/forest_elf.geo.json").getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonArray("bones")) bones.add(b.getAsJsonObject().get("name").getAsString());
        for (Map.Entry<String, JsonElement> a : animations().entrySet())
            for (String bone : a.getValue().getAsJsonObject().getAsJsonObject("bones").keySet())
                assertTrue(bones.contains(bone), a.getKey() + " → " + bone);
    }

    /** 后播放的举盾控制器不得覆盖主手刺击的骨骼。 */
    @Test
    void shieldAndSpearAnimateSeparateArms() throws Exception {
        var animations = animations();
        var spear = animations.getAsJsonObject("swing:spear").getAsJsonObject("bones").keySet();
        var shield = animations.getAsJsonObject("use_offhand:shield").getAsJsonObject("bones").keySet();
        assertTrue(java.util.Collections.disjoint(spear, shield));
        assertTrue(spear.stream().allMatch(n -> n.startsWith("Right")));
        assertTrue(shield.stream().allMatch(n -> n.startsWith("Left")));
    }

    /** TLM / YSM 专用的 Molang 变量我们的驱动不提供，出现了就是借用时没处理干净。 */
    @Test
    void noPlayerModelOnlyMolang() throws Exception {
        String text = animations().toString();
        assertFalse(text.contains("ysm."), "动画里有 ysm.* 变量");
    }

    /** 死亡动画（维护者在 Blockbench 里做的）播完停在最后一帧，接在受击动画之后。 */
    @Test
    void deathHoldsItsLastFrame() throws Exception {
        JsonObject death = animations().getAsJsonObject("death");
        assertEquals("hold_on_last_frame", death.get("loop").getAsString());
        assertEquals(1.55, death.get("animation_length").getAsDouble(), 1e-6);
        assertTrue(animations().has("attacked"));
    }

    /** 自绘的循环动画首尾一致，避免循环接缝处抽一下。 */
    @Test
    void drawnLoopsStartAndEndOnTheSamePose() throws Exception {
        JsonObject hold = animations().getAsJsonObject("hold_mainhand:bow");
        for (Map.Entry<String, JsonElement> bone : hold.getAsJsonObject("bones").entrySet()) {
            JsonObject frames = bone.getValue().getAsJsonObject().getAsJsonObject("rotation");
            String first = frames.keySet().iterator().next(), last = null;
            for (String k : frames.keySet()) last = k;
            assertEquals(frames.get(first), frames.get(last), bone.getKey());
        }
    }
}
