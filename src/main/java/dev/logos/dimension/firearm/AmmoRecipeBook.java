package dev.logos.dimension.firearm;

import java.util.Comparator;
import java.util.List;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/**
 * 铸弹台三种配方的一览：铸弹台界面的配方册和 JEI 用。服务端直接读配方表；客户端读服务端随数据包发来的那份
 * （{@code client.firearm.ClientAmmoRecipes} 收到时交给 {@link #received}）。两边排序一样：压制、改装按配方名，蚀刻按档位。
 */
public final class AmmoRecipeBook {
    private AmmoRecipeBook() {}

    private static volatile List<AmmoPressRecipe> press = List.of();
    private static volatile List<RoundEtchRecipe> etch = List.of();
    private static volatile List<RoundModRecipe> mods = List.of();

    public static List<AmmoPressRecipe> press(Level level) {
        return level.recipeAccess() instanceof RecipeManager rm ? press(rm.recipeMap()) : press;
    }

    public static List<RoundEtchRecipe> etch(Level level) {
        return level.recipeAccess() instanceof RecipeManager rm ? etch(rm.recipeMap()) : etch;
    }

    public static List<RoundModRecipe> mods(Level level) {
        return level.recipeAccess() instanceof RecipeManager rm ? mods(rm.recipeMap()) : mods;
    }

    /** 客户端收到的那份（JEI 在客户端加载插件时用；还没收到时是空的）。 */
    public static List<AmmoPressRecipe> receivedPress() {
        return press;
    }

    public static List<RoundEtchRecipe> receivedEtch() {
        return etch;
    }

    public static List<RoundModRecipe> receivedMods() {
        return mods;
    }

    /** 客户端收到服务端发来的配方。 */
    public static void received(RecipeMap map) {
        press = press(map);
        etch = etch(map);
        mods = mods(map);
    }

    /** 客户端退出世界：配方随世界走，清掉。 */
    public static void clear() {
        press = List.of();
        etch = List.of();
        mods = List.of();
    }

    private static List<AmmoPressRecipe> press(RecipeMap map) {
        return byName(map, AmmoBench.PRESS_RECIPE.get());
    }

    private static List<RoundEtchRecipe> etch(RecipeMap map) {
        return byName(map, AmmoBench.ETCH_RECIPE.get()).stream().sorted(Comparator.comparingInt(RoundEtchRecipe::tier)).toList();
    }

    private static List<RoundModRecipe> mods(RecipeMap map) {
        return byName(map, AmmoBench.MOD_RECIPE.get()).stream()
                .sorted(Comparator.comparingInt(r -> {
                    int i = RoundEffects.MODS.indexOf(r.finish());
                    return i < 0 ? Integer.MAX_VALUE : i;
                })).toList();
    }

    private static <I extends RecipeInput, T extends Recipe<I>> List<T> byName(RecipeMap map, RecipeType<T> type) {
        return map.byType(type).stream().sorted(Comparator.comparing((RecipeHolder<T> h) -> h.id().identifier().toString()))
                .map(RecipeHolder::value).toList();
    }
}
