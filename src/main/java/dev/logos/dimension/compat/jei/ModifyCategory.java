package dev.logos.dimension.compat.jei;

import dev.logos.dimension.firearm.RoundEffects;
import dev.logos.dimension.firearm.RoundModRecipe;
import java.util.List;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 铸弹台改装：普通弹、添加物竖排在左，右边是改好的弹（和左边的弹一起轮换），添加物用完剩下的东西（空瓶）在它下面；
 * 上面一行写这种改装的效果。
 */
final class ModifyCategory extends AbstractRecipeCategory<RoundModRecipe> {
    private static final int SLOT = 18, TOP = 11;
    private static final int INK = 0xFF4A3A28;

    ModifyCategory(IGuiHelper gui) {
        super(AmmoJeiPlugin.MODIFY, Component.translatable("logos_dimension.ammo_bench.tab_modify"),
                gui.createDrawableItemStack(AmmoJeiPlugin.finished(dev.logos.dimension.firearm.RifleRoundItem.IRON.get(), RoundEffects.SULFUR)),
                170, TOP + 2 * SLOT);
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, RoundModRecipe recipe, IFocusGroup focuses) {
        List<ItemStack> plain = AmmoJeiPlugin.moddable().stream().map(ItemStack::new).toList();
        List<ItemStack> modded = AmmoJeiPlugin.moddable().stream().map(i -> AmmoJeiPlugin.finished(i, recipe.finish())).toList();
        var rounds = builder.addInputSlot(1, TOP + 1).setStandardSlotBackground().addItemStacks(plain);
        builder.addInputSlot(1, TOP + 1 + SLOT).setStandardSlotBackground().add(recipe.additive());
        var out = builder.addOutputSlot(66, TOP + 1).setOutputSlotBackground().addItemStacks(modded);
        builder.createFocusLink(rounds, out);
        recipe.remainder().ifPresent(r -> builder.addOutputSlot(66, TOP + 1 + SLOT).setStandardSlotBackground().add(r.create()));
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, RoundModRecipe recipe, IFocusGroup focuses) {
        builder.addText(Component.translatable("tooltip.logos_dimension.round_finish." + recipe.finish()), 170, TOP - 2)
                .setPosition(0, 0).setColor(INK);
        builder.addRecipeArrowWidget().setPosition(30, TOP + SLOT / 2);
    }
}
