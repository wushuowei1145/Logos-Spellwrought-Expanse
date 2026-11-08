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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 腐败核心：放下就是一个新网络的中心，记着网络有多大（空手右键看）。被拆掉不掉落；拆它的玩家镜头升起来看着核心的位置，
 * 和运镜测试方块同一个镜头，同时网络里的脉络和腐败污秽从核心开始一圈圈往外消散。
 *
 * <p>外形是客户端画的肉瘤（{@code client/corruption/CorruptionCoreRenderer}），随网络大小分四级：{@link #STAGE} 0 半格、1 一格、
 * 2 两格、3 三格并且正面裂开露出眼珠。方块本身不画；碰撞箱 0 级是半格，之后是一整格，外面的肉只是画出来的。
 */
public class CorruptionCoreBlock extends Block implements EntityBlock {
    public static final MapCodec<CorruptionCoreBlock> CODEC = simpleCodec(CorruptionCoreBlock::new);
    /** 和 {@code CameraTestBlock} 一样：机位在玩家眼睛上方 10 格，升 24 刻、看 60 刻、回 24 刻。 */
    static final double SHOT_HEIGHT = 10;
    static final int SHOT_BLEND = 24, SHOT_HOLD = 60;
    /** 成长阶段，服务端按网络大小设（{@link CorruptionNetworks}），客户端按它选造型。 */
    public static final IntegerProperty STAGE = IntegerProperty.create("stage", 0, 3);
    private static final VoxelShape SMALL = Block.box(4, 0, 4, 12, 8, 12);

    public CorruptionCoreBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(STAGE, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(STAGE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(STAGE) == 0 ? SMALL : Shapes.block();
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CorruptionCoreBlockEntity(pos, state);
    }

    /** 只有客户端走刻：眼珠和身体转向最近的玩家、偶尔眨眼。 */
    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (!level.isClientSide() || type != Corruption.CORE_ENTITY.get()) return null;
        return (l, p, s, be) -> ((CorruptionCoreBlockEntity) be).clientTick();
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
