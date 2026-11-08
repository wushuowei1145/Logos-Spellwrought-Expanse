package dev.logos.dimension.cloudsea;

import dev.logos.dimension.DimensionBlocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 灵狐樱野（云海白樱群系）和风特色建材与核心构件注册。
 */
public final class FoxWildsBlocks {
    public static final List<DeferredBlock<?>> ALL = new ArrayList<>();

    // 1. 白灰泥墙
    public static final DeferredBlock<Block> WHITE_PLASTER = register(
            "journey_white_plaster",
            Block::new,
            p -> p.mapColor(MapColor.QUARTZ).strength(1.5f).sound(SoundType.STONE)
    );

    // 2. 木质腰墙
    public static final DeferredBlock<Block> WOOD_WAINSCOT = register(
            "journey_wood_wainscot",
            Block::new,
            p -> p.mapColor(MapColor.COLOR_BROWN).strength(2.0f).sound(SoundType.WOOD)
    );

    // 3. 灰黑陶瓦系列（方块 / 楼梯 / 台阶）
    public static final DeferredBlock<Block> KAWARA_ROOF = register(
            "journey_kawara_roof",
            Block::new,
            p -> p.mapColor(MapColor.COLOR_BLACK).strength(1.8f).sound(SoundType.DEEPSLATE)
    );
    public static final DeferredBlock<StairBlock> KAWARA_STAIRS = register(
            "journey_kawara_stairs",
            p -> new StairBlock(KAWARA_ROOF.get().defaultBlockState(), p),
            p -> p.mapColor(MapColor.COLOR_BLACK).strength(1.8f).sound(SoundType.DEEPSLATE)
    );
    public static final DeferredBlock<SlabBlock> KAWARA_SLAB = register(
            "journey_kawara_slab",
            SlabBlock::new,
            p -> p.mapColor(MapColor.COLOR_BLACK).strength(1.8f).sound(SoundType.DEEPSLATE)
    );

    // 4. 朱漆木系列（原木立柱 / 木板 / 楼梯 / 台阶 / 栅栏 / 栅栏门）
    public static final DeferredBlock<Block> LACQUER_LOG = register(
            "journey_lacquer_log",
            CloudseaBlocks.FlammableLogBlock::new,
            p -> p.mapColor(MapColor.COLOR_RED).strength(2.0f).sound(SoundType.WOOD)
    );
    public static final DeferredBlock<Block> LACQUER_PLANKS = register(
            "journey_lacquer_planks",
            Block::new,
            p -> p.mapColor(MapColor.COLOR_RED).strength(2.0f).sound(SoundType.WOOD)
    );
    public static final DeferredBlock<StairBlock> LACQUER_STAIRS = register(
            "journey_lacquer_stairs",
            p -> new StairBlock(LACQUER_PLANKS.get().defaultBlockState(), p),
            p -> p.mapColor(MapColor.COLOR_RED).strength(2.0f).sound(SoundType.WOOD)
    );
    public static final DeferredBlock<SlabBlock> LACQUER_SLAB = register(
            "journey_lacquer_slab",
            SlabBlock::new,
            p -> p.mapColor(MapColor.COLOR_RED).strength(2.0f).sound(SoundType.WOOD)
    );
    public static final DeferredBlock<FenceBlock> LACQUER_FENCE = register(
            "journey_lacquer_fence",
            FenceBlock::new,
            p -> p.mapColor(MapColor.COLOR_RED).strength(2.0f).sound(SoundType.WOOD)
    );
    public static final DeferredBlock<FenceGateBlock> LACQUER_FENCE_GATE = register(
            "journey_lacquer_fence_gate",
            p -> new FenceGateBlock(WoodType.OAK, p),
            p -> p.mapColor(MapColor.COLOR_RED).strength(2.0f).sound(SoundType.WOOD)
    );

    // 5. 榻榻米（方块 / 半砖台阶）
    public static final DeferredBlock<RotatedPillarBlock> TATAMI = register(
            "journey_tatami",
            RotatedPillarBlock::new,
            p -> p.mapColor(MapColor.SAND).strength(0.8f).sound(SoundType.GRASS)
    );
    public static final DeferredBlock<SlabBlock> TATAMI_SLAB = register(
            "journey_tatami_slab",
            SlabBlock::new,
            p -> p.mapColor(MapColor.SAND).strength(0.8f).sound(SoundType.GRASS)
    );

