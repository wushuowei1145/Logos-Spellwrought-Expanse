package dev.logos.dimension.compat.jei;

import dev.logos.dimension.firearm.AmmoBench;
import dev.logos.dimension.firearm.AmmoBlueprintItem;
import dev.logos.dimension.firearm.RoundEffects;
import dev.logos.dimension.firearm.RoundEtchRecipe;
import java.util.List;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 铸弹台铭刻的一档：图纸、普通弹、催化、导体竖排在左，右边是刻了这一档的弹（和左边的弹一起轮换），
 * 上面写档位和每发最多注入多少（空弹头加倍）。
 */
final class EtchCategory extends AbstractRecipeCategory<RoundEtchRecipe> {
    private static final int SLOT = 18, TOP = 11;
    private static final int INK = 0xFF4A3A28;

    EtchCategory(IGuiHelper gui) {
        super(AmmoJeiPlugin.ETCH, Component.translatable("logos_dimension.ammo_bench.tab_etch"),
                gui.createDrawableItemStack(AmmoJeiPlugin.finished(dev.logos.dimension.firearm.RifleRoundItem.IRON.get(), RoundEffects.spell(2))),
                130, TOP + 4 * SLOT);
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, RoundEtchRecipe recipe, IFocusGroup focuses) {
        String finish = RoundEffects.spell(recipe.tier());
        List<ItemStack> plain = AmmoJeiPlugin.rounds().stream().map(ItemStack::new).toList();
        List<ItemStack> etched = AmmoJeiPlugin.rounds().stream().map(i -> AmmoJeiPlugin.finished(i, finish)).toList();
        builder.addInputSlot(1, TOP + 1).setStandardSlotBackground().add(new ItemStack(AmmoBlueprintItem.ITEM.get()));
        var rounds = builder.addInputSlot(1, TOP + 1 + SLOT).setStandardSlotBackground().addItemStacks(plain);
        var catalyst = builder.addInputSlot(1, TOP + 1 + 2 * SLOT).setStandardSlotBackground();
        recipe.catalyst().ifPresent(catalyst::add);
        builder.addInputSlot(1, TOP + 1 + 3 * SLOT).setStandardSlotBackground().add(recipe.conductor());
        var out = builder.addOutputSlot(76, TOP + 1 + SLOT + SLOT / 2).setOutputSlotBackground().addItemStacks(etched);
        builder.createFocusLink(rounds, out);
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, RoundEtchRecipe recipe, IFocusGroup focuses) {
        builder.addText(Component.translatable("logos_dimension.round_finish." + RoundEffects.spell(recipe.tier()))
                        .append(" ").append(Component.translatable("logos_dimension.ammo_bench.book.capacity", recipe.capacity())), 130, TOP - 2)
                .setPosition(0, 0).setColor(INK);
        builder.addRecipeArrowWidget().setPosition(40, TOP + SLOT + SLOT / 2);
    }
}
