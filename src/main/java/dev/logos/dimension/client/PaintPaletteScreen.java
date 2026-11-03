package dev.logos.dimension.client;

import dev.logos.dimension.net.PaintColorPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 调色盘：独立全屏调配页。16×16 共 256 色（HSV 采样），点击选中并写回服务端，
 * 顶部色块与十六进制实时显示当前颜色。配色与钱囊等界面一致：墨色面板、金色描边。
 */
public class PaintPaletteScreen extends Screen {
    private static final int GRID = 16;
    private static final int CELL = 14;
    private static final int PANEL_FILL = 0xE0161218;
    private static final int PANEL_EDGE = 0xFFB89A5A;
    private static final int TITLE_COLOR = 0xFFE8C77A;
    private static final int TEXT_COLOR = 0xFFE8E0D0;
    private static final int HINT_COLOR = 0xFF8C8478;
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
        return GRID * CELL + 24;
    }

    private static int panelHeight() {
        return GRID * CELL + 96;
    }

    @Override
    protected void init() {
        x0 = (this.width - panelWidth()) / 2;
        y0 = (this.height - panelHeight()) / 2;
        gridLeft = x0 + 12;
        gridTop = y0 + 56;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int panelW = panelWidth(), panelH = panelHeight();
        graphics.fill(x0, y0, x0 + panelW, y0 + panelH, PANEL_FILL);
        graphics.outline(x0, y0, panelW, panelH, PANEL_EDGE);
        graphics.text(this.font, this.title, x0 + 10, y0 + 8, TITLE_COLOR);

        // 当前颜色：色块 + 十六进制
        int sw = 96, sh = 22, sx = x0 + 12, sy = y0 + 26;
        graphics.fill(sx, sy, sx + sw, sy + sh, 0xFF000000 | previewColor);
        graphics.outline(sx, sy, sw, sh, PANEL_EDGE);
        graphics.text(this.font, Component.literal(String.format("#%06X", previewColor & 0xFFFFFF)),
                sx + sw + 10, sy + 7, TEXT_COLOR);

        // 256 色网格
        for (int i = 0; i < PALETTE.size(); i++) {
            int x = gridLeft + (i % GRID) * CELL;
            int y = gridTop + (i / GRID) * CELL;
            graphics.fill(x, y, x + CELL - 1, y + CELL - 1, 0xFF000000 | PALETTE.get(i));
        }
        graphics.outline(gridLeft - 1, gridTop - 1, GRID * CELL + 2, GRID * CELL + 2, PANEL_EDGE);
        if (selected >= 0) {
            int x = gridLeft + (selected % GRID) * CELL;
            int y = gridTop + (selected / GRID) * CELL;
            graphics.outline(x - 1, y - 1, CELL + 1, CELL + 1, 0xFFFFFFFF);
        }

        graphics.text(this.font, Component.literal("点击颜色完成调配"),
                x0 + 10, y0 + panelH - 16, HINT_COLOR);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
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
