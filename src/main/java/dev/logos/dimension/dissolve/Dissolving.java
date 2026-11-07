package dev.logos.dimension.dissolve;

import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 消散的服务端入口：通知客户端播放（{@code client/dissolve/Dissolves}），放完把东西拿走。
 * 生物消散时站住不动；玩家本人不会被拿走，只是短暂不画。用法见 docs/DISSOLVE.md。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class Dissolving {
    private Dissolving() {}

    /** 参考图的红橙色。 */
    public static final int EMBER = 0xFF5A28;

    private record Due(long tick, Runnable run) {}
    private static final List<Due> DUE = new ArrayList<>();

    public static void register(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").playToClient(DissolvePayload.TYPE, DissolvePayload.CODEC));
    }

    public static void entity(Entity entity, DissolveShape shape, int ticks, int color) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, new DissolvePayload(DissolvePayload.ENTITY, entity.getId(), List.of(), List.of(), shape, ticks, color));
        if (entity instanceof Mob mob) mob.setNoAi(true);
        if (!(entity instanceof Player)) after(entity.level().getServer(), ticks, entity::discard);
    }

    /** {@code holder} 这只手上的东西，放完从手上清掉。人不消散。 */
    public static void held(LivingEntity holder, InteractionHand hand, DissolveShape shape, int ticks, int color) {
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? holder.getMainArm() : holder.getMainArm().getOpposite();
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(holder, new DissolvePayload(arm == HumanoidArm.LEFT ? DissolvePayload.LEFT : DissolvePayload.RIGHT,
                holder.getId(), List.of(), List.of(), shape, ticks, color));
        after(holder.level().getServer(), ticks, () -> holder.setItemInHand(hand, ItemStack.EMPTY));
    }

    /** 这些方块算一个目标。方块立刻从世界移除，消散的是留下的影子；空气跳过。 */
    public static void blocks(ServerLevel level, List<BlockPos> positions, DissolveShape shape, int ticks, int color) {
        List<BlockPos> kept = new ArrayList<>();
        List<Integer> states = new ArrayList<>();
        for (BlockPos pos : positions) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            kept.add(pos.immutable());
            states.add(Block.getId(state));
        }
        if (kept.isEmpty()) return;
        // 先发影子，不然方块会在消散开始之前就先没了一下
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(kept.getFirst().getX() >> 4, kept.getFirst().getZ() >> 4),
                new DissolvePayload(DissolvePayload.BLOCKS, -1, kept, states, shape, ticks, color));
        for (BlockPos pos : kept) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static void after(MinecraftServer server, int ticks, Runnable run) {
        DUE.add(new Due(server.getTickCount() + ticks, run));
    }

    @SubscribeEvent
    static void onTick(ServerTickEvent.Post event) {
        if (DUE.isEmpty()) return;
        long now = event.getServer().getTickCount();
        DUE.removeIf(d -> {
            if (d.tick() > now) return false;
            d.run().run();
            return true;
        });
    }
}
