package dev.logos.dimension.client.worldtree;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.worldtree.Worldtree;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperty;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** 物品模型条件 {@code logos_dimension:worldtree_sideways}：世界树之弓正在被左键横着拉（换成横过来的拉弓模型）。 */
public record SidewaysProperty() implements ConditionalItemModelProperty {
    public static final Identifier ID = LogosDimension.id("worldtree_sideways");
    public static final MapCodec<SidewaysProperty> CODEC = MapCodec.unit(new SidewaysProperty());

    @Override
    public boolean get(ItemStack stack, ClientLevel level, LivingEntity entity, int seed, ItemDisplayContext context) {
        var draw = stack.get(Worldtree.DRAW.get());
        return draw != null && draw.sideways();
    }

    @Override
    public MapCodec<SidewaysProperty> type() {
        return CODEC;
    }
}
