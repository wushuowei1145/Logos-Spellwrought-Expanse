package dev.logos.dimension.instance;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** Damage destruction is distinct from pickup, merging, transport and chunk unloading. */
public final class BoundCauldronItem extends BlockItem {
    public BoundCauldronItem(Block block, Properties properties) { super(block, properties); }

    @Override public void onDestroyed(ItemEntity entity, DamageSource damageSource) {
        var id = entity.getItem().get(CauldronBinding.WORLD_ID.get());
        if (id != null && entity.level() instanceof ServerLevel server) {
            CauldronWorlds.requestDestroy(server.getServer(), id);
        }
    }

    @Override public boolean onEntityItemUpdate(ItemStack stack, ItemEntity entity) {
        // Entity.checkBelowWorld discards without damage, so it never calls onDestroyed.
        if (entity.level() instanceof ServerLevel server && entity.getY() < server.getMinY() - 64) {
            var id = stack.get(CauldronBinding.WORLD_ID.get());
            if (id != null) CauldronWorlds.requestDestroy(server.getServer(), id);
        }
        return false;
    }
}
