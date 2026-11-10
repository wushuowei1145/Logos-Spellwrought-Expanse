package dev.logos.dimension.client.music;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** 乐器界面的羊皮纸背景：直接引用 Logos 法术编辑器的贴图，九宫格拉伸（木框、黄铜角件不被拉伸）。 */
final class Parchment {
    static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("logos", "textures/gui/spell_editor_parchment.png");
    private static final int TEX_W = 1672, TEX_H = 941;
    private static final int[] SRC_X = {0, 128, 1544, 1672};
    private static final int[] SRC_Y = {0, 128, 813, 941};

    static final int INK = 0xFF3A2414;
    static final int INK_TITLE = 0xFF5A3410;
    static final int INK_HINT = 0xFF7A5A3A;
    static final int BRASS = 0xFF9C7A30;
    static final int GOLD = 0xFFE0B84A;

    private Parchment() {}

    static void draw(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        int corner = Math.min(40, Math.min(w, h) / 2);
        int[] dx = {x, x + corner, x + w - corner, x + w};
        int[] dy = {y, y + corner, y + h - corner, y + h};
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                g.blit(TEXTURE, dx[col], dy[row], dx[col + 1], dy[row + 1],
                    SRC_X[col] / (float) TEX_W, SRC_X[col + 1] / (float) TEX_W,
                    SRC_Y[row] / (float) TEX_H, SRC_Y[row + 1] / (float) TEX_H);
            }
        }
    }
}
