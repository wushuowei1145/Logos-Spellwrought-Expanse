package dev.logos.dimension.firearm;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/**
 * 铸弹台改装一批弹（{@code data/<ns>/recipe/…json}，类型 {@code logos_dimension:round_modifying}）：一份添加物改 {@code rounds} 发
 * 普通弹（不写就是八发，最多八发），改成 {@code finish} 那种（{@link RoundEffects}）；{@code remainder} 是添加物用完剩下的（水银瓶剩空瓶）。
 * 改装弹没有术式，是术式弹的下位替代，用物理手段做出某种效果；刻过术式的弹不能改装，改装过的也不能再刻。
 * <pre>{@code
 * {"type": "logos_dimension:round_modifying", "additive": "logos:mercury_bottle", "finish": "mercury",
 *  "remainder": {"id": "minecraft:glass_bottle"}}
 * {"type": "logos_dimension:round_modifying", "additive": "minecraft:ender_pearl", "finish": "ender", "rounds": 1}
 * }</pre>
 */
public record RoundModRecipe(Ingredient additive, String finish, int rounds, Optional<ItemStackTemplate> remainder) implements Recipe<RoundModRecipe.Input> {
    public static final MapCodec<RoundModRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Ingredient.CODEC.fieldOf("additive").forGetter(RoundModRecipe::additive),
            Codec.STRING.fieldOf("finish").forGetter(RoundModRecipe::finish),
            Codec.intRange(1, AmmoBenchBlockEntity.BATCH).optionalFieldOf("rounds", AmmoBenchBlockEntity.BATCH).forGetter(RoundModRecipe::rounds),
            ItemStackTemplate.CODEC.optionalFieldOf("remainder").forGetter(RoundModRecipe::remainder)).apply(i, RoundModRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, RoundModRecipe> STREAM_CODEC = StreamCodec.composite(
            Ingredient.CONTENTS_STREAM_CODEC, RoundModRecipe::additive,
            ByteBufCodecs.STRING_UTF8, RoundModRecipe::finish,
            ByteBufCodecs.VAR_INT, RoundModRecipe::rounds,
            ByteBufCodecs.optional(ItemStackTemplate.STREAM_CODEC), RoundModRecipe::remainder,
            RoundModRecipe::new);
    public static final RecipeSerializer<RoundModRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    /** 铸弹台改装页的两格：要改的弹、添加物。 */
    public record Input(ItemStack round, ItemStack additive) implements RecipeInput {
        @Override
        public ItemStack getItem(int index) {
            return index == 0 ? round : additive;
        }

        @Override
        public int size() {
            return 2;
        }
    }

    @Override
    public boolean matches(Input input, Level level) {
        // 药水弹还要药粉里真有药水效果。
        return additive.test(input.additive()) && RoundEffects.accepts(finish, input.additive());
    }

    /** 改好的一发（数量 1）：弹本身加上外观，烟火之星、药粉还把星的样子、药水带进弹里。 */
    @Override
    public ItemStack assemble(Input input) {
        return RoundEffects.modified(input.round(), input.additive(), finish);
    }

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
    public RecipeSerializer<RoundModRecipe> getSerializer() {
        return AmmoBench.MOD_SERIALIZER.get();
    }

    @Override
    public RecipeType<RoundModRecipe> getType() {
        return AmmoBench.MOD_RECIPE.get();
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
