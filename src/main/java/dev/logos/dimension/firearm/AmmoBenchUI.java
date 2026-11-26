package dev.logos.dimension.firearm;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;
import dev.logos.ui.AlchemyArt;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;
import org.jspecify.annotations.Nullable;

/**
 * 铸弹台的界面：和 Logos 的魔力器具一样是工作台上摊开的一页账本，右边三条书签翻页：压制、铭刻、改装。
 * 每页左边是这台机器的格子（名字写在格子旁边），右边是这一页的配方册：每条一行「材料 → 成品」，
 * 现在格子里的东西对上哪一条，哪一条就亮起来；点一条，空着的格子里就画出该放什么（半透明的虚影）。
 * 最下面一行写这一页正在做什么，做不了的话为什么。
 */
final class AmmoBenchUI {
    private static final Identifier MANUSCRIPT = Identifier.fromNamespaceAndPath("logos", "lss/manuscript.lss");
    /** 压制进度：黄铜色；铭刻：紫水晶色；改装：硫黄色；魔力：Logos 魔力器具的蓝。 */
    private static final int BRASS = 0xFFD2A24C, AMETHYST = 0xFFB07FE0, SULFUR = 0xFFE0C040, MANA = 0xFF9FD3EE;
    /** 配方册：对上的那一条（淡金），点选的那一条（淡墨）；虚影盖一层灰。 */
    private static final int MATCH = 0x60E8C45A, PICKED = 0x30402A10;
    private static final int GHOST = 0xA08B8B8B;
    /** 格子名字那一列的宽度：放得下 "Powder"、"Conductor" 这样的英文。 */
    /** 配方册宽多少、滚动区多高（露出五条）。 */
    private static final int LABEL = 54, BOOK_W = 150, BOOK_ROWS = 98;
    private static final String[] TABS = {"logos_dimension.ammo_bench.tab_press", "logos_dimension.ammo_bench.tab_etch",
            "logos_dimension.ammo_bench.tab_modify"};

    private AmmoBenchUI() {}

