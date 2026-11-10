package dev.logos.dimension.music;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * 复制纸带：一卷打孔纸带 + 若干空白纸带 → 同样多的打孔纸带，原纸带留在合成格里（和原版复制成书一样）。
 * 复制出的纸带指向同一份内容；内容不可修改，所以互不影响。
 */
public class RollCopyRecipe extends CustomRecipe {
    public static final RollCopyRecipe INSTANCE = new RollCopyRecipe();
    public static final RecipeSerializer<RollCopyRecipe> SERIALIZER =
        new RecipeSerializer<>(MapCodec.unit(INSTANCE), StreamCodec.<RegistryFriendlyByteBuf, RollCopyRecipe>unit(INSTANCE));

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return !assemble(input).isEmpty();
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        ItemStack source = ItemStack.EMPTY;
        int blanks = 0;
        for (int i = 0; i < input.size(); i++) {
            ItemStack s = input.getItem(i);
            if (s.isEmpty()) continue;
            if (s.is(Instruments.MUSIC_ROLL_ITEM.get()) && s.has(Instruments.MUSIC_ROLL.get())) {
                if (!source.isEmpty()) return ItemStack.EMPTY;
                source = s;
            } else if (s.is(Instruments.BLANK_ROLL.get())) {
                blanks++;
            } else {
                return ItemStack.EMPTY;
            }
        }
        if (source.isEmpty() || blanks == 0) return ItemStack.EMPTY;
        return source.copyWithCount(blanks);
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        NonNullList<ItemStack> result = NonNullList.withSize(input.size(), ItemStack.EMPTY);
        for (int i = 0; i < input.size(); i++) {
            ItemStack s = input.getItem(i);
            if (s.is(Instruments.MUSIC_ROLL_ITEM.get()) && s.has(Instruments.MUSIC_ROLL.get())) {
                result.set(i, s.copyWithCount(1));
                break;
            }
        }
        return result;
    }

    @Override
    public RecipeSerializer<RollCopyRecipe> getSerializer() {
        return SERIALIZER;
    }
}
