package dev.logos.dimension.corruption;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;

/**
 * 腐败：世界观里的反派，像幽匿一样蔓延、吞噬活物。腐败核心是一个网络的中心，腐败脉络和腐败污秽是它长出来的部分。
 * 玩法见 docs/CORRUPTION.md。
 */
public final class Corruption {
    private Corruption() {}

    public static final DeferredBlock<CorruptionCoreBlock> CORE = DimensionBlocks.BLOCKS.registerBlock("corruption_core", CorruptionCoreBlock::new,
            p -> p.mapColor(MapColor.COLOR_PURPLE).strength(4f, 1200f).sound(SoundType.SCULK_CATALYST).lightLevel(s -> 6).pushReaction(PushReaction.BLOCK));
    public static final DeferredBlock<CorruptionBlock> BLOCK = DimensionBlocks.BLOCKS.registerBlock("corruption", CorruptionBlock::new,
            p -> p.mapColor(MapColor.COLOR_PURPLE).strength(.6f).sound(SoundType.SCULK));
    public static final DeferredBlock<CorruptionVeinBlock> VEIN = DimensionBlocks.BLOCKS.registerBlock("corruption_vein", CorruptionVeinBlock::new,
            p -> p.mapColor(MapColor.COLOR_PURPLE).forceSolidOn().noCollision().strength(.2f).sound(SoundType.SCULK_VEIN).pushReaction(PushReaction.DESTROY));

    /** 脉络能把哪些方块变成腐败污秽：默认就是原版的 {@code sculk_replaceable}。 */
    public static final TagKey<Block> REPLACEABLE = TagKey.create(Registries.BLOCK, LogosDimension.id("corruption_replaceable"));
    /** 腐败造成的伤害：魔法，无视盔甲，不击退；死亡信息另写。 */
    public static final ResourceKey<DamageType> DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, LogosDimension.id("corruption"));

    public static void init() {
        DimensionBlocks.ITEMS.registerSimpleBlockItem("corruption_core", CORE);
        DimensionBlocks.ITEMS.registerSimpleBlockItem("corruption", BLOCK);
        DimensionBlocks.ITEMS.registerSimpleBlockItem("corruption_vein", VEIN);
    }

    /** 算网络一部分、碰到会受伤的方块（核心不算）。 */
    public static boolean isNetworkBlock(BlockState state) {
        return state.is(BLOCK.get()) || state.is(VEIN.get());
    }
}
