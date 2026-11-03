package dev.logos.dimension.longsword;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.dimension.combat.MoveRunner;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.combat.Movesets;
import dev.logos.dimension.dawnvow.DawnvowCharge;
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
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * 本附属的战斗武器（长剑、打刀，见 {@link CombatWeapon}）接入招式状态机（数据包 combat/movesets/*.json）：
 * 服务端决定出招、接触时刻和射线命中；切物品、死亡、离线都会取消待结算攻击。招式读不到时武器按原版物品处理。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class LongswordCombat {
    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final ThreadLocal<Boolean> CONTACT = ThreadLocal.withInitial(() -> false);
    private static final class State implements MoveRunner.Actor {
        final ServerPlayer player;
        final ItemStack stack;
        final int slot;
        final CombatWeapon weapon;
        final Object level;
        MoveRunner runner;
        /** 上一次命中的目标与游戏刻，用来判断目标的受击冷却是不是这套连招自己造成的。 */
        Entity lastTarget;
        long lastHit;
        State(ServerPlayer player, CombatWeapon weapon) {
            this.player = player; this.stack = player.getMainHandItem(); this.weapon = weapon;
            this.slot = player.getInventory().getSelectedSlot(); this.level = player.level();
        }
        @Override public double attackSpeed() { return player.getAttributeValue(Attributes.ATTACK_SPEED); }
        @Override public void moveStarted(MoveRunner runner) { sync(player, payload(runner)); }
        @Override public void contact(MoveRunner runner) {
            if (!selected(player, this)) return;
            DawnvowCharge.contact(player, runner.move().name());
            LongswordCombat.contact(player, this);
        }
        LongswordPayload payload(MoveRunner runner) {
            if (runner.cleared()) return LongswordPayload.clear(player.getUUID());
            var move = runner.move(); var set = runner.moveset();
            byte flags = (byte) ((move.accepts(Moveset.Input.USE_PRESS) ? LongswordPayload.ACCEPTS_USE : 0)
                    | (move.name().equals(set.sheathe()) ? LongswordPayload.SHEATHING : 0)
                    | (move.accepts(Moveset.Input.ATTACK) ? LongswordPayload.ACCEPTS_ATTACK : 0));
            return new LongswordPayload(player.getUUID(), weapon.id(), move.name(), move.clip(), runner.start(),
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
    private static Moveset moveset(ServerPlayer player, CombatWeapon weapon) { return Movesets.get(player.level().getServer(), weapon.moveset()); }
    private static State active(ServerPlayer player) {
        var state = STATES.get(player.getUUID());
        return state == null || !selected(player, state) ? null : state;
    }
    public static void attack(ServerPlayer player) {
        var state = active(player);
        // 按住右键时只有接受左键的招式（例如弹刀后接突刺）才收左键，长剑蓄力中仍不出招。
        if (state != null && (!player.isUsingItem() || state.runner.move().accepts(Moveset.Input.ATTACK)))
            state.runner.input(Moveset.Input.ATTACK, player.level().getGameTime());
    }
    public static boolean charge(ServerPlayer player) {
        var state = active(player);
        return state != null && state.runner.input(Moveset.Input.USE_PRESS, player.level().getGameTime());
    }
    public static void release(ServerPlayer player) {
        var state = active(player);
        if (state != null) state.runner.input(Moveset.Input.USE_RELEASE, player.level().getGameTime());
    }
    /** 原版格挡成功：交给招式文件的 block 输入（打刀格挡开始 0.3 秒内转入弹刀）；弹刀不消耗耐久。 */
    @SubscribeEvent static void blocked(LivingShieldBlockEvent event) {
        if (!event.getBlocked() || !(event.getEntity() instanceof ServerPlayer player)) return;
        var state = active(player);
        if (state != null && state.runner.input(Moveset.Input.BLOCK, player.level().getGameTime())) event.setShieldDamage(0);
    }
    @SubscribeEvent static void vanillaAttack(AttackEntityEvent event) {
        var weapon = CombatWeapon.of(event.getEntity().getMainHandItem());
        if (weapon == null || CONTACT.get()) return;
        // 招式缺失时不拦截，武器按原版物品攻击。
        if (!(event.getEntity() instanceof ServerPlayer player) || moveset(player, weapon) == null) return;
        event.setCanceled(true);
        attack(player);
    }
    @SubscribeEvent static void tick(ServerTickEvent.Post event) {
        for (var player : event.getServer().getPlayerList().getPlayers()) tick(player);
    }
    private static void clear(ServerPlayer player) {
        DawnvowCharge.end(player);
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
            var weapon = CombatWeapon.of(player.getMainHandItem());
            if (weapon == null) return;
            var set = moveset(player, weapon);
            if (set == null) return;
            state = new State(player, weapon); STATES.put(player.getUUID(), state);
            state.runner = new MoveRunner(set, state, now);
            return;
        }
        state.runner.tick(now);
        if (state.runner.cleared()) { DawnvowCharge.end(player); STATES.remove(player.getUUID()); return; }
        // 松开右键的包可能早于或晚于这一刻到达；这里补一次，保证蓄力不会卡住。
        if (!player.isUsingItem() && selected(player, state)) state.runner.input(Moveset.Input.USE_RELEASE, now);
        // 一直按着右键时，招式结束回到可格挡的状态（例如突刺收势）就重新进入格挡。
        else if (player.isUsingItem() && selected(player, state)) state.runner.input(Moveset.Input.USE_PRESS, now);
        if (DawnvowCharge.dawnvow(state.stack)) DawnvowCharge.tick(player, state.stack, state.runner.move().name());
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
    @SubscribeEvent static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) DawnvowCharge.end(player);
        STATES.remove(event.getEntity().getUUID());
    }
    @SubscribeEvent static void stopped(ServerStoppedEvent event) { STATES.clear(); CONTACT.remove(); }
}
