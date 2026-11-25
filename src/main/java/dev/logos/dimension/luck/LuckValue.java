package dev.logos.dimension.luck;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.logos.dimension.LogosDimension;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 战利品表里按运势取值的数：{@code values} 依次是 −2、−1、0、+1、+2 级时的取值。
 *
 * <pre>{"type": "logos_dimension:luck", "values": [0.5, 0.75, 1, 1.25, 1.5]}                  // 原版幸运（总签运）
 * {"type": "logos_dimension:luck", "kind": "wealth", "values": [2, 3, 4, 6, 8]}              // 某一项运势</pre>
 *
 * <p>不写 {@code kind} 读原版幸运（{@link LootContext#getLuck}，开箱时原版就传进来，四舍五入后截在 ±2）；
 * 写了就读开箱玩家的 {@link Luck#level}。没有玩家（箱子没开就被破坏、被漏斗抽走）按 0 级。
 * 可以用在 {@code random_chance} 的概率、{@code set_count} 的数量、池子的抽取次数等任何接受数的地方。
 */
public record LuckValue(Optional<LuckKind> kind, List<Float> values) implements NumberProvider {
    public static final MapCodec<LuckValue> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
        LuckKind.CODEC.optionalFieldOf("kind").forGetter(LuckValue::kind),
        Codec.FLOAT.listOf().validate(v -> v.size() == 5 ? DataResult.success(v) : DataResult.error(() -> "values 要正好 5 个（−2 到 +2 级），现在是 " + v.size()))
            .fieldOf("values").forGetter(LuckValue::values)).apply(i, LuckValue::new));

    private static final DeferredRegister<MapCodec<? extends NumberProvider>> TYPES = DeferredRegister.create(Registries.LOOT_NUMBER_PROVIDER_TYPE, LogosDimension.ID);

    static {
        TYPES.register("luck", () -> MAP_CODEC);
    }

    public static void register(IEventBus bus) {
        TYPES.register(bus);
    }

    /** 这次开箱时的等级，−2 到 +2。 */
    public int level(LootContext context) {
        if (kind.isEmpty()) return Mth.clamp(Math.round(context.getLuck()), -2, 2);
        return context.getOptionalParameter(LootContextParams.THIS_ENTITY) instanceof Player player ? Mth.clamp(Luck.level(player, kind.get()), -2, 2) : 0;
    }

    @Override
    public float getFloat(LootContext context) {
        return values.get(level(context) + 2);
    }

    @Override
    public MapCodec<LuckValue> codec() {
        return MAP_CODEC;
    }
}
