package dev.logos.dimension.ryuka;

import com.mojang.serialization.Codec;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.dissolve.DissolveShape;
import dev.logos.dimension.dissolve.Dissolving;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.item.RyukaItem;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jspecify.annotations.Nullable;

/**
 * 流火的规则（docs/RYUKA.md 第 3 节），全在服务端：
 * <ul>
 *   <li>魔力：拿在主手每秒 {@link #UPKEEP} 点，每挥一刀 {@link #SWING_COST} 点（没砍中也扣），落樱终式再加 {@link #WAVE_COST} 点。
 *       创造模式不扣。付不起维持费时刀身散去、只剩刀柄（{@link #FADED}），魔力回到 {@link #REGROW} 点再凝聚；只剩刀柄时不能出刀、不能格挡。</li>
 *   <li>伤害：不走原版近战，每一刀是 Logos 的奥秘伤害（{@link #strike}）。</li>
 *   <li>无形之刃：这把刀（连同刀气）的伤害不能被盾挡下。只认这把刀打出的那一下，Logos 别的奥秘伤害照旧。</li>
 *   <li>花散：被这把刀斩杀的生物（玩家除外）整只化作樱花瓣消散。</li>
 *   <li>格挡：魔力护盾，和黑钢长剑一样挡下的扣魔力、魔法也挡；斧头、监守者这类破盾的攻击见 {@link #guarded}。</li>
 * </ul>
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class Ryuka {
    public static final double UPKEEP = 1, SWING_COST = 10, WAVE_COST = 30, REGROW = 20;
    /** 普通格挡挡下会破盾的攻击时，每破盾一秒另付的魔力（斧头、监守者 5 秒 → 20 点）。 */
    public static final double BREAK_COST = 4;
    /** 每刀的奥秘伤害（乘招式的 damage）。 */
    public static final float DAMAGE = 9;
    /** 消散边缘的颜色：原版樱花叶的粉。 */
    public static final int CHERRY = 0xF4A6CF;
    /** 花散的时长（刻）。 */
    public static final int BLOSSOM_TICKS = 24;
    /** 命中时的淡粉光点。 */
    private static final net.minecraft.core.particles.DustParticleOptions HIT_DUST = new net.minecraft.core.particles.DustParticleOptions(0xF7C6DE, .6f);
    /** 招式文件里落樱终式的 effect 名。 */
    public static final String WAVE = "sakura_wave";

    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, LogosDimension.ID);
    /** 刀身散去、只剩刀柄。跟着物品同步给所有客户端，刀身的消散与重新凝聚由客户端看着它的变化画。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> FADED = COMPONENTS.registerComponentType(
            "ryuka_faded", b -> b.persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL));
    /** 正在结算流火一下的挥刀人（连同刀气）；盾和死亡事件靠它认出这一下。 */
    private static final ThreadLocal<@Nullable ServerPlayer> STRIKE = new ThreadLocal<>();

    private Ryuka() {}

    public static void register(IEventBus bus) { COMPONENTS.register(bus); }

    public static boolean faded(ItemStack stack) { return stack.getOrDefault(FADED.get(), false); }

    private static boolean free(Player player) { return player.isCreative() || player.isSpectator(); }

    /** 现在能不能出刀：刀身在、魔力够一刀。 */
    public static boolean canSwing(ServerPlayer player) {
        return !faded(player.getMainHandItem()) && (free(player) || PlayerMagic.of(player).mana >= SWING_COST);
    }

    /** 每刻：扣维持费；付不起时刀身散去，只剩刀柄；散去后魔力回到 {@link #REGROW} 点重新凝聚。 */
    public static void upkeep(ServerPlayer player, ItemStack stack) {
        if (!(stack.getItem() instanceof RyukaItem)) return;
        if (free(player)) {
            if (faded(stack)) regrow(player, stack);
            return;
        }
        var magic = PlayerMagic.of(player);
        if (faded(stack)) {
            if (magic.mana >= REGROW) regrow(player, stack);
            return;
        }
        double tick = UPKEEP / 20;
        if (magic.mana >= tick) {
            magic.mana -= tick;
            return;
        }
        magic.mana = 0;
        stack.set(FADED.get(), true);
        if (player.isUsingItem()) player.stopUsingItem();
        player.level().playSound(null, player.getX(), player.getY() + 1, player.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, .8f, 1.6f);
    }

    private static void regrow(ServerPlayer player, ItemStack stack) {
        stack.remove(FADED.get());
        player.level().playSound(null, player.getX(), player.getY() + 1, player.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1, 1.2f);
    }

    /** 一刀接触前：付这一刀的魔力，落樱终式另付刀气的；付不起时这一刀落空。 */
    public static boolean swing(ServerPlayer player, Moveset.Move move) {
        if (faded(player.getMainHandItem())) return false;
        var magic = PlayerMagic.of(player);
        if (!free(player)) {
            if (magic.mana < SWING_COST) {
                player.level().playSound(null, player.getX(), player.getY() + 1, player.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, .3f, 1.8f);
                return false;
            }
            magic.mana -= SWING_COST;
        }
        player.level().playSound(null, player.getX(), player.getY() + 1, player.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, .7f, 1.5f);
        if (WAVE.equals(move.effect()) && (free(player) || magic.mana >= WAVE_COST)) {
            if (!free(player)) magic.mana -= WAVE_COST;
            PetalWave.launch(player);
        }
        return true;
    }

    /**
     * 流火的一下：白梅的淡粉狐火（docs/FOXFIRE.md，本质是奥秘伤害），不能被盾挡；打死的生物化作花瓣（见 {@link #blossom}）。
     * 命中特效仍是流火自己的一小簇樱花，不放狐火的那一团（出刀太快，会盖住第一人称画面）。
     */
    public static boolean strike(ServerPlayer player, LivingEntity target, double amount) {
        ServerPlayer outer = STRIKE.get();
        STRIKE.set(player);
        try {
            // Logos 的参数顺序是（出手的，挨打的）
            boolean hurt = Foxfire.deal(player, target, amount, FoxfireFlame.SHIRAUME);
            if (hurt) {
                double y = target.getY() + target.getBbHeight() * .6;
                player.level().playSound(null, target.getX(), y, target.getZ(), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.PLAYERS, .8f, 1.4f);
                // 代替 Logos 的奥秘冲击特效：一小簇樱花瓣和几点淡粉的光，第一人称里也不挡视线
                if (player.level() instanceof net.minecraft.server.level.ServerLevel level) {
                    float w = target.getBbWidth() * .35f;
                    level.sendParticles(net.minecraft.core.particles.ParticleTypes.CHERRY_LEAVES, target.getX(), y, target.getZ(), 4, w, .25, w, 0);
                    level.sendParticles(HIT_DUST, target.getX(), y, target.getZ(), 5, w, .2, w, 0);
                }
            }
            return hurt;
        } finally {
            if (outer == null) STRIKE.remove(); else STRIKE.set(outer);
        }
    }

    /** 正在结算流火的一下（连同刀气）。 */
    public static boolean striking() { return STRIKE.get() != null; }

    /** 被换掉的 Logos 命中特效计数（客户端场景检查用）。 */
    private static int quieted;
    public static void quieted() { quieted++; }
    public static int quietedCount() { return quieted; }

    /** 弹刀招式的名字（招式文件 {@code ryuka.json}）。 */
    public static final String PARRY = "parry";

    /**
     * 原版那一击会让盾牌破防几秒（斧头、监守者等近战，弹射物不算）；不会破防时为 0。条件照抄原版 {@code LivingEntity.applyItemBlocking}
     * 与 {@code Player.blockUsingItem}：挡下了伤害、不是弹射物、直接出手的是生物。
     */
    public static float breaks(LivingShieldBlockEvent event) {
        var source = event.getDamageSource();
        if (event.getBlockedDamage() <= 0 || source.is(net.minecraft.tags.DamageTypeTags.IS_PROJECTILE)
                || !(source.getDirectEntity() instanceof LivingEntity attacker)) return 0;
        return attacker.getSecondsToDisableBlocking();
    }

    /**
     * 魔力护盾挡下一击、付完这一下的魔力之后（{@link dev.logos.dimension.longsword.ManaGuard}）：会破盾的攻击，弹刀不额外花魔力；
     * 普通格挡另付 {@link #BREAK_COST} × 破盾秒数（斧头、监守者 5 秒 → 20 点），付不起才破防，按原版秒数不能格挡。
     * 这一击照样挡下。创造模式不扣也不破。
     */
    public static void guarded(ServerPlayer player, LivingShieldBlockEvent event) {
        float seconds = breaks(event);
        if (seconds <= 0 || free(player) || PARRY.equals(dev.logos.dimension.longsword.LongswordCombat.move(player))) return;
        var magic = PlayerMagic.of(player);
        double extra = BREAK_COST * seconds;
        if (magic.mana >= extra) {
            magic.mana -= extra;
            return;
        }
        shatter(player, seconds);
    }

    /** 破防：和原版盾牌被斧头砍中一样，放下刀、冷却 {@code seconds} 秒不能再格挡。 */
    private static void shatter(ServerPlayer player, float seconds) {
        ItemStack stack = player.getItemBlockingWith();
        if (stack == null) return;
        player.getCooldowns().addCooldown(stack, Math.round(seconds * 20));
        player.stopUsingItem();
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, .8f, .8f + player.getRandom().nextFloat() * .4f);
    }

    /** 无形之刃：在魔力护盾挡魔法（最先）之后、打刀的弹刀判定之前，让这一下不算被挡。 */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void unblockable(LivingShieldBlockEvent event) {
        var striker = STRIKE.get();
        if (striker != null && event.getDamageSource().getEntity() == striker) event.setBlocked(false);
    }

    /** 花散：被流火斩杀的生物整只化作樱花瓣消散（玩家不消散）。 */
    @SubscribeEvent
    static void blossom(LivingDeathEvent event) {
        var striker = STRIKE.get();
        var dead = event.getEntity();
        if (striker == null || event.getSource().getEntity() != striker || dead instanceof Player) return;
        Dissolving.entity(dead, DissolveShape.noise(), BLOSSOM_TICKS, CHERRY | Dissolving.PETALS);
    }
}
