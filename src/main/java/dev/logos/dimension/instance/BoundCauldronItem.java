package dev.logos.dimension.instance;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import java.util.function.Consumer;

/** Damage destruction is distinct from pickup, merging, transport and chunk unloading. */
public final class BoundCauldronItem extends BlockItem {
    public BoundCauldronItem(Block block, Properties properties) { super(block, properties); }
    @Override public Component getName(ItemStack stack){
        var name=super.getName(stack);var id=stack.get(CauldronBinding.WORLD_ID.get());
        return id==null?name:name.copy().append(" ["+id+"]");
    }
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,TooltipDisplay display,Consumer<Component> builder,TooltipFlag flag){
        super.appendHoverText(stack,context,display,builder,flag);
        var id=stack.get(CauldronBinding.WORLD_ID.get());
        builder.accept(id==null?Component.translatable("message.logos_dimension.unbound_cauldron"):
                Component.translatable("message.logos_dimension.bound_dimension",CauldronWorlds.key(id).identifier().toString()));
    }

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
