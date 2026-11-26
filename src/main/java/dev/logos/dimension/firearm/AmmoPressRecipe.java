package dev.logos.dimension.firearm;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * 铸弹台压一批弹药（{@code data/<ns>/recipe/…json}，类型 {@code logos_dimension:ammo_pressing}）：弹壳、火药、弹头各一份，
 * 压 {@code time} 刻，出 {@code result}（一批通常八发）。弹种看弹壳（纸壳是霰弹、铜壳是步枪弹），金属看弹头。
 * <pre>{@code
 * {"type": "logos_dimension:ammo_pressing", "casing": "minecraft:paper", "powder": "minecraft:gunpowder",
 *  "head": "minecraft:iron_ingot", "result": {"id": "logos_dimension:buckshot_shell", "count": 8}, "time": 40}
 * }</pre>
 */
public record AmmoPressRecipe(Ingredient casing, Ingredient powder, Ingredient head, ItemStackTemplate result, int time)
        implements Recipe<AmmoPressRecipe.Input> {
    /** 压一批默认要两秒。 */
    public static final int DEFAULT_TIME = 40;
    public static final MapCodec<AmmoPressRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Ingredient.CODEC.fieldOf("casing").forGetter(AmmoPressRecipe::casing),
            Ingredient.CODEC.fieldOf("powder").forGetter(AmmoPressRecipe::powder),
            Ingredient.CODEC.fieldOf("head").forGetter(AmmoPressRecipe::head),
            ItemStackTemplate.CODEC.fieldOf("result").forGetter(AmmoPressRecipe::result),
            ExtraCodecs.POSITIVE_INT.optionalFieldOf("time", DEFAULT_TIME).forGetter(AmmoPressRecipe::time)).apply(i, AmmoPressRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, AmmoPressRecipe> STREAM_CODEC = StreamCodec.composite(
            Ingredient.CONTENTS_STREAM_CODEC, AmmoPressRecipe::casing,
            Ingredient.CONTENTS_STREAM_CODEC, AmmoPressRecipe::powder,
            Ingredient.CONTENTS_STREAM_CODEC, AmmoPressRecipe::head,
            ItemStackTemplate.STREAM_CODEC, AmmoPressRecipe::result,
            ByteBufCodecs.VAR_INT, AmmoPressRecipe::time,
            AmmoPressRecipe::new);
    public static final RecipeSerializer<AmmoPressRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    /** 铸弹台三个输入格里的东西：弹壳、火药、弹头。 */
    public record Input(ItemStack casing, ItemStack powder, ItemStack head) implements RecipeInput {
        @Override
        public ItemStack getItem(int index) {
            return switch (index) {
                case 0 -> casing;
                case 1 -> powder;
                default -> head;
            };
        }

        @Override
        public int size() {
            return 3;
        }
    }

    /** 三格里的东西能压哪种弹，没有就是 null。只在服务端有配方。 */
    public static @Nullable AmmoPressRecipe find(Level level, Input input) {
        if (!(level.recipeAccess() instanceof RecipeManager recipes)) return null;
        for (RecipeHolder<AmmoPressRecipe> h : recipes.recipeMap().byType(AmmoBench.PRESS_RECIPE.get())) {
            if (h.value().matches(input, level)) return h.value();
        }
        return null;
    }

    /** {@code stack} 能不能放进第 {@code slot} 格（0 弹壳、1 火药、2 弹头）：有哪条配方在这一格用得上它。客户端不知道配方，一律放行。 */
    public static boolean usable(Level level, int slot, ItemStack stack) {
        if (!(level.recipeAccess() instanceof RecipeManager recipes)) return true;
        for (RecipeHolder<AmmoPressRecipe> h : recipes.recipeMap().byType(AmmoBench.PRESS_RECIPE.get())) {
            var r = h.value();
            if ((slot == 0 ? r.casing : slot == 1 ? r.powder : r.head).test(stack)) return true;
        }
        return false;
    }

    @Override
    public boolean matches(Input input, Level level) {
        return casing.test(input.casing()) && powder.test(input.powder()) && head.test(input.head());
    }

    @Override
    public ItemStack assemble(Input input) {
        return result.create();
    }

    /** 在铸弹台上压，不在合成格里做：不进配方书。 */
    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public boolean showNotification() {
        return false;
    }

    @Override
    public String group() {
        return "";
    }

    @Override
    public RecipeSerializer<AmmoPressRecipe> getSerializer() {
        return AmmoBench.PRESS_SERIALIZER.get();
    }

    @Override
    public RecipeType<AmmoPressRecipe> getType() {
        return AmmoBench.PRESS_RECIPE.get();
    }

    @Override
    public PlacementInfo placementInfo() {
        return PlacementInfo.NOT_PLACEABLE;
    }

    @Override
    public RecipeBookCategory recipeBookCategory() {
        return AmmoBench.BOOK_CATEGORY.get();
    }
}
