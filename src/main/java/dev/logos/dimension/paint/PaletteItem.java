package dev.logos.dimension.paint;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/** 调色盘物品：右键空气打开调配页；右键方块照常放置（颜色随数据组件进方块实体）。 */
public class PaletteItem extends BlockItem {
    public PaletteItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            Minecraft.getInstance().setScreen(
                new dev.logos.dimension.client.PaintPaletteScreen(player, hand, player.getItemInHand(hand)));
        }
        return InteractionResult.SUCCESS;
    }
}
