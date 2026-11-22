package dev.logos.dimension.jiansui;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 剑祟这一战留下的方块（docs/JIAN_SUI_BOSS.md 第 12 节）。 */
public final class JianSuiBlocks {
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, LogosDimension.ID);

    /** 遗剑：硬度 -1 同基岩，爆炸、活塞、法术都拿它没办法；没有物品形态，不掉落。 */
    public static final DeferredBlock<AxialSwordBlock> AXIAL_SWORD = DimensionBlocks.BLOCKS.registerBlock("jian_sui_axial_sword",
            AxialSwordBlock::new, p -> p.mapColor(MapColor.METAL).sound(SoundType.CHAIN).strength(-1f, 3600000f)
                    .noLootTable().noOcclusion().pushReaction(PushReaction.BLOCK));

    /**
     * 剑格：第三阶段扎住的巨剑剑格那一格临时放的碰撞方块（docs/JIAN_SUI_BOSS.md 第 10.3 节），看不见，8 秒后由场地拆掉。
     * 打不坏、不掉落；读档时由场地一律拆掉。
     */
    public static final DeferredBlock<SwordGuardBlock> SWORD_GUARD = DimensionBlocks.BLOCKS.registerBlock("jian_sui_sword_guard",
            SwordGuardBlock::new, p -> p.mapColor(MapColor.NONE).sound(SoundType.CHAIN).strength(-1f, 3600000f)
                    .noLootTable().noOcclusion().pushReaction(PushReaction.BLOCK));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AxialSwordBlockEntity>> AXIAL_SWORD_ENTITY =
            BLOCK_ENTITIES.register("jian_sui_axial_sword", () -> new BlockEntityType<>(AxialSwordBlockEntity::new, AXIAL_SWORD.get()));

    private JianSuiBlocks() {}

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
    }
}
