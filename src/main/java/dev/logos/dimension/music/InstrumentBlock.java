package dev.logos.dimension.music;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 乐器方块的共同部分：由主块和若干附属块组成，只有主块有模型和方块实体；右键任意一块都交给主块。
 * 空手右键打开演奏界面；拿着纸带右键把纸带放进（或换进）纸带槽。
 */
public abstract class InstrumentBlock extends Block implements EntityBlock {
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

    protected InstrumentBlock(Properties properties) {
        super(properties);
    }

    public abstract InstrumentKind kind();

    /** 这一格此刻是什么乐器：大多数方块固定不变；乐谱架看放着什么，空着时为 null。 */
    public @Nullable InstrumentKind kind(BlockState state) {
        return kind();
    }

    public abstract boolean isMain(BlockState state);

    /** 这一块所属乐器的主块位置。 */
    public abstract BlockPos mainPos(BlockState state, BlockPos pos);

    /** 声音与粒子的中心（世界坐标）。 */
    protected abstract Vec3 centerOf(BlockState mainState, BlockPos main);

    /** 这件乐器占的全部格子（含主块）。 */
    public abstract Iterable<BlockPos> cells(BlockState mainState, BlockPos main);

    public static Vec3 center(BlockState state, BlockPos main) {
        return state.getBlock() instanceof InstrumentBlock b ? b.centerOf(state, main) : Vec3.atCenterOf(main);
    }

    public static @Nullable InstrumentBlockEntity entityAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof InstrumentBlock b)) return null;
        return level.getBlockEntity(b.mainPos(state, pos)) instanceof InstrumentBlockEntity be ? be : null;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return isMain(state) ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isMain(state) ? new InstrumentBlockEntity(pos, state) : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || !isMain(state) || type != Instruments.INSTRUMENT_ENTITY.get()) return null;
        return (BlockEntityTicker<T>) (BlockEntityTicker<InstrumentBlockEntity>) InstrumentBlockEntity::serverTick;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        BlockPos main = mainPos(state, pos);
        // 潜行空手右键：工位上的生物离开（不开界面）
        if (player.isSecondaryUseActive() && InstrumentPosts.occupant(level, main) instanceof net.minecraft.world.entity.Mob) {
            if (!level.isClientSide()) InstrumentPosts.leave(level, main);
            return InteractionResult.SUCCESS;
        }
        // 空手右键有工位的乐器（大提琴、低音提琴、钟琴、管钟）：到工位上去（已经有人时只开界面），潜行右键只开界面
        if (!level.isClientSide() && !player.isSecondaryUseActive() && InstrumentPosts.has(kind(level.getBlockState(main)))
            && !(player.getVehicle() instanceof InstrumentSeat seat && seat.instrument().equals(main))) {
            InstrumentPosts.take(level, main, player);
        }
        if (level.isClientSide()) {
            // 界面类只在客户端存在：经静态入口打开，本类不出现任何客户端类型
            dev.logos.dimension.client.music.MusicClient.openInstrument(main);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                          InteractionHand hand, BlockHitResult hit) {
        if (stack.is(dev.logos.dimension.music.orchestra.Orchestra.BATON.get())) {
            // 指挥棒：把这件乐器加进（或移出）指挥棒绑定的那个乐团
            if (!level.isClientSide()) {
                dev.logos.dimension.music.orchestra.Orchestra.batonOnInstrument(stack, (net.minecraft.server.level.ServerLevel) level,
                    mainPos(state, pos), player);
            }
            return InteractionResult.SUCCESS;
        }
        if (stack.is(net.minecraft.world.item.Items.LEAD) && InstrumentPosts.has(kind(level.getBlockState(mainPos(state, pos))))
                && InstrumentPosts.leadTo(level, mainPos(state, pos), player)) {
            // 拴绳牵着生物：带到这件乐器的工位上
            return InteractionResult.SUCCESS;
        }
        if (!Instruments.isRoll(stack)) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(mainPos(state, pos)) instanceof InstrumentBlockEntity be)) return InteractionResult.PASS;
        ItemStack old = be.roll();
        be.setRoll(stack.copyWithCount(1));
        if (!player.getAbilities().instabuild) stack.shrink(1);
        if (!old.isEmpty() && !player.getInventory().add(old)) player.drop(old, false);
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation,
                                   boolean movedByPiston) {
        if (level.isClientSide()) return;
        BlockPos main = mainPos(state, pos);
        BlockState mainState = level.getBlockState(main);
        if (!(level.getBlockEntity(main) instanceof InstrumentBlockEntity be)) return;
        boolean signal = false;
        for (BlockPos cell : cells(mainState, main)) signal |= level.hasNeighborSignal(cell);
        be.onSignal(signal);
    }
}
