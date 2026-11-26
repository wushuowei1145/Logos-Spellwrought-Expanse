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
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;

/**
 * 铸弹台的界面：和 Logos 的魔力器具一样是工作台上摊开的一页账本。左边压制（弹壳、火药、弹头 → 成弹），
 * 右边铭刻（图纸、弹药、催化、导体 → 术式弹），下面是台子存的魔力和补魔物品格，再下面两行写正在做什么、做不了的原因。
 */
final class AmmoBenchUI {
    private static final Identifier MANUSCRIPT = Identifier.fromNamespaceAndPath("logos", "lss/manuscript.lss");
    /** 压制进度：黄铜色；铭刻进度：紫水晶色；魔力：Logos 魔力器具的蓝。 */
    private static final int BRASS = 0xFFD2A24C, AMETHYST = 0xFFB07FE0, MANA = 0xFF9FD3EE;
    /** 格子名字那一列的宽度：放得下 "Powder"、"Conductor" 这样的英文。 */
    private static final int PRESS_LABEL = 40, ETCH_LABEL = 54, FUEL_LABEL = 28;

    private AmmoBenchUI() {}

    static ModularUI create(BlockUIMenuType.BlockUIHolder holder) {
        var player = holder.player;
        if (!(player.level().getBlockEntity(holder.pos) instanceof AmmoBenchBlockEntity bench)) return ModularUI.of(UI.of(new UIElement()), player);

        // 压制：三格竖排在左，进度条和出货格在右边居中。
        var press = section("logos_dimension.ammo_bench.press",
                column(labelled("logos_dimension.ammo_bench.casing", slot(bench.inputs(), AmmoBenchBlockEntity.CASING, "bench_casing"), PRESS_LABEL),
                        labelled("logos_dimension.ammo_bench.powder", slot(bench.inputs(), AmmoBenchBlockEntity.POWDER, "bench_powder"), PRESS_LABEL),
                        labelled("logos_dimension.ammo_bench.head", slot(bench.inputs(), AmmoBenchBlockEntity.HEAD, "bench_head"), PRESS_LABEL)),
                bar("bench_progress", bench::progress, 1, BRASS, 30), slot(bench.output(), 0, "bench_output"));
        // 铭刻：图纸、弹药、催化、导体竖排，进度条和术式弹的出货格。
        var etch = section("logos_dimension.ammo_bench.etch",
                column(labelled("logos_dimension.ammo_bench.blueprint", slot(bench.etching(), AmmoBenchBlockEntity.BLUEPRINT, "bench_blueprint"), ETCH_LABEL),
                        labelled("logos_dimension.ammo_bench.rounds", slot(bench.etching(), AmmoBenchBlockEntity.ROUNDS, "bench_rounds"), ETCH_LABEL),
                        labelled("logos_dimension.ammo_bench.catalyst", slot(bench.etching(), AmmoBenchBlockEntity.CATALYST, "bench_catalyst"), ETCH_LABEL),
                        labelled("logos_dimension.ammo_bench.conductor", slot(bench.etching(), AmmoBenchBlockEntity.CONDUCTOR, "bench_conductor"), ETCH_LABEL)),
                bar("bench_etch_progress", bench::etchProgress, 1, AMETHYST, 30), slot(bench.etched(), 0, "bench_etched"));
        var both = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(14).alignItems(AlignItems.FLEX_START)).addChildren(press, etch);

        // 魔力：数字、刻度和补魔物品格。
        var manaText = line("bench_mana", () -> Component.translatable("logos_dimension.ammo_bench.mana", (int) bench.mana(), (int) bench.maxMana()));
        var mana = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(8).alignItems(AlignItems.CENTER)).addChildren(
                bar("bench_mana_gauge", () -> (float) bench.mana(), (float) bench.maxMana(), MANA, 150),
                labelled("logos_dimension.ammo_bench.fuel", slot(bench.fuel(), 0, "bench_fuel"), FUEL_LABEL));

        var pressStatus = line("bench_status", () -> {
            var making = bench.pressing();
            return making.isEmpty() ? Component.translatable("logos_dimension.ammo_bench.idle")
                    : Component.translatable("logos_dimension.ammo_bench.pressing", making.getHoverName(), making.getCount());
        });
        var etchStatus = line("bench_etch_status", () -> {
            var plan = bench.etchPlan();
            return plan.problem() != null ? plan.problem()
                    : Component.translatable("logos_dimension.ammo_bench.etching", plan.result().getHoverName(), plan.count());
        });

        var title = new Label();
        title.setText(Component.translatable("block.logos_dimension.ammo_bench"));
        var page = page(286, title, both, manaText, mana, pressStatus, etchStatus, new InventorySlots());
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

    /** 一块：小标题，下面是竖排的格子、进度条和出货格。 */
    private static UIElement section(String key, UIElement slots, UIElement progress, UIElement out) {
        var title = new Label();
        title.setText(Component.translatable(key));
        var row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6).alignItems(AlignItems.CENTER)).addChildren(slots, progress, out);
        return new UIElement().layout(l -> l.gapAll(3)).addChildren(title, row);
    }

    private static UIElement column(UIElement... children) {
        return new UIElement().layout(l -> l.gapAll(2)).addChildren(children);
    }

    /** 格子左边写它的名字，名字占一样宽，一列格子对齐（{@code width} 为 0 时按字的宽度）。 */
    private static UIElement labelled(String key, UIElement slot, int width) {
        var label = new Label();
        label.setText(Component.translatable(key));
        if (width > 0) label.layout(l -> l.width(width));
        return new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4).alignItems(AlignItems.CENTER)).addChildren(label, slot);
    }

    private static ProgressBar bar(String id, Supplier<Float> value, float max, int colour, int width) {
        var bar = new ProgressBar().setRange(0, max);
        bar.setId(id);
        bar.label(l -> l.setText(Component.empty()));
        bar.bar(b -> b.style(s -> s.background(new ColorRectTexture(colour))));
        bar.bind(DataBindingBuilder.floatValS2C(value).build());
        bar.layout(l -> l.width(width).height(10));
        return bar;
    }

    /** 一行服务端随时更新的字。 */
    private static Label line(String id, Supplier<Component> text) {
        var label = new Label();
        label.setId(id);
        label.bind(DataBindingBuilder.componentS2C(text).build());
        return label;
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
