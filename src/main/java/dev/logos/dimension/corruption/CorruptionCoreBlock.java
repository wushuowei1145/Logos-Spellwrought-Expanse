package dev.logos.dimension.corruption;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.ServerConfig;
import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.camera.CameraShot;
import dev.logos.dimension.camera.CameraShots;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 腐败核心：放下就是一个新网络的中心，记着网络有多大（空手右键看）。被拆掉不掉落；拆它的玩家镜头升起来看着核心的位置，
 * 和运镜测试方块同一个镜头，同时网络里的脉络和腐败污秽从核心开始一圈圈往外消散。
 */
public class CorruptionCoreBlock extends Block {
    public static final MapCodec<CorruptionCoreBlock> CODEC = simpleCodec(CorruptionCoreBlock::new);
    /** 和 {@code CameraTestBlock} 一样：机位在玩家眼睛上方 10 格，升 24 刻、看 60 刻、回 24 刻。 */
    static final double SHOT_HEIGHT = 10;
    static final int SHOT_BLEND = 24, SHOT_HOLD = 60;

    public CorruptionCoreBlock(Properties properties) {
        super(properties);
    }

    @Override protected MapCodec<CorruptionCoreBlock> codec() { return CODEC; }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean movedByPiston) {
        super.onPlace(state, level, pos, old, movedByPiston);
        if (level instanceof ServerLevel server && !old.is(this)) CorruptionNetworks.get(server).open(server, pos);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        CorruptionNetworks.get(level).collapse(level, pos);
    }

    /** 玩家拆核心：先放镜头，方块随后才真的没了（消散在移除回调里开始）。 */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (player instanceof ServerPlayer server && CorruptionNetworks.get(server.level()).ofCore(pos) != null) {
            CameraShots.play(server, shot(server, pos));
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    static CameraShot shot(ServerPlayer player, BlockPos core) {
        return CameraShot.of(CameraPoint.world(CameraShots.above(player, SHOT_HEIGHT)), CameraPoint.world(core.getCenter()))
                .timing(SHOT_BLEND, SHOT_HOLD, SHOT_BLEND);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel server) {
            var network = CorruptionNetworks.get(server).ofCore(pos);
            Component text = network == null
                    ? Component.translatable("message.logos_dimension.corruption.core.dormant")
                    : Component.translatable("message.logos_dimension.corruption.core.size", network.size(), ServerConfig.CORRUPTION_MAX_BLOCKS.getAsInt(),
                            CorruptionNetworks.get(server).satellites(network), network.charge(), network.reserve());
            player.sendOverlayMessage(text.copy().withStyle(ChatFormatting.DARK_PURPLE));
        }
        return InteractionResult.SUCCESS;
    }
}
