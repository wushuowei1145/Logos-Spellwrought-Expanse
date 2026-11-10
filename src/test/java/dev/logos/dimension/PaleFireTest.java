package dev.logos.dimension;

import dev.logos.dimension.client.longsword.PaleFire;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 流火刀身的狐火淡一点：带记号的色相转出来的颜色饱和度乘 0.55，色相、明度不变；不带记号的不碰。 */
class PaleFireTest {
    private static float[] hsv(int rgb) {
        float r = (rgb >> 16 & 255) / 255f, g = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h = d == 0 ? 0 : max == r ? (g - b) / d : max == g ? 2 + (b - r) / d : 4 + (r - g) / d;
        h = (h / 6 + 1) % 1;
        return new float[] {h, max == 0 ? 0 : d / max, max};
    }

    @Test void marksAreRecognisedAndRemoved() {
        float hue = PaleFire.hue(.85f);
        assertTrue(PaleFire.marked(hue));
        assertFalse(PaleFire.marked(.85f));
        assertEquals(.85f, PaleFire.unmarked(hue), 1e-6);
        // 记号是整数，只按小数部分用的色相不变
        assertEquals(.85f, hue - (float) Math.floor(hue), 1e-5);
    }

    @Test void paleKeepsHueAndValue() {
        int pink = 0xFF3FB0;
        float[] before = hsv(pink), after = hsv(PaleFire.pale(pink));
        assertEquals(before[0], after[0], .02, "色相变了");
        assertEquals(before[2], after[2], .01, "明度变了");
        assertEquals(before[1] * PaleFire.SATURATION, after[1], .02, "饱和度没有按比例降");
        assertEquals(0xFFFFFF, PaleFire.pale(0xFFFFFF), "白色不变");
    }
}
