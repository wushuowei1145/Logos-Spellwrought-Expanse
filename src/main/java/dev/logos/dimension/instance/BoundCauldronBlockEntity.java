package dev.logos.dimension.instance;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/** Persistent world identity; unloading a chunk must never count as destroying the cauldron. */
public final class BoundCauldronBlockEntity extends BlockEntity {
    private @Nullable UUID worldId;
    private boolean beingMined;
    private boolean creativeMining;

    public BoundCauldronBlockEntity(BlockPos pos, BlockState state) {
        super(CauldronBinding.BLOCK_ENTITY.get(), pos, state);
    }

    public @Nullable UUID worldId() { return worldId; }

    public void setWorldId(UUID id) {
        worldId = id;
        setChanged();
    }

    public void setBeingMined(boolean mining, boolean creative) {
        beingMined = mining;
        creativeMining = creative;
    }

    public ItemStack boundItem() {
        var stack = new ItemStack(getBlockState().getBlock());
        if (worldId != null) stack.set(CauldronBinding.WORLD_ID.get(), worldId);
        return stack;
    }

    @Override protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        worldId = input.read("WorldId", UUIDUtil.CODEC).orElse(null);
    }

    @Override protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (worldId != null) output.store("WorldId", UUIDUtil.CODEC, worldId);
    }

    @Override protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        worldId = components.get(CauldronBinding.WORLD_ID.get());
    }

    @Override protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (worldId != null) components.set(CauldronBinding.WORLD_ID.get(), worldId);
    }

    @Override public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("WorldId");
    }

    @Override public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (worldId == null || !(level instanceof ServerLevel server)) return;
        if (beingMined) {
            // Creative normally suppresses block loot; keep the sole bound portal recoverable.
            if (creativeMining) Block.popResource(server, pos, boundItem());
        } else {
            CauldronWorlds.requestDestroy(server.getServer(), worldId);
        }
    }
}
