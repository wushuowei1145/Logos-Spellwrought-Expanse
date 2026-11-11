package dev.logos.dimension.taixuan;

import dev.logos.dimension.LogosDimension;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 太玄灵境的注册：只有选址用的结构类型；方块在 {@link TaixuanBlocks}。 */
public final class Taixuan {
    private static final DeferredRegister<StructureType<?>> STRUCTURES = DeferredRegister.create(Registries.STRUCTURE_TYPE, LogosDimension.ID);
    public static final DeferredHolder<StructureType<?>, StructureType<TaixuanStructure>> STRUCTURE =
        STRUCTURES.register("taixuan_sanctuary", () -> () -> TaixuanStructure.CODEC);

    public static void register(IEventBus bus) {
        STRUCTURES.register(bus);
    }

    private Taixuan() {}
}
