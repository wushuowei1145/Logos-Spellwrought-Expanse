package dev.logos.dimension;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.logos.dimension.tarot.TarotCards;
import java.awt.image.BufferedImage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** 二十二张塔罗牌的贴图规格和模型层次：tools/tarot_art 输出的静态检查。 */
class TarotAssetsTest {
    private static final String ROOT = "/assets/logos_dimension/";

    private static BufferedImage image(String path) throws Exception {
        try (var in = TarotAssetsTest.class.getResourceAsStream(ROOT + path)) {
            assertNotNull(in, "缺少贴图：" + path);
            return ImageIO.read(in);
        }
    }

    private static JsonObject json(String path) throws Exception {
        try (var in = TarotAssetsTest.class.getResourceAsStream(ROOT + path)) {
            assertNotNull(in, "缺少资源：" + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** 统计不透明像素；只允许全透明或全不透明。 */
    private static int opaque(BufferedImage im, String name) {
        int count = 0;
        for (int y = 0; y < im.getHeight(); y++) for (int x = 0; x < im.getWidth(); x++) {
            int a = im.getRGB(x, y) >>> 24;
            assertTrue(a == 0 || a == 255, name + " 有半透明像素 (" + x + "," + y + ")");
            if (a == 255) count++;
        }
        return count;
    }

    @Test
    void everyCardHasOpaqueArtworkGlowAndLabels() throws Exception {
        assertEquals(22, TarotCards.MAJOR_ARCANA.size());
        for (String id : TarotCards.MAJOR_ARCANA) {
            var art = image("textures/item/tarot/major/" + id + ".png");
            assertEquals(112, art.getWidth(), id);
            assertEquals(192, art.getHeight(), id);
            assertEquals(112 * 192, opaque(art, id), id + " 牌画必须完全不透明");

            var glow = image("textures/item/tarot/major/" + id + "_glow.png");
            assertEquals(112, glow.getWidth(), id);
            assertEquals(192, glow.getHeight(), id);
            int lit = opaque(glow, id + "_glow");
            assertTrue(lit > 0 && lit <= 112 * 192 * 0.12, id + " 发光像素数不合理：" + lit);

            var labels = image("textures/item/tarot/major/" + id + "_labels.png");
            assertEquals(128, labels.getWidth(), id);
            assertEquals(224, labels.getHeight(), id);
            assertTrue(opaque(labels, id + "_labels") > 0, id + " 牌名层是空的");

            var model = json("models/item/tarot/major/" + id + ".json");
            assertEquals("logos_dimension:item/tarot/card_blank", model.get("parent").getAsString());
            assertEquals("logos_dimension:item/tarot/major/" + id, model.getAsJsonObject("textures").get("artwork").getAsString());
            assertEquals("logos_dimension:item/tarot/major/" + id, json("items/tarot_" + id + ".json").getAsJsonObject("model").get("model").getAsString());
        }
    }

    /** 发光层夹在牌画和牌框之间，并且只有这一层发光。 */
    @Test
    void glowLayerSitsBetweenArtworkAndFrame() throws Exception {
        double art = Double.NaN, glow = Double.NaN, frame = Double.NaN;
        for (var element : json("models/item/tarot/card_blank.json").getAsJsonArray("elements")) {
            var e = element.getAsJsonObject();
            double z = e.getAsJsonArray("from").get(2).getAsDouble();
            switch (e.get("name").getAsString()) {
                case "replaceable_artwork_112x192" -> { art = z; assertFalse(e.has("light_emission")); }
                case "artwork_glow" -> { glow = z; assertEquals(15, e.get("light_emission").getAsInt()); }
                case "front_frame" -> frame = z;
                default -> assertFalse(e.has("light_emission"), e.get("name").getAsString());
            }
        }
        assertTrue(art > glow && glow > frame, "牌面朝 -Z，从里到外应是牌画、发光层、牌框");
    }

    @Test
    void cardBackAndFrameKeepTheirSharedSize() throws Exception {
        assertEquals(128, image("textures/item/tarot/frame.png").getWidth());
        assertEquals(224, image("textures/item/tarot/frame.png").getHeight());
        assertEquals(128, image("textures/item/tarot/back.png").getWidth());
        assertEquals(224, image("textures/item/tarot/back.png").getHeight());
    }
}
