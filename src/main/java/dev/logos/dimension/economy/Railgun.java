package dev.logos.dimension.economy;

import dev.logos.lang.Element;
import dev.logos.runtime.Effects;
import dev.logos.runtime.ManaWrap;
import dev.logos.runtime.PlayerMagic;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 银元的彩蛋，致敬《某科学的超电磁炮》的御坂美琴。身上缠着雷电魔力（雷霆的 {@link ManaWrap}）时手持银元长按右键：
 * 先付 200 魔力，把一枚硬币弹上天并蓄力，硬币转着落回拳前的一刻（第 {@link #FIRE_TICK} 刻）把它打出去。
 * <p>
 * 射线最远 96 格，碰到不能掀掉的方块就停；沿途离射线 1.5 格内的生物各受 100 点物理伤害；射线四周的软方块
 * （硬度不超过 {@link #SOFT}）被掀掉、不掉落，范围从拳前半格逐渐张开到半径 4 格，有方块实体的（箱子等）不动。
 * 硬币打出去就没了（创造模式不扣）；中途松手作罢，魔力不退。
 */
public final class Railgun {
    public static final double COST = 200, RANGE = 96, DAMAGE = 100, HIT_RADIUS = 1.5, BREAK_RADIUS = 4;
    /** 掀得掉的最大硬度：泥土、石头 1.5、木头 2、深板岩和矿石 3 都算软；铁块 5、黑曜石 50 挡得住。 */
    public static final float SOFT = 3;
    /** 弹起硬币后第几刻发射；客户端按同一个时间画硬币落回拳前。 */
    public static final int FIRE_TICK = 28;
    private static final int COOLDOWN = 20;
    /** 拆方块每刻沿射线推进的格数：一刻拆完几千个方块会卡一下，所以分四刻跟在冲击波后面拆。 */
    private static final double SWEEP = 24;
    /** 每刻最多放多少个方块碎裂的粒子和声音。 */
    private static final int BREAK_EFFECTS = 40;
    /** 多远的玩家看得到蓄力和发射。 */
    private static final double VIEW = 96;

    private static final List<Sweep> SWEEPS = new ArrayList<>();

    private Railgun() {}

    /** 发给 {@code at} 附近、和服务端协商过本附属频道的玩家（游戏测试里的假玩家没有）。 */
    private static void send(Level level, Vec3 at, double radius, RailgunPayload payload) {
        for (var player : level.players())
            if (player instanceof ServerPlayer sp && sp.distanceToSqr(at) <= radius * radius && sp.connection.hasChannel(RailgunPayload.TYPE))
                PacketDistributor.sendToPlayer(sp, payload);
    }

    public static boolean coin(ItemStack stack) { return stack.is(Economy.coin(Currency.IMPERIAL, 1)); }

    /** 身上缠着雷电魔力。 */
    public static boolean charged(LivingEntity e) {
        var wrap = ManaWrap.of(e);
        return wrap != null && wrap.element() == Element.LIGHTNING;
    }

    /** 右键：缠着雷电时付魔力开始蓄力；没缠雷电时银元仍是普通钱币。 */
    public static InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!charged(player) || player.getCooldowns().isOnCooldown(stack)) return InteractionResult.PASS;
        boolean offhand = hand == InteractionHand.OFF_HAND;
        if (player instanceof ServerPlayer sp) {
            var magic = PlayerMagic.of(sp);
            if (magic.mana < COST) {
                PlayerMagic.alert(sp, Component.translatable("message.logos_dimension.railgun.mana", (int) COST, (int) magic.mana)
                        .withStyle(ChatFormatting.RED));
                // 客户端已经先开始用了，让它停下
                send(sp.level(), sp.position(), 0, RailgunPayload.of(RailgunPayload.CANCEL, sp.getId(), offhand));
                return InteractionResult.FAIL;
            }
            magic.mana -= COST;
            send(sp.level(), sp.position(), VIEW, RailgunPayload.of(RailgunPayload.CHARGE, sp.getId(), offhand));
        }
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    /** 按住的每一刻；{@code elapsed} 是从弹起硬币算起的刻数。 */
    public static void tick(Level level, LivingEntity user, ItemStack stack, int elapsed) {
        if (elapsed < FIRE_TICK || !(level instanceof ServerLevel server) || !(user instanceof ServerPlayer p)) return;
        InteractionHand hand = p.getUsedItemHand();
        p.stopUsingItem();
        fire(server, p, hand, stack);
    }

    /** 提前松手：硬币落空，特效收掉。 */
    public static void release(LivingEntity user, int elapsed) {
        if (elapsed < FIRE_TICK && user instanceof ServerPlayer p) send(p.level(), p.position(), VIEW,
                RailgunPayload.of(RailgunPayload.CANCEL, p.getId(), p.getUsedItemHand() == InteractionHand.OFF_HAND));
    }

    /** 发射：伤害当刻结算，方块在之后几刻里沿射线拆掉。 */
    public static void fire(ServerLevel level, ServerPlayer p, InteractionHand hand, ItemStack stack) {
        Vec3 eye = p.getEyePosition(), dir = p.getLookAngle();
        double reach = reach(level, p, eye, dir);
        Vec3 end = eye.add(dir.scale(reach));
        for (LivingEntity t : struck(level, p, eye, end)) {
            Effects.followUp(p, t, DAMAGE, Element.PHYSICAL);
            t.knockback(1.2, -dir.x, -dir.z);
        }
        SWEEPS.add(new Sweep(level, p, eye, dir, reach));
        // 后坐力
        p.push(-dir.x * .35, .05, -dir.z * .35);
        p.hurtMarked = true;
        p.getCooldowns().addCooldown(stack, COOLDOWN);
        stack.consume(1, p);
        Vec3 mid = eye.add(end).scale(.5);
        send(level, mid, reach / 2 + VIEW,
                new RailgunPayload(RailgunPayload.FIRE, p.getId(), hand == InteractionHand.OFF_HAND, eye, end, reach < RANGE));
    }

    /** 射线能走多远：停在第一个掀不掉的方块（或没加载的区块）面前。 */
    static double reach(ServerLevel level, ServerPlayer p, Vec3 eye, Vec3 dir) {
        BlockPos last = null;
        for (double s = 0; s < RANGE; s += .2) {
            BlockPos pos = BlockPos.containing(eye.add(dir.scale(s)));
            if (pos.equals(last)) continue;
            last = pos;
            if (!level.isLoaded(pos)) return s;
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || fluid(state)) continue;
            if (!breakable(level, p, pos, state)) return s;
        }
        return RANGE;
    }

    /** 离射线 {@link #HIT_RADIUS} 格内的生物，不含射手自己、同乘的和自己的宠物。 */
    static List<LivingEntity> struck(ServerLevel level, ServerPlayer p, Vec3 eye, Vec3 end) {
        return level.getEntitiesOfClass(LivingEntity.class, new AABB(eye, end).inflate(HIT_RADIUS), e -> {
            if (e == p || !e.isAlive() || e.isSpectator() || e.isPassengerOfSameVehicle(p)) return false;
            if (e instanceof TamableAnimal pet && pet.isOwnedBy(p)) return false;
            AABB box = e.getBoundingBox().inflate(HIT_RADIUS);
            return box.contains(eye) || box.clip(eye, end).isPresent();
        });
    }

    /** 液体不挡也不拆。 */
    private static boolean fluid(BlockState state) { return state.getBlock() instanceof LiquidBlock; }

    /** 射手能改这里（出生点保护、领地、冒险模式……），方块够软，也不是箱子之类带方块实体的。 */
    static boolean breakable(ServerLevel level, ServerPlayer p, BlockPos pos, BlockState state) {
        float hardness = state.getDestroySpeed(level, pos);
        return hardness >= 0 && hardness <= SOFT && !state.hasBlockEntity()
                && level.mayInteract(p, pos) && !p.blockActionRestricted(level, pos, p.gameMode.getGameModeForPlayer());
    }

    /** 离射手 {@code t} 格处掀方块的半径：拳前很窄，免得把脚下挖空，七格外张满。 */
    static double radius(double t) { return Math.min(BREAK_RADIUS, .5 + t * .5); }

    /** 一次射击要拆的方块，每刻往前拆 {@link #SWEEP} 格。 */
    private static final class Sweep {
        final ServerLevel level;
        final ServerPlayer player;
        final Vec3 from, dir;
        final double reach;
        double done;

        Sweep(ServerLevel level, ServerPlayer player, Vec3 from, Vec3 dir, double reach) {
            this.level = level; this.player = player; this.from = from; this.dir = dir; this.reach = reach;
        }

        /** 拆下一段，拆完返回 true。 */
        boolean step() {
            double s0 = done, s1 = Math.min(reach, done + SWEEP);
            boolean last = s1 >= reach;
            AABB span = new AABB(from.add(dir.scale(s0)), from.add(dir.scale(s1))).inflate(BREAK_RADIUS + 1);
            AABB shooter = player.getBoundingBox().inflate(.6);
            int effects = 0;
            for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(span.minX), Mth.floor(span.minY), Mth.floor(span.minZ),
                    Mth.floor(span.maxX), Mth.floor(span.maxY), Mth.floor(span.maxZ))) {
                Vec3 c = Vec3.atCenterOf(pos);
                double t = c.subtract(from).dot(dir);
                if (t < s0 || (last ? t > reach : t >= s1)) continue;
                double r = radius(t);
                if (c.distanceToSqr(from.add(dir.scale(t))) > r * r || shooter.contains(c) || !level.isLoaded(pos)) continue;
                BlockState state = level.getBlockState(pos);
                if (state.isAir() || fluid(state) || !breakable(level, player, pos, state)) continue;
                level.setBlock(pos, state.getFluidState().createLegacyBlock(), Block.UPDATE_ALL);
                if (effects < BREAK_EFFECTS && (pos.hashCode() & 7) == 0) {
                    level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(state));
                    effects++;
                }
            }
            done = s1;
            return last;
        }
    }

    static void sweep(ServerTickEvent.Post event) {
        SWEEPS.removeIf(s -> s.player.isRemoved() || s.player.level() != s.level || s.step());
    }

    static void stopped(ServerStoppedEvent event) { SWEEPS.clear(); }
}
