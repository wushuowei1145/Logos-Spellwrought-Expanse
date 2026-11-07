package dev.logos.dimension.camera;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.screenfx.ScreenEffects;
import dev.logos.dimension.screenfx.ScreenFlash;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;

/**
 * 运镜系统的纯测试方块，也是方块接运镜的最小样例：只能用镐挖，挖掉后镜头升到玩家头顶看向它原来的位置，
 * 停三秒再回到玩家；潜行时挖掉则加上黑白闪、信箱遮幅和电影蒙版，用来试这些选项。贴图借原版结构方块的，不算正式内容，发布前可以连注册一起拿掉。
 */
public final class CameraTestBlock extends Block {
    /** 爆炸炸不掉、活塞推不动：「只能用镐」就是字面意思。 */
    public static final DeferredBlock<CameraTestBlock> BLOCK = DimensionBlocks.BLOCKS.registerBlock("camera_test_block", CameraTestBlock::new,
            p -> p.strength(1.5f, 3600000f).requiresCorrectToolForDrops().pushReaction(PushReaction.BLOCK));
    /** 机位比玩家眼睛高多少，被上方的方块挡住就矮一些。 */
    private static final double HEIGHT = 10;
    private static final int BLEND = 24, HOLD = 60;

    public CameraTestBlock(Properties properties) {
        super(properties);
    }

    public static void init() {
        DimensionBlocks.ITEMS.registerSimpleBlockItem("camera_test_block", BLOCK);
    }

    private static boolean pickaxe(ItemStack stack) {
        return stack.is(ItemTags.PICKAXES);
    }

    /** 生存模式不拿镐时进度不涨。 */
    @Override protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return pickaxe(player.getMainHandItem()) ? super.getDestroyProgress(state, player, level, pos) : 0;
    }

    /** 创造模式不看挖掘进度，一下就碎，所以不拿镐时要在这里拦住。 */
    @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, ItemStack tool, boolean willHarvest, FluidState fluid) {
        if (!pickaxe(tool)) return false;
        if (player instanceof ServerPlayer server) {
            CameraShot shot = CameraShot.of(CameraPoint.world(CameraShots.above(server, HEIGHT)), CameraPoint.world(pos.getCenter())).timing(BLEND, HOLD, BLEND);
            if (server.isShiftKeyDown()) {
                ScreenEffects.flash(server, ScreenFlash.at(CameraPoint.world(pos.getCenter())));
                shot = shot.letterbox().cinema();
            }
            CameraShots.play(server, shot);
        }
        return super.onDestroyedByPlayer(state, level, pos, player, tool, willHarvest, fluid);
    }
}
