package dev.logos.dimension.compat.jei;

import dev.logos.dimension.firearm.RoundEffects;
import dev.logos.dimension.firearm.RoundModRecipe;
import java.util.ArrayList;
import java.util.List;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 铸弹台改装：普通弹、添加物竖排在左，右边是改好的弹（和左边的弹一起轮换），添加物用完剩下的东西（空瓶）在它下面；
 * 弹的数量就是一份添加物改几发。上面一行写这种改装的效果。药水弹的药粉也跟着轮换，每种药水一份，成品是那种药水的弹。
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
        // 每种弹配每份示范的添加物（药水弹是每种药水的药粉，别的就一样东西）：三格一起轮换。
        List<ItemStack> additives = RoundEffects.samples(recipe.additive(), recipe.finish());
        List<ItemStack> plain = new ArrayList<>(), adds = new ArrayList<>(), modded = new ArrayList<>();
        for (Item item : AmmoJeiPlugin.moddable()) {
            for (ItemStack add : additives) {
                ItemStack round = new ItemStack(item, recipe.rounds());
                plain.add(round);
                adds.add(add);
                modded.add(RoundEffects.modified(round, add, recipe.finish()).copyWithCount(recipe.rounds()));
            }
        }
        var rounds = builder.addInputSlot(1, TOP + 1).setStandardSlotBackground().addItemStacks(plain);
        var additive = builder.addInputSlot(1, TOP + 1 + SLOT).setStandardSlotBackground().addItemStacks(adds);
        var out = builder.addOutputSlot(66, TOP + 1).setOutputSlotBackground().addItemStacks(modded);
        builder.createFocusLink(rounds, additive, out);
        recipe.remainder().ifPresent(r -> builder.addOutputSlot(66, TOP + 1 + SLOT).setStandardSlotBackground().add(r.create()));
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, RoundModRecipe recipe, IFocusGroup focuses) {
        builder.addText(Component.translatable("tooltip.logos_dimension.round_finish." + recipe.finish()), 170, TOP - 2)
                .setPosition(0, 0).setColor(INK);
        builder.addRecipeArrowWidget().setPosition(30, TOP + SLOT / 2);
    }
}
