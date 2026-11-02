package dev.logos.dimension.cloudsea;

import dev.logos.dimension.fox.FoxSanctuary;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ListBackedContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** 绘马挂上玩家挂的三块绘马；作为容器，拆掉时原版会把它们掉出来。 */
public class EmaRackBlockEntity extends BlockEntity implements ListBackedContainer {
    public static final int SLOTS = 3;
    private final NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);

    public EmaRackBlockEntity(BlockPos pos, BlockState state) {
        super(FoxSanctuary.EMA_RACK.get(), pos, state);
    }

    /** 挂到第一个空位；挂满返回 false。 */
    public boolean hang(ItemStack ema) {
        for (int i = 0; i < SLOTS; i++) {
            if (items.get(i).isEmpty()) {
                items.set(i, ema);
                changed();
                return true;
            }
        }
        return false;
    }

    ItemStack takeLast() {
        for (int i = SLOTS - 1; i >= 0; i--) {
            if (!items.get(i).isEmpty()) {
                ItemStack out = items.get(i);
                items.set(i, ItemStack.EMPTY);
                changed();
                return out;
            }
        }
        return ItemStack.EMPTY;
    }

    private void changed() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    public NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items.clear();
        ContainerHelper.loadAllItems(input, items);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items, true);
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