    static ModularUI create(BlockUIMenuType.BlockUIHolder holder) {
        var player = holder.player;
        if (!(player.level().getBlockEntity(holder.pos) instanceof AmmoBenchBlockEntity bench)) return ModularUI.of(UI.of(new UIElement()), player);
        Level level = player.level();

        UIElement[] pages = {pressPage(bench, level), etchPage(bench, level), modifyPage(bench, level)};
        for (int i = 1; i < pages.length; i++) pages[i].setDisplay(false);
        int[] open = {0};
        var ribbons = new UIElement().layout(l -> l.width(16).paddingTop(16).gapAll(4).flexShrink(0));
        for (int i = 0; i < TABS.length; i++) {
            int page = i;
            var tab = AlchemyArt.ribbon(TABS[i], () -> open[0] == page, () -> {
                open[0] = page;
                for (int j = 0; j < pages.length; j++) pages[j].setDisplay(j == page);
            });
            tab.setId("bench_tab_" + i);
            ribbons.addChild(tab);
        }

        var title = new Label();
        title.setText(Component.translatable("block.logos_dimension.ammo_bench"));
        var inner = new UIElement().layout(l -> l.width(LABEL + 26 + 30 + 26 + 18 + BOOK_W + 40).gapAll(4).paddingAll(8).paddingTop(10));
        inner.style(s -> s.background(AlchemyArt.ledger()));
        inner.addChild(title);
        inner.addChildren(pages);
        inner.addChild(new InventorySlots());
        AlchemyArt.inked(inner);
        var benchTop = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).paddingAll(14).gapAll(6));
        benchTop.setId("ammo_bench_page");
        benchTop.style(s -> s.background(AlchemyArt.bench()));
        benchTop.addChildren(inner, ribbons);
        var root = new UIElement().layout(l -> l.widthPercent(100).heightPercent(100).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
        root.addChild(benchTop);
        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP),
                StylesheetManager.INSTANCE.getStylesheetSafe(MANUSCRIPT)), player);
    }

    // ---------------------------------------------------------------- 三页

    /** 压制：弹壳、火药、弹头 → 压床 → 成弹；配方册列出每种弹的三样材料。 */
    private static UIElement pressPage(AmmoBenchBlockEntity bench, Level level) {
        var casing = slot(bench.inputs(), AmmoBenchBlockEntity.CASING, "bench_casing");
        var powder = slot(bench.inputs(), AmmoBenchBlockEntity.POWDER, "bench_powder");
        var head = slot(bench.inputs(), AmmoBenchBlockEntity.HEAD, "bench_head");
        var machine = machine(column(labelled("logos_dimension.ammo_bench.casing", casing), labelled("logos_dimension.ammo_bench.powder", powder),
                labelled("logos_dimension.ammo_bench.head", head)), bar("bench_progress", bench::progress, 1, BRASS, 30), slot(bench.output(), 0, "bench_output"));
        List<UIElement> rows = new ArrayList<>();
        int[] picked = {-1};
        var recipes = AmmoRecipeBook.press(level);
        for (int i = 0; i < recipes.size(); i++) {
            var r = recipes.get(i);
            int index = i;
            rows.add(bookRow("bench_book_press_" + i, List.of(first(r.casing()), first(r.powder()), first(r.head())), r.result().create(), null,
                    () -> r.matches(new AmmoPressRecipe.Input(stack(bench.inputs(), 0), stack(bench.inputs(), 1), stack(bench.inputs(), 2)), level),
                    () -> picked[0] == index,
                    () -> {
                        picked[0] = index;
                        ghost(casing, first(r.casing()));
                        ghost(powder, first(r.powder()));
                        ghost(head, first(r.head()));
                    }));
        }
        var status = line("bench_status", () -> {
            var making = bench.pressing();
            return making.isEmpty() ? Component.translatable("logos_dimension.ammo_bench.idle")
                    : Component.translatable("logos_dimension.ammo_bench.pressing", making.getHoverName(), making.getCount());
        });
        return page("bench_page_press", machine, book("logos_dimension.ammo_bench.book.press", rows), status);
    }

    /** 铭刻：图纸、弹药、催化、导体 → 术式弹，下面是台子的魔力和补魔格；配方册是七档蚀刻表。 */
    private static UIElement etchPage(AmmoBenchBlockEntity bench, Level level) {
        var blueprint = slot(bench.etching(), AmmoBenchBlockEntity.BLUEPRINT, "bench_blueprint");
        var rounds = slot(bench.etching(), AmmoBenchBlockEntity.ROUNDS, "bench_rounds");
        var catalyst = slot(bench.etching(), AmmoBenchBlockEntity.CATALYST, "bench_catalyst");
        var conductor = slot(bench.etching(), AmmoBenchBlockEntity.CONDUCTOR, "bench_conductor");
        var machine = machine(column(labelled("logos_dimension.ammo_bench.blueprint", blueprint), labelled("logos_dimension.ammo_bench.rounds", rounds),
                        labelled("logos_dimension.ammo_bench.catalyst", catalyst), labelled("logos_dimension.ammo_bench.conductor", conductor)),
                bar("bench_etch_progress", bench::etchProgress, 1, AMETHYST, 30), slot(bench.etched(), 0, "bench_etched"));
        var manaText = line("bench_mana", () -> Component.translatable("logos_dimension.ammo_bench.mana", (int) bench.mana(), (int) bench.maxMana()));
        var mana = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6).alignItems(AlignItems.CENTER)).addChildren(
                bar("bench_mana_gauge", () -> (float) bench.mana(), (float) bench.maxMana(), MANA, 84),
                labelled("logos_dimension.ammo_bench.fuel", slot(bench.fuel(), 0, "bench_fuel"), 28));
        var left = new UIElement().layout(l -> l.gapAll(3)).addChildren(machine, manaText, mana);
        List<UIElement> rows = new ArrayList<>();
        int[] picked = {-1};
        var tiers = AmmoRecipeBook.etch(level);
        ItemStack sample = new ItemStack(RifleRoundItem.IRON.get());
        for (int i = 0; i < tiers.size(); i++) {
            var r = tiers.get(i);
            int index = i;
            ItemStack result = sample.copy();
            result.set(Firearms.FINISH.get(), RoundEffects.spell(r.tier()));
            List<ItemStack> materials = new ArrayList<>();
            materials.add(r.catalyst().map(AmmoBenchUI::first).orElse(ItemStack.EMPTY));
            materials.add(first(r.conductor()));
            rows.add(bookRow("bench_book_etch_" + i, materials, result, Component.translatable("logos_dimension.ammo_bench.book.capacity", r.capacity()),
                    () -> r.matches(new RoundEtchRecipe.Input(stack(bench.etching(), AmmoBenchBlockEntity.CATALYST),
                            stack(bench.etching(), AmmoBenchBlockEntity.CONDUCTOR)), level),
                    () -> picked[0] == index,
                    () -> {
                        picked[0] = index;
                        ghost(blueprint, new ItemStack(AmmoBlueprintItem.ITEM.get()));
                        ghost(rounds, sample);
                        ghost(catalyst, r.catalyst().map(AmmoBenchUI::first).orElse(ItemStack.EMPTY));
                        ghost(conductor, first(r.conductor()));
                    }));
        }
        var status = line("bench_etch_status", () -> {
            var plan = bench.etchPlan();
            return plan.problem() != null ? plan.problem()
                    : Component.translatable("logos_dimension.ammo_bench.etching", plan.result().getHoverName(), plan.count());
        });
        return page("bench_page_etch", left, book("logos_dimension.ammo_bench.book.etch", rows), status);
    }

    /** 改装：弹药、添加物 → 改装弹；配方册列出每种添加物改成什么。 */
    private static UIElement modifyPage(AmmoBenchBlockEntity bench, Level level) {
        var rounds = slot(bench.modding(), AmmoBenchBlockEntity.MOD_ROUNDS, "bench_mod_rounds");
        var additive = slot(bench.modding(), AmmoBenchBlockEntity.ADDITIVE, "bench_additive");
        var machine = machine(column(labelled("logos_dimension.ammo_bench.rounds", rounds), labelled("logos_dimension.ammo_bench.additive", additive)),
                bar("bench_mod_progress", bench::modProgress, 1, SULFUR, 30), slot(bench.modded(), 0, "bench_modded"));
        List<UIElement> rows = new ArrayList<>();
        int[] picked = {-1};
        ItemStack sample = new ItemStack(RifleRoundItem.IRON.get());
        var mods = AmmoRecipeBook.mods(level);
        for (int i = 0; i < mods.size(); i++) {
            var r = mods.get(i);
            int index = i;
            // 药水弹拿一份真有药水的药粉来示范（配方只写了药粉这件物品）；成品写上一份添加物改几发。
            var samples = RoundEffects.samples(r.additive(), r.finish());
            ItemStack add = samples.isEmpty() ? ItemStack.EMPTY : samples.getFirst();
            rows.add(bookRow("bench_book_mod_" + i, List.of(add), RoundEffects.modified(sample, add, r.finish()).copyWithCount(r.rounds()),
                    Component.translatable("logos_dimension.round_finish." + r.finish()),
                    () -> r.matches(new RoundModRecipe.Input(stack(bench.modding(), 0), stack(bench.modding(), 1)), level),
                    () -> picked[0] == index,
                    () -> {
                        picked[0] = index;
                        ghost(rounds, sample);
                        ghost(additive, add);
                    }));
        }
        var status = line("bench_mod_status", () -> {
            var plan = bench.modPlan();
            return plan.problem() != null ? plan.problem()
                    : Component.translatable("logos_dimension.ammo_bench.modifying", plan.result().getHoverName(), plan.count());
        });
        return page("bench_page_modify", machine, book("logos_dimension.ammo_bench.book.modify", rows), status);
    }

    // ---------------------------------------------------------------- 零件

    /** 一页：左边机器，右边配方册，下面一行状态。 */
    private static UIElement page(String id, UIElement left, UIElement book, Label status) {
        var both = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(10).alignItems(AlignItems.FLEX_START)).addChildren(left, book);
        status.layout(l -> l.widthPercent(100));
        status.textStyle(s -> s.textWrap(TextWrap.WRAP).adaptiveHeight(true));
        var page = new UIElement().layout(l -> l.gapAll(4)).addChildren(both, status);
        page.setId(id);
        return page;
    }

    /** 机器：竖排的格子、进度条、出货格。 */
    private static UIElement machine(UIElement slots, UIElement progress, UIElement out) {
        return new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6).alignItems(AlignItems.CENTER)).addChildren(slots, progress, out);
    }

    /** 配方册：一句说明，下面一列可以上下滚动的配方（悬停在一条上写出整条配方和怎么用）。 */
    private static UIElement book(String key, List<UIElement> rows) {
        var head = new Label();
        head.setText(Component.translatable(key));
        head.layout(l -> l.width(BOOK_W));
        head.textStyle(s -> s.textWrap(TextWrap.WRAP).adaptiveHeight(true));
        // 留出竖滚动条的宽度，不出横滚动条。
        var list = new UIElement().layout(l -> l.width(BOOK_W - 12).gapAll(1));
        list.addChildren(rows.toArray(UIElement[]::new));
        var scroll = new ScrollerView();
        scroll.layout(l -> l.width(BOOK_W).height(BOOK_ROWS));
        scroll.style(s -> s.background(IGuiTexture.EMPTY));
        scroll.getScrollerViewStyle().mode(com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode.VERTICAL);
        scroll.viewPort(v -> v.style(s -> s.background(IGuiTexture.EMPTY)));
        scroll.addScrollViewChild(list);
        return new UIElement().layout(l -> l.width(BOOK_W).gapAll(2)).addChildren(head, scroll);
    }

    /** 配方册的一条：材料的图标 → 成品的图标（和数量），后面一句短注；对上了亮，点选了加深，点一下选它。 */
    private static UIElement bookRow(String id, List<ItemStack> inputs, ItemStack output, @Nullable Component note,
                                     BooleanSupplier matches, BooleanSupplier picked, Runnable pick) {
        var row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(2).paddingAll(1).alignItems(AlignItems.CENTER));
        row.setId(id);
        // 只在客户端画（服务端也搭一遍界面，但从不画）。
        row.style(s -> s.backgroundTexture((GuiTexture) (context, x, y, width, height) -> {
            int colour = matches.getAsBoolean() ? MATCH : picked.getAsBoolean() ? PICKED : 0;
            if (colour != 0 && context instanceof GUIContext gui) DrawerHelperClient.drawSolidRect(gui, x, y, width, height, colour);
        }));
        for (var stack : inputs) row.addChild(stack.isEmpty() ? text("—") : icon(stack));
        row.addChild(text("→"));
        // 成品的数量画在图标角上（像格子里那样），整句「×8」在悬停的配方里。
        row.addChild(icon(output));
        if (note != null) {
            var label = new Label();
            label.setText(note);
            row.addChild(label);
        }
        // 整行是一个点选目标：图标和字不单独接鼠标，悬停在行上任何地方都写出整条配方。
        for (var child : row.getChildren()) child.setAllowHitTest(false);
        row.style(s -> s.tooltips(recipeText(inputs, output, note), Component.translatable("logos_dimension.ammo_bench.book.pick")));
        row.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button == 0) pick.run();
        });
        return row;
    }

    /** 一条配方写成一句：「纸 + 火药 + 铁锭 → 铁霰弹 ×8」，后面加上短注。 */
    private static Component recipeText(List<ItemStack> inputs, ItemStack output, @Nullable Component note) {
        var text = Component.empty();
        boolean firstPart = true;
        for (var stack : inputs) {
            if (stack.isEmpty()) continue;
            if (!firstPart) text.append(" + ");
            text.append(stack.getHoverName());
            firstPart = false;
        }
        text.append(" → ").append(output.getHoverName());
        if (output.getCount() > 1) text.append(" ×" + output.getCount());
        if (note != null) text.append("（").append(note).append("）");
        return text;
    }

    private static UIElement icon(ItemStack stack) {
        var icon = new UIElement().layout(l -> l.width(16).height(16).flexShrink(0));
        icon.style(s -> s.background(new ItemStackTexture(stack)));
        return icon;
    }

    private static Label text(String s) {
        var label = new Label();
        label.setText(Component.literal(s));
        return label;
    }

    /** 空格子里画出该放什么：物品盖一层灰，格子里有东西时不画。 */
    private static void ghost(ItemSlot slot, ItemStack what) {
        IGuiTexture ghost = what.isEmpty() ? IGuiTexture.EMPTY : new GuiTextureGroup(new ItemStackTexture(what), new ColorRectTexture(GHOST));
        slot.slotStyle(s -> s.slotOverlay(ghost).showSlotOverlayOnlyEmpty(true));
    }

    /** 一种材料拿来显示的那件物品（标签取第一个）。 */
    private static ItemStack first(Ingredient ingredient) {
        return ingredient.items().findFirst().map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    private static ItemStack stack(ItemStacksResourceHandler h, int i) {
        return h.getResource(i).toStack(h.getAmountAsInt(i));
    }

    private static ItemSlot slot(ItemStacksResourceHandler handler, int index, String id) {
        var slot = new ItemSlot();
        slot.setId(id);
        slot.bind(new ResourceHandlerSlot(handler, handler::set, index, 0, 0));
        return slot;
    }

    private static UIElement column(UIElement... children) {
        return new UIElement().layout(l -> l.gapAll(2)).addChildren(children);
    }

    /** 格子左边写它的名字，名字占一样宽，一列格子对齐。 */
    private static UIElement labelled(String key, UIElement slot) {
        return labelled(key, slot, LABEL);
    }

    private static UIElement labelled(String key, UIElement slot, int width) {
        var label = new Label();
        label.setText(Component.translatable(key));
        label.layout(l -> l.width(width));
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
}
