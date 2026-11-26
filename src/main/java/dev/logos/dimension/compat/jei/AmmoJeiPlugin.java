package dev.logos.dimension.compat.jei;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.firearm.AmmoBench;
import dev.logos.dimension.firearm.AmmoPressRecipe;
import dev.logos.dimension.firearm.AmmoRecipeBook;
import dev.logos.dimension.firearm.BlankRoundItem;
import dev.logos.dimension.firearm.BuckshotItem;
import dev.logos.dimension.firearm.Firearms;
import dev.logos.dimension.firearm.RifleRoundItem;
import dev.logos.dimension.firearm.RoundEffects;
import dev.logos.dimension.firearm.RoundEtchRecipe;
import dev.logos.dimension.firearm.RoundModRecipe;
import java.util.ArrayList;
import java.util.List;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.registration.IExtraIngredientRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.FireworkExplosion;

/**
 * JEI 联动，只在装了 JEI 时加载（docs/FIREARMS.md 第 11 节）：铸弹台的压制、铭刻、改装各一个配方分类；
 * 每种弹按外观组件 {@code round_finish} 分成不同条目（像药水那样），所以「晶辉术式铁步枪弹」「燃烧铁霰弹」都能单独查到配方。
 * 药水弹不再按药水分（每种弹一条「药水铁步枪弹」），它的改装配方里药粉和成品按每种药水一起轮换。
 */
@JeiPlugin
public final class AmmoJeiPlugin implements IModPlugin {
    static final IRecipeType<AmmoPressRecipe> PRESS = IRecipeType.create(LogosDimension.id("ammo_pressing"), AmmoPressRecipe.class);
    static final IRecipeType<RoundEtchRecipe> ETCH = IRecipeType.create(LogosDimension.id("round_etching"), RoundEtchRecipe.class);
    static final IRecipeType<RoundModRecipe> MODIFY = IRecipeType.create(LogosDimension.id("round_modifying"), RoundModRecipe.class);

    @Override
    public Identifier getPluginUid() {
        return LogosDimension.id("jei");
    }

    /** 七种弹：铭刻、改装都只改外观组件，JEI 按它分条目。 */
    static List<Item> rounds() {
        return List.of(BuckshotItem.COPPER.get(), BuckshotItem.ITEM.get(), BuckshotItem.NETHERITE.get(),
                RifleRoundItem.COPPER.get(), RifleRoundItem.IRON.get(), RifleRoundItem.NETHERITE.get(), BlankRoundItem.ITEM.get());
    }

    /** 有弹丸、能改装的六种（空弹头不改装）。 */
    static List<Item> moddable() {
        return rounds().subList(0, 6);
    }

    /** {@code item} 带上外观 {@code finish} 的一发（烟火弹配一颗默认的烟火之星）。 */
    static ItemStack finished(Item item, String finish) {
        ItemStack stack = new ItemStack(item);
        stack.set(Firearms.FINISH.get(), finish);
        if (finish.equals(RoundEffects.FIREWORK)) stack.set(DataComponents.FIREWORK_EXPLOSION, FireworkExplosion.DEFAULT);
        return stack;
    }

    @Override
    public void registerItemSubtypes(ISubtypeRegistration registration) {
        for (Item item : rounds()) registration.registerFromDataComponentTypes(item, Firearms.FINISH.get());
    }

    /** 刻了术式、改装过的弹不在创造物品栏里，这里把每种外观都加进 JEI 的物品列表。 */
    @Override
    public void registerExtraIngredients(IExtraIngredientRegistration registration) {
        List<ItemStack> extra = new ArrayList<>();
        for (Item item : rounds()) {
            for (int tier = 1; tier <= RoundEtchRecipe.TIERS; tier++) extra.add(finished(item, RoundEffects.spell(tier)));
        }
        for (Item item : moddable()) for (String mod : RoundEffects.MODS) extra.add(finished(item, mod));
        registration.addExtraItemStacks(extra);
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        var gui = registration.getJeiHelpers().getGuiHelper();
        registration.addRecipeCategories(new PressCategory(gui), new EtchCategory(gui), new ModifyCategory(gui));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addRecipes(PRESS, AmmoRecipeBook.receivedPress());
        registration.addRecipes(ETCH, AmmoRecipeBook.receivedEtch());
        registration.addRecipes(MODIFY, AmmoRecipeBook.receivedMods());
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        Item bench = AmmoBench.BENCH.get().asItem();
        registration.addCraftingStation(PRESS, bench);
        registration.addCraftingStation(ETCH, bench);
        registration.addCraftingStation(MODIFY, bench);
    }
}
