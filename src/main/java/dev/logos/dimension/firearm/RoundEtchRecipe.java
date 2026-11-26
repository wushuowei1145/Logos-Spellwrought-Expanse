package dev.logos.dimension.firearm;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemStack;
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
 * 铸弹台蚀刻术式弹的一档（{@code data/<ns>/recipe/…json}，类型 {@code logos_dimension:round_etching}）：
 * 催化（可以不要）和导体各一份，刻一批弹（最多八发）；{@code tier} 1–7 照 Logos 载页的基础到贤者，
 * {@code capacity} 是这一档每发普通弹能注入的魔力（空弹头加倍，{@link SpellRounds#capacity}）。
 * <pre>{@code
 * {"type": "logos_dimension:round_etching", "tier": 3, "capacity": 80,
 *  "catalyst": "logos:echo_tincture", "conductor": "logos:living_gold_ingot"}
 * }</pre>
 */
public record RoundEtchRecipe(int tier, int capacity, Optional<Ingredient> catalyst, Ingredient conductor)
        implements Recipe<RoundEtchRecipe.Input> {
    /** 档位数：基础、晶辉、回响、炼成、白垩、天穹、贤者。 */
    public static final int TIERS = 7;
    public static final MapCodec<RoundEtchRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.intRange(1, TIERS).fieldOf("tier").forGetter(RoundEtchRecipe::tier),
            ExtraCodecs.POSITIVE_INT.fieldOf("capacity").forGetter(RoundEtchRecipe::capacity),
            Ingredient.CODEC.optionalFieldOf("catalyst").forGetter(RoundEtchRecipe::catalyst),
            Ingredient.CODEC.fieldOf("conductor").forGetter(RoundEtchRecipe::conductor)).apply(i, RoundEtchRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, RoundEtchRecipe> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RoundEtchRecipe::tier,
            ByteBufCodecs.VAR_INT, RoundEtchRecipe::capacity,
            Ingredient.OPTIONAL_CONTENTS_STREAM_CODEC, RoundEtchRecipe::catalyst,
            Ingredient.CONTENTS_STREAM_CODEC, RoundEtchRecipe::conductor,
            RoundEtchRecipe::new);
    public static final RecipeSerializer<RoundEtchRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    /** 铸弹台的催化格、导体格。 */
    public record Input(ItemStack catalyst, ItemStack conductor) implements RecipeInput {
        @Override
        public ItemStack getItem(int index) {
            return index == 0 ? catalyst : conductor;
        }

        @Override
        public int size() {
            return 2;
        }
    }

    /** 两格里的材料是哪一档；对不上就是 null。只在服务端有配方。 */
    public static @Nullable RoundEtchRecipe find(Level level, Input input) {
        if (!(level.recipeAccess() instanceof RecipeManager recipes)) return null;
        for (RecipeHolder<RoundEtchRecipe> h : recipes.recipeMap().byType(AmmoBench.ETCH_RECIPE.get())) {
            if (h.value().matches(input, level)) return h.value();
        }
        return null;
    }

    /** {@code stack} 能不能放进催化格（{@code catalyst}）或导体格。客户端不知道配方，一律放行。 */
    public static boolean usable(Level level, boolean catalyst, ItemStack stack) {
        if (!(level.recipeAccess() instanceof RecipeManager recipes)) return true;
        for (RecipeHolder<RoundEtchRecipe> h : recipes.recipeMap().byType(AmmoBench.ETCH_RECIPE.get())) {
            var r = h.value();
            if (catalyst ? r.catalyst.map(c -> c.test(stack)).orElse(false) : r.conductor.test(stack)) return true;
        }
        return false;
    }

    @Override
    public boolean matches(Input input, Level level) {
        boolean catalystOk = catalyst.map(c -> c.test(input.catalyst())).orElse(input.catalyst().isEmpty());
        return catalystOk && conductor.test(input.conductor());
    }

    @Override
    public ItemStack assemble(Input input) {
        return ItemStack.EMPTY;
    }

    /** 在铸弹台上刻，不在合成格里做：不进配方书。 */
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
    public RecipeSerializer<RoundEtchRecipe> getSerializer() {
        return AmmoBench.ETCH_SERIALIZER.get();
    }

    @Override
    public RecipeType<RoundEtchRecipe> getType() {
        return AmmoBench.ETCH_RECIPE.get();
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
