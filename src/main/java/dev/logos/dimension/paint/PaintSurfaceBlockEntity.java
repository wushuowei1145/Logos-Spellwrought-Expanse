package dev.logos.dimension.paint;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** 漆面：存一层半透明油漆的 RGB 颜色，同步到客户端做 tint。 */
public class PaintSurfaceBlockEntity extends BlockEntity {
    /** 默认颜色：不染色（tint 乘白色，ARGB 全白）。 */
    public static final int DEFAULT_COLOR = 0xFFFFFFFF;
    private int paint = DEFAULT_COLOR;

    public PaintSurfaceBlockEntity(BlockPos pos, BlockState state) {
        super(PaintSystem.SURFACE_ENTITY.get(), pos, state);
    }

    public int paint() {
        return paint;
    }

    public void setPaint(int color) {
        paint = color;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        paint = input.read("paint", Codec.INT).orElse(DEFAULT_COLOR);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("paint", Codec.INT, paint);
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        Integer color = components.get(PaintSystem.PAINT_COLOR.get());
        if (color != null) {
            paint = color;
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        components.set(PaintSystem.PAINT_COLOR.get(), paint);
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
