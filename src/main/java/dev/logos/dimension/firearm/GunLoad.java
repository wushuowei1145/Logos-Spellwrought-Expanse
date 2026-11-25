package dev.logos.dimension.firearm;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * 铳的装弹状态，存在铳这件物品上（随物品走、存档、同步给看得见的玩家）。
 * <ul>
 *   <li>{@code selected}：选中的弹药（一件物品的样子，带组件：以后写了不同术式的术式弹是不同的弹药），短按 R 装它；空表示还没选过。</li>
 *   <li>{@code loaded}：膛里装的是哪种弹药；一个转轮（弹匣）只装一种，换种类就整个换。</li>
 *   <li>{@code rounds}：还剩几发。</li>
 * </ul>
 * 物品样子的数量恒为 1。
 */
public record GunLoad(ItemStack selected, ItemStack loaded, int rounds) {
    public static final GunLoad EMPTY = new GunLoad(ItemStack.EMPTY, ItemStack.EMPTY, 0);
    public static final Codec<GunLoad> CODEC = RecordCodecBuilder.create(i -> i.group(
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("selected", ItemStack.EMPTY).forGetter(GunLoad::selected),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("loaded", ItemStack.EMPTY).forGetter(GunLoad::loaded),
            Codec.intRange(0, 255).optionalFieldOf("rounds", 0).forGetter(GunLoad::rounds)
    ).apply(i, GunLoad::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, GunLoad> STREAM_CODEC = StreamCodec.composite(
            ItemStack.OPTIONAL_STREAM_CODEC, GunLoad::selected,
            ItemStack.OPTIONAL_STREAM_CODEC, GunLoad::loaded,
            ByteBufCodecs.VAR_INT, GunLoad::rounds,
            GunLoad::new);

    public GunLoad {
        selected = selected.isEmpty() ? ItemStack.EMPTY : selected.copyWithCount(1);
        loaded = loaded.isEmpty() || rounds <= 0 ? ItemStack.EMPTY : loaded.copyWithCount(1);
        rounds = loaded.isEmpty() ? 0 : rounds;
    }

    public GunLoad withSelected(ItemStack ammo) { return new GunLoad(ammo, loaded, rounds); }
    public GunLoad withRounds(int n) { return new GunLoad(selected, loaded, n); }

    /** 物品组件按内容比较（{@link ItemStack} 自己不按内容比较），同样的状态不重复同步。 */
    @Override public boolean equals(Object o) {
        return o instanceof GunLoad other && rounds == other.rounds
                && ItemStack.isSameItemSameComponents(selected, other.selected) && ItemStack.isSameItemSameComponents(loaded, other.loaded);
    }
    @Override public int hashCode() {
        return 31 * (31 * ItemStack.hashItemAndComponents(selected) + ItemStack.hashItemAndComponents(loaded)) + rounds;
    }
}
