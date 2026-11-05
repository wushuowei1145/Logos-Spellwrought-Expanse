package dev.logos.dimension.fox;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * 聚落的居民（{@link SanctuaryResidents}）：生成时定好的街上落脚点，每个区块生成到它时在那里放一只神狐。
 * 排在聚落所有部件之后，地面和房屋都已放好。范围是整个核心区。
 */
public class SanctuaryResidentsPiece extends StructurePiece {
    private final long sanctuary;
    private final List<BlockPos> spots;

    SanctuaryResidentsPiece(long sanctuary, List<BlockPos> spots, BoundingBox box) {
        super(FoxSanctuary.RESIDENTS.get(), 0, box);
        this.sanctuary = sanctuary;
        this.spots = List.copyOf(spots);
    }

    public SanctuaryResidentsPiece(CompoundTag tag) {
        super(FoxSanctuary.RESIDENTS.get(), tag);
        sanctuary = tag.getLongOr("Sanctuary", 0);
        List<BlockPos> list = new ArrayList<>();
        for (long p : tag.getLongArray("Spots").orElse(new long[0])) list.add(BlockPos.of(p));
        spots = List.copyOf(list);
    }

    /** 这处聚落的标识（结构起点的 BlockPos.asLong）。 */
    public long sanctuary() {
        return sanctuary;
    }

    /** 生成时放下的只数。 */
    public int planned() {
        return spots.size();
    }

    public List<BlockPos> spots() {
        return spots;
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putLong("Sanctuary", sanctuary);
        tag.putLongArray("Spots", spots.stream().mapToLong(BlockPos::asLong).toArray());
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random,
                            BoundingBox chunkBB, ChunkPos chunkPos, BlockPos referencePos) {
        for (BlockPos spot : spots) {
            if (chunkBB.isInside(spot)) SanctuaryResidents.spawn(level, spot, sanctuary);
        }
    }
}
