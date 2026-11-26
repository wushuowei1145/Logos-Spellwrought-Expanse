package dev.logos.dimension.firearm;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import dev.logos.ui.AlchemyArt;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;

/**
 * 铸弹台的界面：和 Logos 的魔力器具一样是工作台上摊开的一页账本。
 * 弹壳、火药、弹头三格，压制进度，出货格；下面一行写正在压什么。
 */
final class AmmoBenchUI {
    private static final Identifier MANUSCRIPT = Identifier.fromNamespaceAndPath("logos", "lss/manuscript.lss");
    /** 进度条：黄铜色。 */
    private static final int BRASS = 0xFFD2A24C;
    /** 格子名字那一列的宽度：放得下 "Powder" 这样的英文。 */
    private static final int LABEL_WIDTH = 40;

    private AmmoBenchUI() {}

    static ModularUI create(BlockUIMenuType.BlockUIHolder holder) {
        var player = holder.player;
        if (!(player.level().getBlockEntity(holder.pos) instanceof AmmoBenchBlockEntity bench)) return ModularUI.of(UI.of(new UIElement()), player);

        var casing = labelled("logos_dimension.ammo_bench.casing", slot(bench.inputs(), AmmoBenchBlockEntity.CASING, "bench_casing"));
        var powder = labelled("logos_dimension.ammo_bench.powder", slot(bench.inputs(), AmmoBenchBlockEntity.POWDER, "bench_powder"));
        var head = labelled("logos_dimension.ammo_bench.head", slot(bench.inputs(), AmmoBenchBlockEntity.HEAD, "bench_head"));
        var progress = new ProgressBar().setRange(0, 1);
        progress.setId("bench_progress");
        progress.label(l -> l.setText(Component.empty()));
        progress.bar(b -> b.style(s -> s.background(new ColorRectTexture(BRASS))));
        progress.bind(DataBindingBuilder.floatValS2C(bench::progress).build());
        progress.layout(l -> l.width(44).height(12));
        var output = slot(bench.output(), 0, "bench_output");
        // 三格竖排在左（名字写在格子左边），进度条和出货格在右边居中：材料从上往下塞进压床，成弹从右边出来。
        var inputs = new UIElement().layout(l -> l.gapAll(2)).addChildren(casing, powder, head);
        var press = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(8).alignItems(AlignItems.CENTER))
                .addChildren(inputs, progress, output);

        var status = new Label();
        status.setId("bench_status");
        status.bind(DataBindingBuilder.componentS2C(() -> {
            var making = bench.pressing();
            return making.isEmpty() ? Component.translatable("logos_dimension.ammo_bench.idle")
                    : Component.translatable("logos_dimension.ammo_bench.pressing", making.getHoverName(), making.getCount());
        }).build());

        var title = new Label();
        title.setText(Component.translatable("block.logos_dimension.ammo_bench"));
        var page = page(176, title, press, status, new InventorySlots());
        var root = new UIElement().layout(l -> l.widthPercent(100).heightPercent(100).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
        root.addChild(page);
        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP),
                StylesheetManager.INSTANCE.getStylesheetSafe(MANUSCRIPT)), player);
    }

    private static ItemSlot slot(ItemStacksResourceHandler handler, int index, String id) {
        var slot = new ItemSlot();
        slot.setId(id);
        slot.bind(new ResourceHandlerSlot(handler, handler::set, index, 0, 0));
        return slot;
    }

    /** 格子左边写它的名字（弹壳、火药、弹头），名字占一样宽，三个格子对齐。 */
    private static UIElement labelled(String key, UIElement slot) {
        var label = new Label();
        label.setText(Component.translatable(key));
        label.layout(l -> l.width(LABEL_WIDTH));
        return new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4).alignItems(AlignItems.CENTER)).addChildren(label, slot);
    }

    /** Logos 魔力器具的那一页：木台上摊一张账本纸，字是墨色。 */
    private static UIElement page(int width, UIElement... children) {
        var inner = new UIElement().layout(l -> l.widthPercent(100).gapAll(4).paddingAll(8).paddingTop(10));
        inner.style(s -> s.background(AlchemyArt.ledger()));
        inner.addChildren(children);
        for (var child : children) {
            if (child instanceof Label label) {
                label.layout(l -> l.widthPercent(100));
                label.textStyle(s -> s.textWrap(TextWrap.WRAP).adaptiveHeight(true));
            }
        }
        AlchemyArt.inked(inner);
        var bench = new UIElement().layout(l -> l.width(width + 28).paddingAll(14));
        bench.setId("ammo_bench_page");
        bench.style(s -> s.background(AlchemyArt.bench()));
        bench.addChild(inner);
        return bench;
    }
}
