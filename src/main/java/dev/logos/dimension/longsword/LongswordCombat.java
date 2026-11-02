package dev.logos.dimension.longsword;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.MoveRunner;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.combat.Movesets;
import dev.logos.dimension.item.LongswordItem;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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

/**
 * 长剑接入招式状态机（数据包 combat/movesets/longsword.json）：服务端决定出招、接触时刻和射线命中；
 * 切物品、死亡、离线都会取消待结算攻击。招式读不到时长剑按原版物品处理。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class LongswordCombat {
    public static final net.minecraft.resources.Identifier MOVESET = LogosDimension.id("longsword");
    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final ThreadLocal<Boolean> CONTACT = ThreadLocal.withInitial(() -> false);
    private static final class State implements MoveRunner.Actor {
        final ServerPlayer player;
        final ItemStack stack;
        final int slot, variant;
        final Object level;
        MoveRunner runner;
        /** 上一次命中的目标与游戏刻，用来判断目标的受击冷却是不是这套连招自己造成的。 */
        Entity lastTarget;
        long lastHit;
        State(ServerPlayer player, int variant) {
            this.player = player; this.stack = player.getMainHandItem(); this.variant = variant;
            this.slot = player.getInventory().getSelectedSlot(); this.level = player.level();
        }
        @Override public double attackSpeed() { return player.getAttributeValue(Attributes.ATTACK_SPEED); }
        @Override public void moveStarted(MoveRunner runner) { sync(player, payload(runner)); }
        @Override public void contact(MoveRunner runner) { if (selected(player, this)) LongswordCombat.contact(player, this); }
        LongswordPayload payload(MoveRunner runner) {
            if (runner.cleared()) return LongswordPayload.clear(player.getUUID());
            var move = runner.move(); var set = runner.moveset();
            byte flags = (byte) ((move.accepts(Moveset.Input.USE_PRESS) ? LongswordPayload.ACCEPTS_USE : 0)
                    | (move.name().equals(set.sheathe()) ? LongswordPayload.SHEATHING : 0));
            return new LongswordPayload(player.getUUID(), variant, move.name(), move.clip(), runner.start(),
                    (float) runner.rate(), (float) runner.offset(), move.playback(), (float) move.blendIn(), flags);
        }
    }
    @SubscribeEvent static void packets(RegisterPayloadHandlersEvent event) {
        // 同步包格式变化时提高版本号，新旧版本不会互相解码。
        var registrar = event.registrar("3");
        registrar.playToClient(LongswordPayload.TYPE, LongswordPayload.CODEC);
        registrar.playToServer(LongswordPayload.Attack.TYPE, LongswordPayload.Attack.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) attack(player);
        });
    }
    public static LongswordPayload pose(ServerPlayer player) {
        var state = STATES.get(player.getUUID());
        return state == null ? LongswordPayload.clear(player.getUUID()) : state.payload(state.runner);
    }
    /** 当前招式名；没有持剑动作时为 null。 */
    public static String move(ServerPlayer player) {
        var state = STATES.get(player.getUUID());
        return state == null ? null : state.runner.move().name();
    }
    private static void sync(ServerPlayer player, LongswordPayload payload) {
        // 模拟玩家没有协商客户端通道；只给观看此区块且支持动作包的连接同步。
        for (var viewer : player.level().players()) if ((viewer == player || viewer.getChunkTrackingView().contains(player.chunkPosition()))
                && NetworkRegistry.hasChannel(viewer.connection, LongswordPayload.TYPE.id())) PacketDistributor.sendToPlayer(viewer, payload);
    }
    private static boolean selected(ServerPlayer player, State state) {
        return player.isAlive() && !player.isSpectator() && !state.stack.isEmpty() && state.level == player.level()
                && player.getInventory().getSelectedSlot() == state.slot && player.getMainHandItem() == state.stack;
    }
    private static Moveset moveset(ServerPlayer player) { return Movesets.get(player.level().getServer(), MOVESET); }
    private static State active(ServerPlayer player) {
        var state = STATES.get(player.getUUID());
        return state == null || !selected(player, state) ? null : state;
    }
    public static void attack(ServerPlayer player) {
        var state = active(player);
        if (state != null && !player.isUsingItem()) state.runner.input(Moveset.Input.ATTACK, player.level().getGameTime());
    }
    public static boolean charge(ServerPlayer player) {
        var state = active(player);
        return state != null && state.runner.input(Moveset.Input.USE_PRESS, player.level().getGameTime());
    }
    public static void release(ServerPlayer player) {
        var state = active(player);
        if (state != null) state.runner.input(Moveset.Input.USE_RELEASE, player.level().getGameTime());
    }
    @SubscribeEvent static void vanillaAttack(AttackEntityEvent event) {
        if (LongswordItem.variant(event.getEntity().getMainHandItem()) < 0 || CONTACT.get()) return;
        // 招式缺失时不拦截，长剑按原版物品攻击。
        if (!(event.getEntity() instanceof ServerPlayer player) || moveset(player) == null) return;
        event.setCanceled(true);
        attack(player);
    }
    @SubscribeEvent static void tick(ServerTickEvent.Post event) {
        for (var player : event.getServer().getPlayerList().getPlayers()) tick(player);
    }
    private static void clear(ServerPlayer player) {
        if (STATES.remove(player.getUUID()) != null) sync(player, LongswordPayload.clear(player.getUUID()));
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
            var sheathe = state.runner.moveset().sheathe();
            if (state.stack.isEmpty() || !carried(player, state.stack) || sheathe == null) { clear(player); state = null; }
            else if (!state.runner.move().name().equals(sheathe)) state.runner.force(sheathe, now);
        }
        if (state == null) {
            int variant = LongswordItem.variant(player.getMainHandItem());
            if (variant < 0) return;
            var set = moveset(player);
            if (set == null) return;
            state = new State(player, variant); STATES.put(player.getUUID(), state);
            state.runner = new MoveRunner(set, state, now);
            return;
        }
        state.runner.tick(now);
        if (state.runner.cleared()) { STATES.remove(player.getUUID()); return; }
        // 松开右键的包可能早于或晚于这一刻到达；这里补一次，保证蓄力不会卡住。
        if (!player.isUsingItem() && selected(player, state)) state.runner.input(Moveset.Input.USE_RELEASE, now);
    }
    private static void contact(ServerPlayer player, State state) {
        var from = player.getEyePosition();
        var to = from.add(player.getLookAngle().scale(player.entityInteractionRange()));
        var block = player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        to = block.getLocation();
        var hit = ProjectileUtil.getEntityHitResult(player.level(), player, from, to,
                player.getBoundingBox().expandTowards(to.subtract(from)).inflate(1), e -> !e.isSpectator() && e.isPickable(), 0);
        boolean attacked = hit != null && player.hasLineOfSight(hit.getEntity());
        if (attacked) {
            var target = hit.getEntity(); long now = player.level().getGameTime();
            // 取消点让连段间隔短于原版 10 刻受击冷却；只放过这套连招上一下自己造成的冷却，
            // 冷却若被其他来源刷新过（剩余时间比预期长）就照原版规则处理。
            if (target == state.lastTarget && target instanceof LivingEntity living && living.invulnerableTime > 0
                    && living.invulnerableTime <= 20 - (now - state.lastHit)) living.invulnerableTime = 0;
            CONTACT.set(true);
            try { player.attack(target); } finally { CONTACT.remove(); }
            if (target instanceof LivingEntity living && living.invulnerableTime >= 20) { state.lastTarget = target; state.lastHit = now; }
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
