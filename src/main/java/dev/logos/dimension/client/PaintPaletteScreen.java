package dev.logos.dimension.client;

import dev.logos.dimension.net.PaintColorPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 调色盘：独立全屏调配页，外观沿用 Logos 的法术编辑器羊皮纸（木框 + 黄铜角件）。
 * 16×16 共 256 色（HSV 采样），点击选中并写回服务端，顶部色块与十六进制实时显示当前颜色。
 */
public class PaintPaletteScreen extends Screen {
    private static final int GRID = 16;
    private static final int CELL = 13;
    /** 内边距避开羊皮纸的木框与黄铜角件；整体要能放进 640×360 的逻辑分辨率。 */
    private static final int PANEL_MARGIN_X = 44;
    private static final int HEADER = 84;
    private static final int FOOTER = 44;
    /** Logos 法术编辑器的羊皮纸背景（附属要求：已有贴图用 logos: 路径引用）。 */
    private static final Identifier PARCHMENT =
        Identifier.fromNamespaceAndPath("logos", "textures/gui/spell_editor_parchment.png");
    private static final int TEX_W = 1672, TEX_H = 941;
    private static final int[] SRC_X = {0, 128, 1544, 1672};
    private static final int[] SRC_Y = {0, 128, 813, 941};
    private static final int INK = 0xFF3A2414;
    private static final int INK_TITLE = 0xFF5A3410;
    private static final int INK_HINT = 0xFF7A5A3A;
    private static final int BRASS = 0xFF9C7A30;
    private static final List<Integer> PALETTE = buildPalette();

    private final Player player;
    private final InteractionHand hand;
    private int x0, y0, gridLeft, gridTop;
    private int previewColor;
    private int selected;

    public PaintPaletteScreen(Player player, InteractionHand hand, ItemStack stack) {
        super(Component.literal("调色盘"));
        this.player = player;
        this.hand = hand;
        this.previewColor = stack.getOrDefault(
                dev.logos.dimension.paint.PaintSystem.PAINT_COLOR.get(),
                dev.logos.dimension.paint.PaintSurfaceBlockEntity.DEFAULT_COLOR);
        this.selected = PALETTE.indexOf(previewColor & 0xFFFFFF);
    }

    private static int panelWidth() {
        return GRID * CELL + PANEL_MARGIN_X * 2;
    }

    private static int panelHeight() {
        return HEADER + GRID * CELL + FOOTER;
    }

    @Override
    protected void init() {
        x0 = (this.width - panelWidth()) / 2;
        y0 = (this.height - panelHeight()) / 2;
        gridLeft = x0 + PANEL_MARGIN_X;
        gridTop = y0 + HEADER;
    }

    /** 与 Logos 的 SpellEditorBackground 相同的九宫格：木框、黄铜角件不被拉伸。 */
    private static void parchment(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        int corner = Math.min(40, Math.min(w, h) / 2);
        int[] dx = {x, x + corner, x + w - corner, x + w};
        int[] dy = {y, y + corner, y + h - corner, y + h};
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                g.blit(PARCHMENT, dx[col], dy[row], dx[col + 1], dy[row + 1],
                    SRC_X[col] / (float) TEX_W, SRC_X[col + 1] / (float) TEX_W,
                    SRC_Y[row] / (float) TEX_H, SRC_Y[row + 1] / (float) TEX_H);
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        parchment(graphics, x0, y0, panelWidth(), panelHeight());
        graphics.text(this.font, this.title, x0 + PANEL_MARGIN_X, y0 + 38, INK_TITLE);

        // 当前颜色：色块 + 十六进制
        int sw = 96, sh = 22, sx = x0 + PANEL_MARGIN_X, sy = y0 + 56;
        graphics.fill(sx - 1, sy - 1, sx + sw + 1, sy + sh + 1, INK);
        graphics.fill(sx, sy, sx + sw, sy + sh, 0xFF000000 | previewColor);
        graphics.text(this.font, Component.literal(String.format("#%06X", previewColor & 0xFFFFFF)),
                sx + sw + 10, sy + 7, INK);

        // 256 色网格（细墨线分隔，选中格黄铜边框）
        for (int i = 0; i < PALETTE.size(); i++) {
            int x = gridLeft + (i % GRID) * CELL;
            int y = gridTop + (i / GRID) * CELL;
            graphics.fill(x, y, x + CELL - 1, y + CELL - 1, 0xFF000000 | PALETTE.get(i));
        }
        graphics.outline(gridLeft - 1, gridTop - 1, GRID * CELL + 2, GRID * CELL + 2, INK);
        if (selected >= 0) {
            int x = gridLeft + (selected % GRID) * CELL;
            int y = gridTop + (selected / GRID) * CELL;
            graphics.outline(x - 1, y - 1, CELL + 1, CELL + 1, BRASS);
        }

        graphics.text(this.font, Component.literal("点击颜色完成调配"),
                x0 + PANEL_MARGIN_X, y0 + panelHeight() - 34, INK_HINT);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x(), mouseY = event.y();
        if (event.button() == 0 && mouseX >= gridLeft && mouseY >= gridTop
                && mouseX < gridLeft + GRID * CELL && mouseY < gridTop + GRID * CELL) {
            int cx = (int) ((mouseX - gridLeft) / CELL);
            int cy = (int) ((mouseY - gridTop) / CELL);
            int index = cy * GRID + cx;
            if (index >= 0 && index < PALETTE.size()) {
                selected = index;
                previewColor = PALETTE.get(index);
                ClientPacketDistributor.sendToServer(new PaintColorPayload(0xFF000000 | previewColor, hand.ordinal()));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static List<Integer> buildPalette() {
        List<Integer> colors = new ArrayList<>(256);
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                float hue = x / (float) GRID;
                float sat = 0.85f;
                float val = 1.0f - y / (float) GRID;
                colors.add(hsv(hue, sat, Math.max(0.15f, val)));
            }
        }
        return colors;
    }

    private static int hsv(float h, float s, float v) {
        int i = (int) (h * 6) % 6;
        float f = h * 6 - (int) (h * 6);
        float p = v * (1 - s);
        float q = v * (1 - f * s);
        float t = v * (1 - (1 - f) * s);
        float r = 0, g = 0, b = 0;
        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            case 5 -> { r = v; g = p; b = q; }
        }
        int ri = Math.round(r * 255), gi = Math.round(g * 255), bi = Math.round(b * 255);
        return (ri << 16) | (gi << 8) | bi;
    }
}
