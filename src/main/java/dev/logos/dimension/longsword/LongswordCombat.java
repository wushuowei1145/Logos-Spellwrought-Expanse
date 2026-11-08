package dev.logos.dimension.longsword;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.Airborne;
import dev.logos.dimension.combat.CombatEffects;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.dimension.combat.MoveRunner;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.combat.Movesets;
import dev.logos.dimension.combat.Parry;
import dev.logos.dimension.dawnvow.DawnvowCharge;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent;
import net.neoforged.neoforge.event.entity.player.SweepAttackEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * 本附属的战斗武器（长剑、打刀，见 {@link CombatWeapon}）接入招式状态机（数据包 combat/movesets/*.json）：
 * 服务端决定出招、接触时刻和命中；切物品、死亡、离线都会取消待结算攻击。招式读不到时武器按原版物品处理。
 * <p>
 * 命中判定是准星前方的锥形（{@link #cone}）：顶点在眼睛，张角是武器注册时给的 {@link CombatWeapon#withCone}，
 * 不给就是 {@link CombatWeapon#DEFAULT_CONE} 度；长度是原版攻击距离
 * （{@code entity_interaction_range}，被属性修饰时跟着变）。锥形里看得见的生物都挨一刀（原版攻击，护甲、附魔、
 * 受击冷却照常）；准星正对着的那个暴击（经 NeoForge 的暴击事件，原版的 1.5 倍、粒子与音效）。这一刀不再另触发原版横扫。
 * 凌霄按维护者要求改用菱形棱柱判定，并保留原版暴击条件；其他武器继续使用上面的锥形规则。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class LongswordCombat {
    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final ThreadLocal<Boolean> CONTACT = ThreadLocal.withInitial(() -> false);
    /** 这一刀要暴击的目标（准星正对着的），由暴击事件读。 */
    private static final ThreadLocal<Entity> CRIT = new ThreadLocal<>();
    /** 正在结算的招式与目标：招式的 damage、knockback 与附加效果的伤害倍数由伤害、击退事件读。 */
    private static final ThreadLocal<Moveset.Move> MOVE = new ThreadLocal<>();
    private static final ThreadLocal<Entity> TARGET = new ThreadLocal<>();
    /** 招式要求按蓄满的攻击结算时写进攻击蓄力计时的值（远大于任何攻击间隔）。 */
    private static final int FULL_STRENGTH_TICKS = 1000;
    /** 一刀最多打几个目标。 */
    private static final int MAX_TARGETS = 8;
    private static final class State implements MoveRunner.Actor {
        final ServerPlayer player;
        final ItemStack stack;
        final int slot;
        final CombatWeapon weapon;
        final Object level;
        MoveRunner runner;
        /**
         * 这套连招最近打中各目标的记录：目标自己记下的“最后被生物打中”的时刻（它的 tickCount）与当时的游戏刻。
         * 下一刀前目标的最后一下若仍是这一刀留下的，受击冷却就是连招自己造成的。
         */
        final Map<UUID, long[]> lastHits = new HashMap<>();
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
        if (state != null && (!player.isUsingItem() || state.runner.move().accepts(Moveset.Input.ATTACK))
                && (!(state.stack.getItem() instanceof CombatWeapon.Holder holder) || holder.allowsAttack(player, state.runner.move().name())))
            state.runner.input(Moveset.Input.ATTACK, player.level().getGameTime());
    }
    /** 直接切到招式文件里的 {@code move}（施法剑按施放结果选收势）；没有持剑动作或招式文件里没有这个招式时不动。 */
    public static void force(ServerPlayer player, String move) {
        var state = active(player);
        if (state != null) state.runner.force(move, player.level().getGameTime());
    }
    public static boolean charge(ServerPlayer player) {
        var state = active(player);
        return state != null && state.runner.input(Moveset.Input.USE_PRESS, player.level().getGameTime());
    }
    public static void release(ServerPlayer player) {
        var state = active(player);
        if (state != null) state.runner.input(Moveset.Input.USE_RELEASE, player.level().getGameTime());
    }
    /** 原版格挡成功：交给招式文件的 block 输入（打刀格挡开始 0.3 秒内转入弹刀）；转入的招式是弹刀时不消耗耐久，近战的攻击方进入僵直。 */
    @SubscribeEvent static void blocked(LivingShieldBlockEvent event) {
        if (!event.getBlocked() || !(event.getEntity() instanceof ServerPlayer player)) return;
        var state = active(player);
        if (state != null && state.runner.input(Moveset.Input.BLOCK, player.level().getGameTime())
                && CombatEffects.PARRY.equals(state.runner.move().effect())) {
            event.setShieldDamage(0);
            Parry.stagger(event.getDamageSource(), player);
        }
    }
    /**
     * 这名玩家的攻击被弹开：切到招式文件的 {@link Moveset#PARRIED}，这一下后面的目标不再结算，这段时间不能出招、不能格挡。
     * 招式文件没有这个招式（和弓）或手上没有战斗武器时不动。
     */
    public static boolean parried(ServerPlayer player) {
        var state = active(player);
        if (state == null || state.runner.moveset().move(Moveset.PARRIED) == null) return false;
        // 按住右键接突刺时原版还在格挡，不停下来就没有破绽。
        if (player.isUsingItem()) player.stopUsingItem();
        state.runner.force(Moveset.PARRIED, player.level().getGameTime());
        return true;
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
        if (selected(player, state) && state.stack.getItem() instanceof CombatWeapon.Holder holder) holder.combatTick(player, state.runner.move().name());
    }
    /** 这一刀命中锥形的张角（度）：手上武器的 {@link CombatWeapon#cone}；曦誓剑身金光在时更宽。 */
    public static double cone(ServerPlayer player) {
        var weapon = CombatWeapon.of(player.getMainHandItem());
        return DawnvowCharge.cone(player, weapon == null ? CombatWeapon.DEFAULT_CONE : weapon.cone());
    }
    /** 某一招的张角：招式写了 {@code cone} 就用它（例如长枪的直刺窄、横扫宽），否则同 {@link #cone(ServerPlayer)}。 */
    public static double cone(ServerPlayer player, Moveset.Move move) {
        return move.cone() > 0 ? DawnvowCharge.cone(player, move.cone()) : cone(player);
    }

    /**
     * 这一刀打到谁：准星正对着的（原版射线，碰到方块为止）排第一个，其余是锥形里看得见的生物，近的在前。
     * 锥形以眼睛为顶点、朝视线，生物身上离视线最近的那一点要在攻击距离内、与视线的夹角不超过张角的一半。
     */
    public static List<Entity> targets(ServerPlayer player, double degrees) {
        var level = player.level();
        var from = player.getEyePosition(); var look = player.getLookAngle();
        double reach = player.entityInteractionRange();
        var to = from.add(look.scale(reach));
        var block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        var end = block.getLocation();
        var hit = ProjectileUtil.getEntityHitResult(level, player, from, end,
                player.getBoundingBox().expandTowards(end.subtract(from)).inflate(1), e -> !e.isSpectator() && e.isPickable(), 0);
        List<Entity> out = new ArrayList<>();
        Entity aimed = hit != null && player.hasLineOfSight(hit.getEntity()) ? hit.getEntity() : null;
        if (aimed != null) out.add(aimed);
        double cos = Math.cos(Math.toRadians(degrees / 2));
        List<Entity> cone = level.getEntities(player, player.getBoundingBox().inflate(reach + 1), e -> {
            if (e == aimed || !(e instanceof LivingEntity living) || !living.isAlive() || e.isSpectator() || !e.isPickable()) return false;
            if (e.isPassengerOfSameVehicle(player) || e instanceof net.minecraft.world.entity.TamableAnimal pet && pet.isOwnedBy(player)) return false;
            var near = nearest(e.getBoundingBox(), from, look, reach);
            var d = near.subtract(from);
            double dist = d.length();
            if (dist > reach) return false;
            if (dist > 1e-4 && d.dot(look) / dist < cos) return false;
            return player.hasLineOfSight(e) || level.clip(new ClipContext(from, near, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
                    .getType() == net.minecraft.world.phys.HitResult.Type.MISS;
        });
        cone.sort(java.util.Comparator.comparingDouble(e -> e.getBoundingBox().distanceToSqr(from)));
        for (var e : cone) if (out.size() < MAX_TARGETS) out.add(e);
        return out;
    }

    /** 盒子里离视线最近的一点：先取视线上离盒心最近的点（不超过攻击距离），再夹进盒子。 */
    private static net.minecraft.world.phys.Vec3 nearest(net.minecraft.world.phys.AABB box, net.minecraft.world.phys.Vec3 from,
                                                        net.minecraft.world.phys.Vec3 look, double reach) {
        double t = Math.clamp(box.getCenter().subtract(from).dot(look), 0, reach);
        var p = from.add(look.scale(t));
        return new net.minecraft.world.phys.Vec3(Math.clamp(p.x, box.minX, box.maxX), Math.clamp(p.y, box.minY, box.maxY), Math.clamp(p.z, box.minZ, box.maxZ));
    }

    private static void contact(ServerPlayer player, State state) {
        var move = state.runner.move();
        var holder = state.stack.getItem() instanceof CombatWeapon.Holder h ? h : null;
        if (holder != null && !holder.swing(player, move)) { player.resetAttackStrengthTicker(); return; }
        var targets = state.weapon.diamondHitbox() ? dev.logos.dimension.combat.DiamondHitbox.targets(player) : targets(player, cone(player, move));
        CombatEffects.contact(player, move, targets);
        if (targets.isEmpty()) {
            player.resetAttackStrengthTicker();
            if (!CombatEffects.quietWhiff(move))
                player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, .6f, 1);
            return;
        }
        long now = player.level().getGameTime();
        state.lastHits.values().removeIf(hit -> now - hit[1] > 20);
        // 原版打完一下就把攻击蓄力清零；每个目标都按出刀时的蓄力算，最后一下之后照常清零
        var strength = (dev.logos.dimension.mixin.AttackStrengthAccess) player;
        int ticker = move.fullStrength() ? FULL_STRENGTH_TICKS : strength.logos_dimension$attackStrengthTicker();
        int swap = strength.logos_dimension$itemSwapTicker();
        boolean first = true;
        for (var target : targets) {
            if (!selected(player, state) || !player.isAlive()) break;
            strength.logos_dimension$setAttackStrengthTicker(ticker);
            strength.logos_dimension$setItemSwapTicker(swap);
            // 取消点让连段间隔短于原版 10 刻受击冷却；只放过这套连招上一下自己造成的冷却：
            // 目标最后一次被生物打中仍是这一刀留下的（同一个人、同一时刻）。之后若被别人打过就照原版规则处理。
            // 不按游戏刻推算剩余冷却：目标所在区块没在 tick 时冷却不会递减，推算会误判成被别人刷新过。
            long[] last = state.lastHits.get(target.getUUID());
            if (last != null && target instanceof LivingEntity living && living.invulnerableTime > 0
                    && living.getLastHurtByMob() == player && living.getLastHurtByMobTimestamp() == last[0]) living.invulnerableTime = 0;
            CONTACT.set(true);
            MOVE.set(move); TARGET.set(target);
            // 凌霄沿用原版铁剑暴击条件，不额外强制暴击。
            if (first && !state.weapon.diamondHitbox()) CRIT.set(target);
            try { if (holder == null || !holder.strike(player, target, move)) player.attack(target); }
            finally { CONTACT.remove(); CRIT.remove(); MOVE.remove(); TARGET.remove(); }
            if (target instanceof LivingEntity living && living.invulnerableTime >= 20 && living.getLastHurtByMob() == player)
                state.lastHits.put(target.getUUID(), new long[]{living.getLastHurtByMobTimestamp(), now});
            // 这一下被弹开（招式已经换成被弹反）：没有砍中，不触发挑飞之类的效果，后面的目标也不再结算。
            if (state.runner.move() != move) break;
            DawnvowCharge.hit(player, target);
            CombatEffects.hit(player, target, move);
            first = false;
        }
    }

    /** 招式的伤害倍数（damage）与附加效果的倍数（例如连刺打浮空目标翻倍），只作用于这一刀的目标。 */
    @SubscribeEvent static void damage(LivingIncomingDamageEvent event) {
        var move = MOVE.get();
        if (move == null || event.getEntity() != TARGET.get()) return;
        float scale = (float) move.damage() * CombatEffects.multiplier(move, event.getEntity());
        if (scale != 1) event.setAmount(event.getAmount() * scale);
    }
    /** 招式不击退（knockback 为假）或目标浮空时，这一刀的击退取消：连刺不把目标推出枪尖，浮空目标停在原处。 */
    @SubscribeEvent static void knockback(LivingKnockBackEvent event) {
        var move = MOVE.get();
        if (move != null && event.getEntity() == TARGET.get() && (!move.knockback() || Airborne.is(event.getEntity()))) event.setCanceled(true);
    }

    /** 准星正对着的目标暴击：原版暴击的 1.5 倍、粒子与音效。 */
    @SubscribeEvent static void critical(CriticalHitEvent event) {
        if (CRIT.get() == null || event.getTarget() != CRIT.get()) return;
        event.setCriticalHit(true);
        event.setDamageMultiplier(Math.max(event.getDamageMultiplier(), 1.5f));
    }

    /** 锥形判定已经打到了附近的生物，这一刀不再另触发原版横扫。 */
    @SubscribeEvent static void sweep(SweepAttackEvent event) {
        if (CONTACT.get() && CombatWeapon.of(event.getEntity().getMainHandItem()) != null) event.setSweeping(false);
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
    @SubscribeEvent static void stopped(ServerStoppedEvent event) { STATES.clear(); CONTACT.remove(); MOVE.remove(); TARGET.remove(); }
}
