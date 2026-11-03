package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.Style;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextArea;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;
import dev.logos.dimension.cloudsea.EmaItem;
import dev.logos.dimension.cloudsea.EmaRackBlockEntity;
import dev.logos.dimension.cloudsea.EmaWish;
import dev.logos.dimension.net.EmaWritePayload;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

/**
 * 绘马页：照实物画出的一块桧木绘马——五角屋形板、两个绳孔穿着白编绳、右侧竖写「願意」，左边空白处写愿望。
 * 与 Logos 的界面一样用 LDLib2 搭，板面、木纹、绳子全部用代码绘制，不用贴图。
 *
 * <p>手持绘马右键是书写页，合上时像书与笔一样把文字存回物品；右键绘马挂是翻看页，只读，可在挂着的几块间翻动。
 */
public final class EmaScreen {
    /** 板面尺寸（含屋顶）、屋顶高、板上方留给绳圈的高度。比例取自实物约 1.6 : 1。 */
    private static final int W = 320, H = 200, ROOF = 20, CORD = 48;
    /** 书写区：板面左侧约四分之三，避开右边的「願意」。 */
    private static final int AX = 22, AY = ROOF + 20, AW = 222, AH = 112;
    private static final float FONT_SIZE = 9, LINE_SPACING = 3;
    private static final int WOOD = 0xFFEAD5A8, WOOD_EDGE = 0xFFC4A472, WOOD_RIM = 0x40705030, WOOD_LIGHT = 0x50FFF6DE;
    private static final int GRAIN = 0x8F6B3E;
    private static final int HOLE = 0xFF2E241A, HOLE_RIM = 0xFFB89868;
    private static final int CORD_CORE = 0xFFF3F3F5, CORD_EDGE = 0xFFA9A9B2, CORD_BRAID = 0xFFC9C9D2;
    private static final int PRINT = 0xFF2A2A2A;
    private static final int INK = 0xFF2A2119, INK_HINT = 0xFF7A6040;

    /** 书写页记下是哪只手；翻看页为空。 */
    private final @Nullable InteractionHand hand;
    private final List<EmaWish> wishes;
    private int page;
    private final String original;
    private final TextArea area = new TextArea();
    private final Label signature = new Label();
    private final Label pageLabel = new Label();
    /** 翻看页换页时才放行写入，玩家自己打不进字。 */
    private boolean filling;
    private final List<float[]> grain = grain();
    private final List<Vector2f> cord = cord();

    private EmaScreen(@Nullable InteractionHand hand, List<EmaWish> wishes, int page) {
        this.hand = hand;
        this.wishes = wishes;
        this.page = page;
        this.original = wishes.get(page).text();
    }

    /** 手持绘马右键：打开书写页。签名只用通用类型，供 EmaItem 在客户端调用。 */
    public static void openWrite(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        EmaWish wish = stack.getOrDefault(EmaItem.WISH.get(), new EmaWish("", ""));
        new EmaScreen(hand, List.of(new EmaWish(wish.text(), player.getName().getString())), 0).open();
    }

