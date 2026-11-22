package dev.logos.dimension.jiansui;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** 遗剑方块里记的只有「什么时候插下的」：剑身上那点淡青白的光从这一刻起慢慢暗下去。 */
public class AxialSwordBlockEntity extends BlockEntity {
    /** 插下的游戏刻（没记的当作早就暗了）。 */
    private long placed = Long.MIN_VALUE / 2;

    public AxialSwordBlockEntity(BlockPos pos, BlockState state) {
        super(JianSuiBlocks.AXIAL_SWORD_ENTITY.get(), pos, state);
    }

    public long placed() {
        return placed;
    }

    public void setPlaced(long tick) {
        placed = tick;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        placed = input.getLongOr("Placed", Long.MIN_VALUE / 2);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putLong("Placed", placed);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }
}
