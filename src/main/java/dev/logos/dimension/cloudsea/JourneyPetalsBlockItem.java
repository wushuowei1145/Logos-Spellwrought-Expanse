package dev.logos.dimension.cloudsea;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 落樱瓣物品：
 * <ul>
 *   <li>右键陆地方块或已有花瓣时，正常执行放置 / 堆叠</li>
 *   <li>右键水面时，通过流体投射检测水源并在水面上方放置浮水花瓣</li>
 * </ul>
 */
public class JourneyPetalsBlockItem extends BlockItem {
    public JourneyPetalsBlockItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        BlockHitResult hitResult = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockAboveResult = hitResult.withPosition(hitResult.getBlockPos().above());
            InteractionResult result = super.useOn(new UseOnContext(player, hand, blockAboveResult));
            if (result.consumesAction()) {
                return result;
            }
        }
        return super.use(level, player, hand);
    }
}