    // 6. 障子拉门与木格纸窗
    public static final DeferredBlock<DoorBlock> SHOJI_DOOR = register(
            "journey_shoji_door",
            p -> new DoorBlock(BlockSetType.OAK, p),
            p -> p.mapColor(MapColor.WOOD).strength(1.5f).sound(SoundType.WOOD).noOcclusion()
    );
    public static final DeferredBlock<ShojiWindowBlock> SHOJI_WINDOW = register(
            "journey_shoji_window",
            ShojiWindowBlock::new,
            p -> p.mapColor(MapColor.WOOD).strength(0.8f).sound(SoundType.WOOD).noOcclusion()
    );

    // 7. 照明（和纸灯笼 / 矮石灯笼 / 春日高石灯笼）
    public static final DeferredBlock<LanternBlock> PAPER_LANTERN = register(
            "journey_paper_lantern",
            LanternBlock::new,
            p -> p.mapColor(MapColor.SAND).strength(0.5f).sound(SoundType.WOOD).lightLevel(s -> 14).noOcclusion()
    );
    public static final DeferredBlock<StoneLanternBlock> STONE_LANTERN = register(
            "journey_stone_lantern",
            StoneLanternBlock::new,
            p -> p.mapColor(MapColor.STONE).strength(2.0f).sound(SoundType.STONE).lightLevel(s -> 13).noOcclusion()
    );
    public static final DeferredBlock<TallStoneLanternBlock> STONE_LANTERN_TALL = register(
            "journey_stone_lantern_tall",
            TallStoneLanternBlock::new,
            p -> p.mapColor(MapColor.STONE).strength(2.0f).sound(SoundType.STONE)
                    .lightLevel(s -> s.getValue(TallStoneLanternBlock.HALF) == DoubleBlockHalf.UPPER ? 14 : 0)
                    .noOcclusion()
    );

    // 8. 温泉与庭院神圣装置（Phase 2）
    public static final DeferredBlock<HotSpringStoneBlock> HOT_SPRING_STONE = register(
            "journey_hot_spring_stone",
            HotSpringStoneBlock::new,
            p -> p.mapColor(MapColor.COLOR_BLACK).strength(2.5f).sound(SoundType.STONE)
    );
    public static final DeferredBlock<FoxStatueBlock> FOX_STATUE = register(
            "journey_fox_statue",
            FoxStatueBlock::new,
            p -> p.mapColor(MapColor.STONE).strength(2.0f).sound(SoundType.STONE).noOcclusion()
    );
    public static final DeferredBlock<StoneBasinBlock> STONE_BASIN = register(
            "journey_stone_basin",
            StoneBasinBlock::new,
            p -> p.mapColor(MapColor.STONE).strength(2.0f).sound(SoundType.STONE).noOcclusion()
    );
    public static final DeferredBlock<BambooSpoutBlock> BAMBOO_SPOUT = register(
            "journey_bamboo_spout",
            BambooSpoutBlock::new,
            p -> p.mapColor(MapColor.PLANT).strength(1.0f).sound(SoundType.BAMBOO).noOcclusion()
    );
    public static final DeferredBlock<SteppingStoneBlock> STEPPING_STONE = register(
            "journey_stepping_stone",
            SteppingStoneBlock::new,
            p -> p.mapColor(MapColor.STONE).strength(1.2f).sound(SoundType.STONE).noOcclusion()
    );
    public static final DeferredBlock<ShrineHokoraBlock> SHRINE_HOKORA = register(
            "journey_shrine_hokora",
            ShrineHokoraBlock::new,
            p -> p.mapColor(MapColor.STONE).strength(2.0f).sound(SoundType.STONE).noOcclusion()
                .lightLevel(state -> state.getValue(ShrineHokoraBlock.ACTIVE) ? ShrineHokoraBlock.ACTIVE_LIGHT : 0)
    );
    public static final DeferredBlock<NorenBlock> NOREN = register(
            "journey_noren",
            NorenBlock::new,
            p -> p.mapColor(MapColor.WOOL).strength(0.5f).sound(SoundType.WOOL).noOcclusion().noCollision()
    );
    // 暖帘的另外三色：店家用颜色区分身份
    public static final DeferredBlock<NorenBlock> NOREN_RED = noren("journey_noren_red", MapColor.COLOR_RED);
    public static final DeferredBlock<NorenBlock> NOREN_WHITE = noren("journey_noren_white", MapColor.SNOW);
    public static final DeferredBlock<NorenBlock> NOREN_BROWN = noren("journey_noren_brown", MapColor.COLOR_BROWN);

