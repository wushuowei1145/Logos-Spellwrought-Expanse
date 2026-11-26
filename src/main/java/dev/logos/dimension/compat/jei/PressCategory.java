package dev.logos.dimension.compat.jei;

import dev.logos.dimension.firearm.AmmoBench;
import dev.logos.dimension.firearm.AmmoPressRecipe;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import net.minecraft.network.chat.Component;

/** 铸弹台压制：弹壳、火药、弹头竖排在左，箭头，右边一批弹。 */
final class PressCategory extends AbstractRecipeCategory<AmmoPressRecipe> {
    private static final int SLOT = 18;

    PressCategory(IGuiHelper gui) {
        super(AmmoJeiPlugin.PRESS, Component.translatable("logos_dimension.ammo_bench.tab_press"),
                gui.createDrawableItemLike(AmmoBench.BENCH.get()), 90, 3 * SLOT);
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, AmmoPressRecipe recipe, IFocusGroup focuses) {
        builder.addInputSlot(1, 1).setStandardSlotBackground().add(recipe.casing());
        builder.addInputSlot(1, 1 + SLOT).setStandardSlotBackground().add(recipe.powder());
        builder.addInputSlot(1, 1 + 2 * SLOT).setStandardSlotBackground().add(recipe.head());
        builder.addOutputSlot(66, 1 + SLOT).setOutputSlotBackground().add(recipe.result());
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, AmmoPressRecipe recipe, IFocusGroup focuses) {
        builder.addRecipeArrowWidget().setPosition(30, SLOT);
    }
}
