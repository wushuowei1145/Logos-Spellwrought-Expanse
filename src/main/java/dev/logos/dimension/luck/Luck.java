package dev.logos.dimension.luck;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.logos.dimension.LogosDimension;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 今日运势：抽到的运势记在玩家身上，管到下一次日出。
 *
 * <p>记录才是准的，药水效果只是让玩家看见的图标：喝牛奶、死亡都会清掉效果，但每秒按记录补回来，
 * 所以霉运只能到结签架上解，不能靠牛奶或自杀洗掉。玩法读运势用 {@link #level}，不要去读效果。
 */
public final class Luck {
    private static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, LogosDimension.ID);
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, LogosDimension.ID);
    private static final Map<LuckKind, Holder<MobEffect>> GOOD = new EnumMap<>(LuckKind.class);
    private static final Map<LuckKind, Holder<MobEffect>> BAD = new EnumMap<>(LuckKind.class);
    private static final Supplier<AttachmentType<Today>> TODAY = ATTACHMENTS.register("luck_today",
        () -> AttachmentType.<Today>builder(() -> Today.NONE).serialize(Today.CODEC.fieldOf("today"), today -> today != Today.NONE).copyOnDeath().build());
    private static final long DAY_TICKS = 24000;
    private static final int SYNC_TICKS = 20;

    static {
        for (LuckKind kind : LuckKind.values()) {
            GOOD.put(kind, EFFECTS.register(kind.id + "_luck", () -> new LuckEffect(MobEffectCategory.BENEFICIAL, kind.color)));
            if (kind.hasBad) BAD.put(kind, EFFECTS.register(kind.id + "_misfortune", () -> new LuckEffect(MobEffectCategory.HARMFUL, 0x4A3A52)));
        }
    }

    /** 没有实际作用的效果：只占一个图标，运势的作用由各玩法自己读 {@link #level} 决定。 */
    private static final class LuckEffect extends MobEffect {
        LuckEffect(MobEffectCategory category, int color) {
            super(category, color);
        }
    }

    /**
     * 一天的运势。{@code vanilla} 是原版幸运（正）或霉运（负）的等级；{@code levels} 是各种运的等级，正吉负凶。
     * clock、day 记下是哪个时钟的哪一天，这个时钟换天就过期。
     */
    private record Today(Identifier clock, long day, int vanilla, Map<LuckKind, Integer> levels) {
        static final Today NONE = new Today(Identifier.withDefaultNamespace("none"), -1, 0, Map.of());
        static final Codec<Today> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("clock").forGetter(Today::clock),
            Codec.LONG.fieldOf("day").forGetter(Today::day),
            Codec.INT.fieldOf("vanilla").forGetter(Today::vanilla),
            Codec.unboundedMap(LuckKind.CODEC, Codec.INT).fieldOf("levels").forGetter(Today::levels)).apply(i, Today::new));
    }

    private Luck() {
    }

    public static void register(IEventBus bus) {
        EFFECTS.register(bus);
        ATTACHMENTS.register(bus);
        NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Post event) -> {
            if (event.getEntity() instanceof ServerPlayer player && player.tickCount % SYNC_TICKS == 0) sync(player);
        });
    }

    /** 玩家所在维度的时钟，看到的日夜就是它走出来的，睡觉跳过夜晚也拨它；下界这类没有时钟的维度跟主世界。 */
    public static Holder<WorldClock> clock(Level level) {
        return level.dimensionType().defaultClock().orElseGet(() -> level.registryAccess().getOrThrow(WorldClocks.OVERWORLD));
    }

    public static Identifier clockId(Holder<WorldClock> clock) {
        return clock.unwrapKey().orElseThrow().identifier();
    }

    /** 这个时钟走到第几天：刻数 0 是日出，所以日出换天。 */
    public static long day(Level level, Holder<WorldClock> clock) {
        return Math.floorDiv(level.clockManager().getTotalTicks(clock), DAY_TICKS);
    }

    /** 记录上的时钟今天还是不是记下的那一天；时钟被数据包删掉了也算过期。 */
    private static boolean current(Level level, Today today) {
        return level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK)
            .get(ResourceKey.create(Registries.WORLD_CLOCK, today.clock()))
            .filter(clock -> day(level, clock) == today.day()).isPresent();
    }

    /** 今天这种运的等级：正吉负凶，0 表示今天没抽到这一项。玩法接实际效果时读这里（只在服务端有值）。 */
    public static int level(Player player, LuckKind kind) {
        Today today = player.getExistingDataOrNull(TODAY);
        if (today == null || !current(player.level(), today)) return 0;
        return today.levels().getOrDefault(kind, 0);
    }

    /** 换上今天的运势，昨天留下的先清掉。 */
    public static void grant(ServerPlayer player, int vanilla, Map<LuckKind, Integer> levels) {
        clear(player);
        Holder<WorldClock> clock = clock(player.level());
        player.setData(TODAY, new Today(clockId(clock), day(player.level(), clock), vanilla, Map.copyOf(levels)));
        sync(player);
    }

    /** 解掉这个时钟这一天抽到的凶运，吉运留着；不是身上现在这份运势就不算。返回 true 表示确实解掉了什么。 */
    public static boolean cleanse(ServerPlayer player, Identifier clock, long day) {
        Today today = player.getExistingDataOrNull(TODAY);
        if (today == null || !today.clock().equals(clock) || today.day() != day || !current(player.level(), today)) return false;
        Map<LuckKind, Integer> kept = new HashMap<>();
        today.levels().forEach((kind, level) -> {
            if (level > 0) kept.put(kind, level);
        });
        if (today.vanilla() >= 0 && kept.size() == today.levels().size()) return false;
        clear(player);
        player.setData(TODAY, new Today(today.clock(), today.day(), Math.max(0, today.vanilla()), kept));
        sync(player);
        return true;
    }

    /** 把效果对齐记录：过期就清掉，还在就补上被牛奶、死亡清掉的图标。grant 之外每秒跑一次。 */
    public static void sync(ServerPlayer player) {
        Today today = player.getExistingDataOrNull(TODAY);
        if (today == null) return;
        if (!current(player.level(), today)) {
            clear(player);
            player.removeData(TODAY);
            return;
        }
        var clock = player.level().registryAccess().lookupOrThrow(Registries.WORLD_CLOCK)
            .getOrThrow(ResourceKey.create(Registries.WORLD_CLOCK, today.clock()));
        int left = (int) (DAY_TICKS - Math.floorMod(player.level().clockManager().getTotalTicks(clock), DAY_TICKS));
        if (today.vanilla() != 0) show(player, today.vanilla() > 0 ? MobEffects.LUCK : MobEffects.UNLUCK, Math.abs(today.vanilla()), left);
        today.levels().forEach((kind, level) -> {
            if (level != 0) show(player, (level > 0 ? GOOD : BAD).get(kind), Math.abs(level), left);
        });
    }

    /**
     * 只补缺的：已有同种同级或更高的效果（比如赛钱箱给的福运）就不动，也不反复刷新。等级低的会被盖过，
     * 原版会把更久的低级效果藏在后面，到时接着走。图标留着，粒子不要，免得身上冒一整天。
     */
    private static void show(ServerPlayer player, Holder<MobEffect> effect, int level, int ticks) {
        MobEffectInstance current = player.getEffect(effect);
        if (current == null || current.getAmplifier() < level - 1) {
            player.addEffect(new MobEffectInstance(effect, ticks, level - 1, false, false, true));
        }
    }

    /** 清掉运势的效果。原版幸运不动，它可能来自赛钱箱的保佑，让它自己走完。 */
    private static void clear(ServerPlayer player) {
        GOOD.values().forEach(player::removeEffect);
        BAD.values().forEach(player::removeEffect);
        player.removeEffect(MobEffects.UNLUCK);
    }
}
