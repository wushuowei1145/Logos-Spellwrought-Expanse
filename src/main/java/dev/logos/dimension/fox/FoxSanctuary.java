package dev.logos.dimension.fox;

import dev.logos.dimension.LogosDimension;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 神狐聚落：灵狐樱野山顶的和风聚落与上山小路。
 *
 * <p>这是本附属唯一的 Java 结构类型（维护者为它破例，其他结构仍用原版数据包）：
 * 原版拼接无法按真实坡度铺山路，Beardifier 也只能削不能填。建筑仍是代码生成的 NBT 模板，
 * 这里只负责选址、整形山体、铺山路与溪流，并按设计数据摆放模板。
 */
public final class FoxSanctuary {
    private static final DeferredRegister<StructureType<?>> TYPES = DeferredRegister.create(Registries.STRUCTURE_TYPE, LogosDimension.ID);
    private static final DeferredRegister<StructurePieceType> PIECES = DeferredRegister.create(Registries.STRUCTURE_PIECE, LogosDimension.ID);

    public static final DeferredHolder<StructureType<?>, StructureType<FoxSanctuaryStructure>> TYPE =
        TYPES.register("fox_sanctuary", () -> () -> FoxSanctuaryStructure.CODEC);
    public static final DeferredHolder<StructurePieceType, StructurePieceType> GROUNDS =
        PIECES.register("fox_sanctuary_grounds", () -> (StructurePieceType.ContextlessType) SanctuaryGroundsPiece::new);
    public static final DeferredHolder<StructurePieceType, StructurePieceType> TEMPLATE =
        PIECES.register("fox_sanctuary_template", () -> (StructurePieceType.StructureTemplateType) SanctuaryTemplatePiece::new);

    public static void register(IEventBus bus) {
        TYPES.register(bus);
        PIECES.register(bus);
    }

    private FoxSanctuary() {
    }
}
