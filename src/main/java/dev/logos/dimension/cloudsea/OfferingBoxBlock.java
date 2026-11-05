package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.economy.CoinItem;
import dev.logos.dimension.economy.Currency;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 赛钱箱：木箱顶面一排斜格栅。手持金粒、铁粒、铜锭或绿宝石右键投进去，叮当一声。
 *
 * <p>投异世界钱币还会得到保佑：面额越大，福运越久（小钱福运 I；中钱加生命恢复；大钱福运 II、生命恢复与抗性提升），
 * 这里是神狐的地方，狐钱的时长多一半。多投可以累加，总时长不超过 {@link #MAX_SECONDS} 秒。
 */
public class OfferingBoxBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<OfferingBoxBlock> CODEC = simpleCodec(OfferingBoxBlock::new);
    /** 福运累加的上限（一小时）。 */
    public static final int MAX_SECONDS = 3600;
    /** 中钱、大钱的门槛（折成铜分）。 */
    private static final double MID_WORTH = 50, BIG_WORTH = 2000;
    private static final VoxelShape SHAPE_NS = Block.box(1, 0, 3, 15, 10, 13);
    private static final VoxelShape SHAPE_EW = Block.box(3, 0, 1, 13, 10, 15);

    public OfferingBoxBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public MapCodec<OfferingBoxBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.X ? SHAPE_EW : SHAPE_NS;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /**
     * 一枚钱币换到的保佑。
     *
     * @param luck    福运的等级（0 为福运 I）
     * @param seconds 福运的秒数
     * @param regen   带不带生命恢复（福运时长的六分之一）
     * @param resist  带不带抗性提升（同上）
     */
    public record Blessing(int luck, int seconds, boolean regen, boolean resist) {
    }

    /** 按面额折成铜分（开局锚定汇率，不随行情变动）算保佑。 */
    public static Blessing blessing(Currency currency, Currency.Denomination denomination) {
        double worth = denomination.value() * currency.anchor;
        double seconds = 30 * Math.pow(worth, 0.4) * (currency == Currency.FOX ? 1.5 : 1);
        boolean big = worth >= BIG_WORTH;
        return new Blessing(big ? 1 : 0, (int) Math.round(seconds), worth >= MID_WORTH, big);
    }

    /** 接在身上已有的同种效果后面：时长累加，等级取高的。 */
    private static void extend(Player player, Holder<MobEffect> effect, int amplifier, int seconds) {
        MobEffectInstance current = player.getEffect(effect);
        int ticks = seconds * 20;
        if (current != null) {
            ticks += current.getDuration();
            amplifier = Math.max(amplifier, current.getAmplifier());
        }
        player.addEffect(new MobEffectInstance(effect, Math.min(ticks, MAX_SECONDS * 20), amplifier));
    }

    private static void bless(Player player, CoinItem coin, ItemStack stack) {
        Blessing blessing = blessing(coin.currency, coin.denomination);
        extend(player, MobEffects.LUCK, blessing.luck(), blessing.seconds());
        int side = Math.max(20, blessing.seconds() / 6);
        if (blessing.regen()) extend(player, MobEffects.REGENERATION, 0, side);
        if (blessing.resist()) extend(player, MobEffects.RESISTANCE, 0, side);
        player.sendOverlayMessage(Component.translatable("block.logos_dimension.journey_offering_box.blessed", stack.getHoverName(),
            Math.max(1, Math.round(blessing.seconds() / 60f))));
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        boolean coin = stack.getItem() instanceof CoinItem
            || stack.is(Items.GOLD_NUGGET) || stack.is(Items.IRON_NUGGET) || stack.is(Items.EMERALD) || stack.is(ItemTags.COPPER);
        if (!coin) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (level instanceof ServerLevel server) {
            if (stack.getItem() instanceof CoinItem offered) bless(player, offered, stack);
            stack.consume(1, player);
            server.playSound(null, pos, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.7f, 1.6f);
            server.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.5f, 1.4f);
            server.sendParticles(ParticleTypes.WAX_ON, pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, 4, 0.2, 0.05, 0.2, 0.01);
        }
        return InteractionResult.SUCCESS;
    }
}
