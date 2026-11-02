package dev.logos.dimension.economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 万国钱囊里的东西：每种货币一个最小单位的余额，不限数量。
 * locked 里的货币结账时不会被自动换汇（炒汇玩家锁住持仓）；autoCollect 打开时捡到的钱币直接进钱囊。
 * 不可变：改动都返回新的一份，再写回物品。
 */
public record WalletContents(Map<Currency, Long> balances, Set<Currency> locked, boolean autoCollect) {
    public static final WalletContents EMPTY = new WalletContents(Map.of(), Set.of(), true);
    private static final Codec<Currency> CURRENCY = Codec.STRING.xmap(Currency::byId, c -> c.id);
    public static final Codec<WalletContents> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(CURRENCY, Codec.LONG).optionalFieldOf("balances", Map.of()).forGetter(WalletContents::balances),
            CURRENCY.listOf().optionalFieldOf("locked", List.of()).forGetter(w -> List.copyOf(w.locked)),
            Codec.BOOL.optionalFieldOf("auto_collect", true).forGetter(WalletContents::autoCollect)
    ).apply(i, (b, l, a) -> new WalletContents(b, l.isEmpty() ? Set.of() : EnumSet.copyOf(l), a)));
    public static final StreamCodec<RegistryFriendlyByteBuf, WalletContents> STREAM_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC);

    public WalletContents {
        var copy = new EnumMap<Currency, Long>(Currency.class);
        balances.forEach((c, v) -> { if (v > 0) copy.put(c, v); });
        balances = Map.copyOf(copy);
        locked = Set.copyOf(locked);
    }

    public long get(Currency c) { return balances.getOrDefault(c, 0L); }

    /** 加上（负数为扣除）；余额不足或溢出时返回 null。 */
    public WalletContents add(Currency c, long delta) {
        long next;
        try { next = Math.addExact(get(c), delta); } catch (ArithmeticException e) { return null; }
        if (next < 0) return null;
        var copy = new EnumMap<Currency, Long>(Currency.class);
        copy.putAll(balances);
        copy.put(c, next);
        return new WalletContents(copy, locked, autoCollect);
    }

    public WalletContents toggleLock(Currency c) {
        var copy = locked.isEmpty() ? EnumSet.noneOf(Currency.class) : EnumSet.copyOf(locked);
        if (!copy.remove(c)) copy.add(c);
        return new WalletContents(balances, copy, autoCollect);
    }

    public WalletContents withAutoCollect(boolean value) { return new WalletContents(balances, locked, value); }

    /** 按中间价折合多少铜分。 */
    public double value(java.util.function.ToDoubleFunction<Currency> rate) {
        double v = 0;
        for (var e : balances.entrySet()) v += e.getValue() * rate.applyAsDouble(e.getKey());
        return v;
    }

    /** 结账时自动换汇的顺序：先帝国币，再按折合价值从多到少；锁住的不动。 */
    public List<Currency> payOrder(Currency except, java.util.function.ToDoubleFunction<Currency> rate) {
        var list = new ArrayList<Currency>();
        for (var c : Currency.values()) if (c != except && !locked.contains(c) && get(c) > 0) list.add(c);
        list.sort((a, b) -> a == Currency.IMPERIAL ? -1 : b == Currency.IMPERIAL ? 1
                : Double.compare(get(b) * rate.applyAsDouble(b), get(a) * rate.applyAsDouble(a)));
        return list;
    }
}
