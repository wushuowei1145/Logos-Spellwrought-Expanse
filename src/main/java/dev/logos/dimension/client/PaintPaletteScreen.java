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
 * 调色盘：独立全屏调配页。16×16 = 256 色（HSV 采样），点击选中并写回服务端，
 * 顶部一块大预览区实时显示当前颜色。
 */
public class PaintPaletteScreen extends Screen {
    private static final int GRID = 16;
    private static final int CELL = 14;
    private static final List<Integer> PALETTE = buildPalette();

    private final Player player;
    private final InteractionHand hand;
    private int gridLeft, gridTop;
    private int previewColor;

    public PaintPaletteScreen(Player player, InteractionHand hand, ItemStack stack) {
        super(Component.literal("调色盘"));
        this.player = player;
        this.hand = hand;
        this.previewColor = stack.getOrDefault(
                dev.logos.dimension.paint.PaintSystem.PAINT_COLOR.get(),
                dev.logos.dimension.paint.PaintSurfaceBlockEntity.DEFAULT_COLOR);
    }

    @Override
    protected void init() {
        gridLeft = (this.width - GRID * CELL) / 2;
        gridTop = (this.height - GRID * CELL) / 2 + 20;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);
        graphics.text(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 16, 0xFFFFFF);
        // 预览区
        int previewW = 80, previewH = 24;
        int px = (this.width - previewW) / 2, py = 36;
        graphics.fill(px, py, px + previewW, py + previewH, 0xFF000000 | previewColor);
        graphics.outline(px, py, previewW, previewH, 0xFFFFFFFF);
        // 256 色网格
        for (int i = 0; i < PALETTE.size(); i++) {
            int x = gridLeft + (i % GRID) * CELL;
            int y = gridTop + (i / GRID) * CELL;
            graphics.fill(x, y, x + CELL - 1, y + CELL - 1, 0xFF000000 | PALETTE.get(i));
        }
        graphics.text(this.font, "点击颜色完成调配", 8, this.height - 14, 0xAAAAAA);
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