    /** 右键绘马挂：从指着的那块翻起，看挂着的每一块。 */
    public static void openRack(BlockPos pos, int slot) {
        var level = Minecraft.getInstance().level;
        if (level == null || !(level.getBlockEntity(pos) instanceof EmaRackBlockEntity rack)) return;
        List<EmaWish> hung = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < EmaRackBlockEntity.SLOTS; i++) {
            ItemStack stack = rack.getItems().get(i);
            if (stack.isEmpty()) continue;
            if (i == slot) start = hung.size();
            EmaWish wish = stack.get(EmaItem.WISH.get());
            Component name = stack.get(DataComponents.CUSTOM_NAME);
            hung.add(wish != null ? wish : new EmaWish(name == null ? "" : name.getString(), ""));
        }
        if (!hung.isEmpty()) new EmaScreen(null, hung, start).open();
    }

    private void open() {
        ModularUI ui = createUI();
        // 写字时按 E 不能把页面关掉
        ui.shouldCloseOnKeyInventory(false);
        Minecraft.getInstance().setScreen(new ModularUIScreen(ui, Component.translatable("item.logos_dimension.journey_ema")) {
            @Override
            public void removed() {
                super.removed();
                save();
            }
        });
    }

    /** 像书与笔：合上就存；没改动不发。 */
    private void save() {
        if (hand == null) return;
        String text = String.join("\n", area.getValue()).strip();
        if (!text.equals(original.strip())) ClientPacketDistributor.sendToServer(new EmaWritePayload(hand.ordinal(), text));
    }

    private ModularUI createUI() {
        boolean writing = hand != null;
        Font font = Minecraft.getInstance().font;
        float scale = FONT_SIZE / font.lineHeight;

        area.setId("ema_text");
        // 字直接写在木板上：去掉样式表给书写区内容框的深色底
        Style.importantPipeline(area.getStyle(), s -> s.backgroundTexture(IGuiTexture.EMPTY));
        Style.importantPipeline(area.contentView.getStyle(), s -> s.background(IGuiTexture.EMPTY));
        Style.importantPipeline(area.getTextAreaStyle(), s -> s.textColor(INK).cursorColor(INK).textShadow(false)
                .fontSize(FONT_SIZE).lineSpacing(LINE_SPACING).focusOverlay(IGuiTexture.EMPTY)
                .horizontalScrollDisplay(ScrollDisplay.NEVER).verticalScrollDisplay(ScrollDisplay.NEVER)
                .placeholder(writing ? Component.translatable("gui.logos_dimension.ema.placeholder") : Component.empty()));
        area.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(AX).top(CORD + AY).width(AW).height(AH));
        area.setTextValidator(lines -> (writing || filling) && lines.length <= EmaWish.MAX_LINES
                && Arrays.stream(lines).mapToInt(String::length).sum() <= EmaWish.MAX_CHARS
                && Arrays.stream(lines).allMatch(line -> font.width(line) * scale <= AW - 6));
        if (!writing) area.setFocusable(false);

        pageLabel.setId("ema_page");
        signature.textStyle(s -> s.textColor(INK_HINT).textShadow(false).fontSize(9));
        signature.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(AX).top(CORD + H - 26).width(AW).height(10));

        UIElement board = new UIElement();
        board.layout(l -> l.width(W).height(CORD + H));
        board.style(s -> s.backgroundTexture((GuiTexture) this::drawBoard));
        board.addChildren(area, signature);

        UIElement controls = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(10).alignItems(AlignItems.CENTER).height(18));
        if (writing) {
            controls.addChild(tag("ema_done", Component.translatable("gui.logos_dimension.ema.done"), () -> Minecraft.getInstance().setScreen(null)));
        } else if (wishes.size() > 1) {
            pageLabel.textStyle(s -> s.textColor(0xFFF0E2C0).textShadow(true)
                    .textAlignHorizontal(com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal.CENTER));
            pageLabel.layout(l -> l.width(36).height(10));
            controls.addChildren(tag("ema_prev", Component.literal("‹"), () -> turn(-1)), pageLabel, tag("ema_next", Component.literal("›"), () -> turn(1)));
        }

        UIElement root = new UIElement();
        root.layout(l -> l.widthPercent(100).heightPercent(100).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER).gapAll(6));
        root.addChildren(board, controls);
        if (writing) {
            // 打开就落笔：等界面挂上树后再给书写区焦点
            root.addEventListener(UIEvents.TICK, ev -> {
                if (!area.isFocused() && area.getModularUI() != null && area.getModularUI().getFocusedElement() == null) area.focus();
            });
        }
        show();
        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)));
    }

    private void turn(int step) {
        page = Math.floorMod(page + step, wishes.size());
        show();
    }

    private void show() {
        EmaWish wish = wishes.get(page);
        filling = true;
        area.setValue(wish.text().split("\n", -1), false);
        filling = false;
        signature.setText(wish.author().isEmpty() ? Component.empty() : Component.literal("—— " + wish.author()));
        pageLabel.setText(Component.literal((page + 1) + " / " + wishes.size()));
    }

    /** 小木牌按钮：与绘马同一种木色，悬停时浅一些。 */
    private static Button tag(String id, Component text, Runnable action) {
        Button b = new Button().setText(text);
        b.setId(id);
        b.setOnClick(ev -> action.run());
        b.textStyle(s -> s.textColor(INK).textShadow(false));
        b.layout(l -> l.minWidth(22).height(16).paddingHorizontal(8));
        Style.importantPipeline(b.getButtonStyle(), s -> s.baseTexture(plank(WOOD)).hoverTexture(plank(0xFFF6E6C2)).pressedTexture(plank(WOOD_EDGE)));
        return b;
    }

    private static GuiTexture plank(int face) {
        return (ctx, x, y, w, h) -> {
            DrawerHelperClient.drawSolidRect(ctx, x + 1, y + 2, w, h - 1, 0x50000000);
            DrawerHelperClient.drawSolidRect(ctx, x, y, w, h, WOOD_EDGE);
            DrawerHelperClient.drawSolidRect(ctx, x, y, w, h - 2, face);
            DrawerHelperClient.drawSolidRect(ctx, x, y, w, 1, WOOD_LIGHT);
        };
    }

    private void drawBoard(GUIContext ctx, float x, float y, float w, float h) {
        float bx = x, by = y + CORD;
        // 投影、板厚（下沿露出的木口）、板面
        shape(ctx, bx + 3, by + 5, 0x38000000);
        shape(ctx, bx, by + 3, WOOD_EDGE);
        shape(ctx, bx, by, WOOD);
        for (float[] g : grain) {
            float gy = g[0], from = g[1], to = g[2];
            float half = gy < ROOF ? W / 2f * gy / ROOF : W / 2f;
            from = Math.max(from, W / 2f - half + 1);
            to = Math.min(to, W / 2f + half - 1);
            if (to > from) DrawerHelperClient.drawSolidRect(ctx, bx + from, by + gy, to - from, g[3], (int) g[4] << 24 | GRAIN);
        }
        // 屋顶两坡与左沿受光，右沿与底沿压暗
        DrawerHelperClient.drawLines(ctx, List.of(new Vector2f(bx, by + ROOF), new Vector2f(bx + W / 2f, by), new Vector2f(bx + W, by + ROOF)), WOOD_LIGHT, WOOD_LIGHT, 1);
        DrawerHelperClient.drawSolidRect(ctx, bx, by + ROOF, 1, H - ROOF, WOOD_LIGHT);
        DrawerHelperClient.drawSolidRect(ctx, bx + W - 1, by + ROOF, 1, H - ROOF, WOOD_RIM);
        DrawerHelperClient.drawSolidRect(ctx, bx, by + H - 1, W, 1, WOOD_RIM);

        float holeY = by + ROOF + 7;
        float left = bx + W * 0.41f, right = bx + W * 0.59f;
        disc(ctx, left, holeY, 4.5f, HOLE_RIM);
        disc(ctx, left, holeY, 3.5f, HOLE);
        disc(ctx, right, holeY, 4.5f, HOLE_RIM);
        disc(ctx, right, holeY, 3.5f, HOLE);

        List<Vector2f> path = new ArrayList<>(cord.size());
        for (Vector2f p : cord) path.add(new Vector2f(bx + p.x, by + p.y));
        DrawerHelperClient.drawLines(ctx, path, CORD_EDGE, CORD_EDGE, 6);
        DrawerHelperClient.drawLines(ctx, path, CORD_CORE, CORD_CORE, 4);
        // 编绳的斜纹
        for (int i = 2; i + 1 < path.size(); i += 3) {
            Vector2f a = path.get(i), b = path.get(i + 1);
            float dx = b.x - a.x, dy = b.y - a.y, len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-3f) continue;
            float nx = -dy / len * 1.6f, ny = dx / len * 1.6f;
            DrawerHelperClient.drawLines(ctx, List.of(new Vector2f(a.x - nx - dx * .3f, a.y - ny - dy * .3f), new Vector2f(a.x + nx + dx * .3f, a.y + ny + dy * .3f)),
                    CORD_BRAID, CORD_BRAID, 1);
        }

        // 右侧竖写「願意」
        Font font = Minecraft.getInstance().font;
        float s = 2.75f, cx = bx + W * 0.865f;
        String[] chars = {"願", "意"};
        float[] cy = {by + H * 0.40f, by + H * 0.60f};
        for (int i = 0; i < chars.length; i++) {
            DrawerHelperClient.drawText(ctx, chars[i], cx - font.width(chars[i]) * s / 2f, cy[i] - font.lineHeight * s / 2f, s, PRINT, false);
        }
    }

    /** 五角屋形板：屋顶按半像素一行铺出两坡，下面是整块长方形。 */
    private static void shape(GUIContext ctx, float x, float y, int color) {
        for (float row = 0; row < ROOF; row += 0.5f) {
            float half = W / 2f * (row + 0.5f) / ROOF;
            DrawerHelperClient.drawSolidRect(ctx, x + W / 2f - half, y + row, half * 2, 0.5f, color);
        }
        DrawerHelperClient.drawSolidRect(ctx, x, y + ROOF, W, H - ROOF, color);
    }

    private static void disc(GUIContext ctx, float cx, float cy, float r, int color) {
        for (float dy = -r; dy < r; dy += 0.5f) {
            float half = (float) Math.sqrt(Math.max(0, r * r - (dy + 0.25f) * (dy + 0.25f)));
            DrawerHelperClient.drawSolidRect(ctx, cx - half, cy + dy, half * 2, 0.5f, color);
        }
    }

    /** 桧木的细直纹：长短不一的横向纹线，轻轻起伏；每条拆成短段，存 {y, 起点, 终点, 粗细, 不透明度}。 */
    private static List<float[]> grain() {
        Random random = new Random(0x5EEDL);
        List<float[]> out = new ArrayList<>();
        for (float y = 1.5f; y < H - 2; y += 1.5f + random.nextFloat() * 3.5f) {
            float start = -20 + random.nextFloat() * W * 0.6f;
            float end = Math.min(W + 20, start + 60 + random.nextFloat() * W);
            float phase = random.nextFloat() * 6.28f, amp = 0.4f + random.nextFloat() * 0.8f;
            float alpha = 0x18 + random.nextFloat() * 0x30, thick = random.nextFloat() < 0.25f ? 1f : 0.5f;
            for (float sx = start; sx < end; sx += 16) {
                float gy = y + (float) Math.sin(phase + sx / 47f) * amp;
                if (gy >= 0 && gy < H - 1) out.add(new float[] {gy, sx, Math.min(end, sx + 16), thick, alpha});
            }
        }
        return out;
    }

    /** 白编绳：从左孔穿出、向左上绕一个松圈，再落进右孔（Catmull-Rom 过几处控制点，坐标相对板面左上）。 */
    private static List<Vector2f> cord() {
        float holeY = ROOF + 7;
        Vector2f[] points = {
                new Vector2f(W * 0.41f, holeY), new Vector2f(W * 0.405f, -4), new Vector2f(W * 0.36f, -30),
                new Vector2f(W * 0.42f, -42), new Vector2f(W * 0.52f, -27), new Vector2f(W * 0.585f, -6),
                new Vector2f(W * 0.59f, holeY)};
        List<Vector2f> out = new ArrayList<>();
        for (int i = 0; i < points.length - 1; i++) {
            Vector2f p0 = points[Math.max(0, i - 1)], p1 = points[i], p2 = points[i + 1], p3 = points[Math.min(points.length - 1, i + 2)];
            for (int k = 0; k < 8; k++) {
                float t = k / 8f, t2 = t * t, t3 = t2 * t;
                out.add(new Vector2f(
                        0.5f * (2 * p1.x + (-p0.x + p2.x) * t + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3),
                        0.5f * (2 * p1.y + (-p0.y + p2.y) * t + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3)));
            }
        }
        out.add(points[points.length - 1]);
        return out;
    }
}