    public static final DeferredBlock<ShimenawaBlock> SHIMENAWA = register(
            "journey_shimenawa",
            ShimenawaBlock::new,
            p -> p.mapColor(MapColor.SAND).strength(0.5f).sound(SoundType.GRASS).noOcclusion().noCollision()
    );

    // 9. 神社用具：绘马挂（可叠 1–4 层、挂三块玩家绘马）、赛钱箱、鈴緒
    public static final DeferredBlock<EmaRackBlock> EMA_RACK = register(
            "journey_ema_rack",
            EmaRackBlock::new,
            p -> p.mapColor(MapColor.COLOR_BROWN).strength(1.5f).sound(SoundType.WOOD).noOcclusion()
    );
    public static final DeferredBlock<OfferingBoxBlock> OFFERING_BOX = register(
            "journey_offering_box",
            OfferingBoxBlock::new,
            p -> p.mapColor(MapColor.COLOR_BROWN).strength(2.0f).sound(SoundType.WOOD).noOcclusion()
    );
    public static final DeferredBlock<SuzuRopeBlock> SUZU_ROPE = register(
            "journey_suzu_rope",
            SuzuRopeBlock::new,
            p -> p.mapColor(MapColor.COLOR_RED).strength(0.3f).sound(SoundType.WOOL).noOcclusion().noCollision()
    );
    // 10. 抽签：签箱、结签架与签纸；居酒屋的菜单条
    public static final DeferredBlock<OmikujiBoxBlock> OMIKUJI_BOX = register(
            "journey_omikuji_box",
            OmikujiBoxBlock::new,
            p -> p.mapColor(MapColor.COLOR_RED).strength(1.5f).sound(SoundType.WOOD).noOcclusion()
    );
    public static final DeferredBlock<OmikujiRackBlock> OMIKUJI_RACK = register(
            "journey_omikuji_rack",
            OmikujiRackBlock::new,
            p -> p.mapColor(MapColor.COLOR_BROWN).strength(1.0f).sound(SoundType.WOOD).noOcclusion()
    );
    public static final DeferredBlock<MenuStripsBlock> MENU_STRIPS = register(
            "journey_menu_strips",
            MenuStripsBlock::new,
            p -> p.mapColor(MapColor.SAND).strength(0.3f).sound(SoundType.WOOL).noOcclusion().noCollision()
    );
    // 11. 地下封印场地：封印杭（九根封着杀生石里的九尾狐）与贴在墙上的御札
    public static final DeferredBlock<SealStakeBlock> SEAL_STAKE = register(
            "journey_seal_stake",
            SealStakeBlock::new,
            p -> p.mapColor(MapColor.COLOR_BLACK).strength(3.0f, 1200.0f).sound(SoundType.WOOD).noOcclusion()
                .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK)
                .lightLevel(s -> s.getValue(SealStakeBlock.HALF) == DoubleBlockHalf.UPPER ? 7 : 0)
    );
    public static final DeferredBlock<OfudaBlock> OFUDA = register(
            "journey_ofuda",
            OfudaBlock::new,
            p -> p.mapColor(MapColor.SNOW).strength(0.2f).sound(SoundType.WOOL).noOcclusion().noCollision()
                .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)
    );
    /** 签纸：从签箱抽出，名字就是签运；系到结签架上解霉运。 */
    public static final DeferredItem<net.minecraft.world.item.Item> OMIKUJI = DimensionBlocks.ITEMS.registerSimpleItem("journey_omikuji", p -> p.stacksTo(16));
    /** 绘马：右键写下愿望，写好的挂到绘马挂上。 */
    public static final DeferredItem<EmaItem> EMA = DimensionBlocks.ITEMS.registerItem("journey_ema", EmaItem::new, p -> p.stacksTo(16));

    private static DeferredBlock<NorenBlock> noren(String id, MapColor color) {
        return register(id, NorenBlock::new, p -> p.mapColor(color).strength(0.5f).sound(SoundType.WOOL).noOcclusion().noCollision());
    }

    private static <T extends Block> DeferredBlock<T> register(
            String id,
            java.util.function.Function<Block.Properties, T> factory,
            java.util.function.UnaryOperator<Block.Properties> properties
    ) {
        DeferredBlock<T> block = DimensionBlocks.BLOCKS.registerBlock(id, factory, properties);
        DimensionBlocks.ITEMS.registerSimpleBlockItem(id, block);
        ALL.add(block);
        return block;
    }

    public static void init() {
    }

    private FoxWildsBlocks() {
    }
}
