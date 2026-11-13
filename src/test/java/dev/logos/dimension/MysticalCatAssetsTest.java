package dev.logos.dimension;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.logos.dimension.npc.MysticalCat;
import dev.logos.dimension.npc.NpcParts;
import net.minecraft.util.RandomSource;

/** 灵猫的模型、零件表、各毛色贴图与动画文件（tools/mystical_cat_assets.py 导入）的静态检查。 */
class MysticalCatAssetsTest {
    private static final String ASSETS = "/assets/logos_dimension/";
    private static final NpcParts.Race CAT = NpcParts.MYSTICAL_CAT;

    private static JsonObject read(String path) throws Exception {
        try (var in = MysticalCatAssetsTest.class.getResourceAsStream(ASSETS + path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static BufferedImage image(String path) throws Exception {
        try (var in = MysticalCatAssetsTest.class.getResourceAsStream(ASSETS + path)) {
            assertNotNull(in, path);
            return ImageIO.read(in);
        }
    }

    private static Set<String> modelBones() throws Exception {
        Set<String> bones = new HashSet<>();
        for (JsonElement b : read("geckolib/models/entity/mystical_cat.geo.json").getAsJsonArray("minecraft:geometry").get(0)
                .getAsJsonObject().getAsJsonArray("bones")) bones.add(b.getAsJsonObject().get("name").getAsString());
        return bones;
    }

    @Test
    void partGroupsExistAndRandomLooksRoundTrip() throws Exception {
        Set<String> bones = modelBones();
        for (var slot : CAT.slots()) for (var part : slot.parts()) for (String g : part.groups())
            assertTrue(bones.contains(g), "模型里缺少零件组 " + g);
        RandomSource random = RandomSource.create(11);
        int twin = 0;
        for (int i = 0; i < 400; i++) {
            Map<String, String> look = MysticalCat.randomAppearance(random);
            assertEquals(look, CAT.sanitize(NpcParts.decode(NpcParts.encode(look))));
            if (MysticalCat.TWIN_TAIL.equals(look.get("tails"))) twin++;
        }
        // 猫又约 8%：400 只里在 10 到 60 只之间
        assertTrue(twin > 10 && twin < 60, "双尾猫又的数量 " + twin);
    }

    /** 每种毛色一张贴图：和默认贴图同样大小、同样的透明像素（网格的背面剔除按默认贴图算）。 */
    @Test
    void everyCoatHasATextureWithTheSameAlpha() throws Exception {
        BufferedImage base = image("textures/entity/mystical_cat.png");
        for (String coat : MysticalCat.COATS) {
            BufferedImage img = image("textures/entity/mystical_cat/" + coat + ".png");
            assertEquals(base.getWidth(), img.getWidth(), coat);
            assertEquals(base.getHeight(), img.getHeight(), coat);
            for (int y = 0; y < base.getHeight(); y++)
                for (int x = 0; x < base.getWidth(); x++)
                    assertEquals(base.getRGB(x, y) >>> 24 > 0, img.getRGB(x, y) >>> 24 > 0, coat + " @" + x + "," + y);
        }
    }

    /** 动画只用模型里有的骨骼；坐姿、剧情动作和三层常驻叠加层都在。 */
    @Test
    void animationsUseOnlyModelBones() throws Exception {
        Set<String> bones = modelBones();
        JsonObject anims = read("geckolib/animations/entity/mystical_cat.animation.json").getAsJsonObject("animations");
        for (String name : List.of("idle", "walk", "sit", "greet", "refuse", "stretch", "wash", "purr", "pre_parallel0", "pre_parallel1", "pre_parallel2"))
            assertTrue(anims.has(name), "缺少动画 " + name);
        for (var e : anims.entrySet())
            for (String bone : e.getValue().getAsJsonObject().getAsJsonObject("bones").keySet())
                assertTrue(bones.contains(bone), e.getKey() + " 用到模型里没有的骨骼 " + bone);
    }

    @Test
    void happyExpressionReplacesEyesAndMouth() {
        var happy = CAT.expressions().stream().filter(e -> e.id().equals(MysticalCat.HAPPY)).findFirst().orElseThrow();
        assertEquals(List.of("Eyelid", "Mouth"), happy.hides());
    }
}
