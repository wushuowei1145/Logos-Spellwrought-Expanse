package dev.logos.dimension.longsword;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.item.LongswordItem;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** 服务端决定出招、接触时刻和射线命中；切物品、死亡、离线都会取消待结算攻击。 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class LongswordCombat {
    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final ThreadLocal<Boolean> CONTACT = ThreadLocal.withInitial(() -> false);
    private static final class State {
        ItemStack stack;
        int slot, variant, combo;
        LongswordPhase phase = LongswordPhase.DRAW;
        double start, lastAttack = -100;
        float rate = 1, reverse;
        boolean hit, queued;
        Object level;
        LongswordPayload payload(ServerPlayer player) { return new LongswordPayload(player.getUUID(), variant, phase, start, rate, reverse); }
    }
    @SubscribeEvent static void packets(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(LongswordPayload.TYPE, LongswordPayload.CODEC);
        registrar.playToServer(LongswordPayload.Attack.TYPE, LongswordPayload.Attack.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) attack(player);
        });
    }
    public static LongswordPayload pose(ServerPlayer player) {
        var state = STATES.get(player.getUUID());
        return state == null ? new LongswordPayload(player.getUUID(), 0, LongswordPhase.CLEAR, 0, 1, 0) : state.payload(player);
    }
    private static void sync(ServerPlayer player, State state) { broadcast(player, state.payload(player)); }
    private static void broadcast(ServerPlayer player, LongswordPayload payload) {
        // 模拟玩家没有协商客户端通道；只给观看此区块且支持动作包的连接同步。
        for (var viewer : player.level().players()) if ((viewer == player || viewer.getChunkTrackingView().contains(player.chunkPosition()))
                && NetworkRegistry.hasChannel(viewer.connection, LongswordPayload.TYPE.id())) PacketDistributor.sendToPlayer(viewer, payload);
    }
    private static void begin(ServerPlayer player, State state, LongswordPhase phase, double now) {
        state.phase = phase; state.start = now; state.hit = false; state.queued = false;
        sync(player, state);
    }
    private static boolean selected(ServerPlayer player, State state) {
        return player.isAlive() && !player.isSpectator() && state.level == player.level()
                && player.getInventory().getSelectedSlot() == state.slot && player.getMainHandItem() == state.stack;
    }
    public static void attack(ServerPlayer player) {
        var state = STATES.get(player.getUUID());
        if (state == null || !selected(player, state) || player.isUsingItem()) return;
        double now = player.level().getGameTime();
        if (state.phase.attack()) {
            // 仅在收势的最后四刻接收一条输入，持续按住左键也不能跳过攻速。
            if (now - state.start >= state.phase.duration(state.rate, 0) - 4) state.queued = true;
        } else if (state.phase == LongswordPhase.IDLE) {
            if (now - state.lastAttack > 30) state.combo = 0;
            strike(player, state, now);
        }
    }
    private static void strike(ServerPlayer player, State state, double now) {
        state.rate = (float) Math.clamp(player.getAttributeValue(Attributes.ATTACK_SPEED) / 1.6, .2, 4);
        state.combo = state.combo % 3 + 1; state.lastAttack = now;
        begin(player, state, LongswordPhase.values()[LongswordPhase.ATTACK_1.ordinal() + state.combo - 1], now);
    }
    public static boolean charge(ServerPlayer player) {
        var state = STATES.get(player.getUUID());
        if (state == null || !selected(player, state) || state.phase != LongswordPhase.IDLE) return false;
        state.combo = 0;
        begin(player, state, LongswordPhase.CHARGING, player.level().getGameTime());
        return true;
    }
    public static void release(ServerPlayer player) {
        var state = STATES.get(player.getUUID());
        if (state == null || state.phase != LongswordPhase.CHARGING || !selected(player, state)) return;
        double now = player.level().getGameTime();
        state.reverse = (float) Math.clamp(now - state.start, 0, 11);
        begin(player, state, state.reverse >= 11 ? LongswordPhase.RELEASE : LongswordPhase.CANCEL, now);
    }
    @SubscribeEvent static void vanillaAttack(AttackEntityEvent event) {
        if (LongswordItem.variant(event.getEntity().getMainHandItem()) < 0 || CONTACT.get()) return;
        event.setCanceled(true);
        if (event.getEntity() instanceof ServerPlayer player) attack(player);
    }
    @SubscribeEvent static void tick(ServerTickEvent.Post event) {
        for (var player : event.getServer().getPlayerList().getPlayers()) tick(player);
    }
    private static void clear(ServerPlayer player) {
        if (STATES.remove(player.getUUID()) != null) broadcast(player, pose(player));
    }
    private static boolean carried(ServerPlayer player, ItemStack stack) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) if (player.getInventory().getItem(i) == stack) return true;
        return false;
    }
    private static void tick(ServerPlayer player) {
        var state = STATES.get(player.getUUID());
        if (!player.isAlive() || player.isSpectator() || state != null && state.level != player.level()) { clear(player); return; }
        double now = player.level().getGameTime();
        if (state != null && !selected(player, state)) {
            if (state.stack.isEmpty() || !carried(player, state.stack)) { clear(player); state = null; }
            else if (state.phase != LongswordPhase.SHEATHE) begin(player, state, LongswordPhase.SHEATHE, now);
        }
        if (state == null) {
            int variant = LongswordItem.variant(player.getMainHandItem());
            if (variant < 0) return;
            state = new State(); state.stack = player.getMainHandItem(); state.variant = variant;
            state.slot = player.getInventory().getSelectedSlot(); state.level = player.level(); STATES.put(player.getUUID(), state);
            begin(player, state, LongswordPhase.DRAW, now); return;
        }
        if ((state.phase.attack() || state.phase == LongswordPhase.RELEASE) && !state.hit
                && now - state.start >= state.phase.contact(state.rate)) {
            state.hit = true;
            if (selected(player, state)) contact(player);
        }
        if (now - state.start >= state.phase.duration(state.rate, state.reverse)) {
            if (state.phase == LongswordPhase.SHEATHE) { clear(player); return; }
            if (state.phase.attack() && state.queued) strike(player, state, state.start + state.phase.duration(state.rate, 0));
            else begin(player, state, LongswordPhase.IDLE, now);
        } else if (state.phase == LongswordPhase.CHARGING && !player.isUsingItem()) release(player);
    }
    private static void contact(ServerPlayer player) {
        var from = player.getEyePosition();
        var to = from.add(player.getLookAngle().scale(player.entityInteractionRange()));
        var block = player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        to = block.getLocation();
        var hit = ProjectileUtil.getEntityHitResult(player.level(), player, from, to,
                player.getBoundingBox().expandTowards(to.subtract(from)).inflate(1), e -> !e.isSpectator() && e.isPickable(), 0);
        boolean attacked = hit != null && player.hasLineOfSight(hit.getEntity());
        if (attacked) {
            CONTACT.set(true);
            try { player.attack(hit.getEntity()); } finally { CONTACT.remove(); }
        } else {
            player.resetAttackStrengthTicker();
            player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, .6f, 1);
        }
    }
    @SubscribeEvent static void tracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer viewer && event.getTarget() instanceof ServerPlayer target
                && NetworkRegistry.hasChannel(viewer.connection, LongswordPayload.TYPE.id()))
            PacketDistributor.sendToPlayer(viewer, pose(target));
    }
    @SubscribeEvent static void logout(PlayerEvent.PlayerLoggedOutEvent event) { STATES.remove(event.getEntity().getUUID()); }
    @SubscribeEvent static void stopped(ServerStoppedEvent event) { STATES.clear(); CONTACT.remove(); }
}
