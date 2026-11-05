package dev.logos.dimension.cloudsea;

import com.mojang.serialization.Codec;
import dev.logos.dimension.LogosDimension;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 抽签：每位玩家每天能在签箱抽一支，日出换天。吉签带一段福运，凶签带一段霉运；
 * 把签纸系到结签架上会解掉霉运（{@link OmikujiRackBlock}）。
 */
public final class Omikuji {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, LogosDimension.ID);
    /** 在每个世界时钟上，上次抽签是第几天；各维度的时钟走得不一样，分开记才不会来回换维度多抽。死亡不清除，免得靠重生多抽。 */
    private static final Supplier<AttachmentType<Map<Identifier, Long>>> LAST_DAY = ATTACHMENTS.register("omikuji_days",
        () -> AttachmentType.<Map<Identifier, Long>>builder(() -> Map.of())
            .serialize(Codec.unboundedMap(Identifier.CODEC, Codec.LONG).fieldOf("days"), days -> !days.isEmpty()).copyOnDeath().build());
    private static final long DAY_TICKS = 24000;

    /** 一种签运：抽中权重；amplifier ≥ 0 时带效果，秒数为效果时长。 */
    public enum Fortune {
        DAIKICHI("daikichi", 8, true, 1, 600),
        CHUKICHI("chukichi", 16, true, 0, 480),
        KICHI("kichi", 20, true, 0, 360),
        SHOKICHI("shokichi", 20, true, 0, 240),
        SUEKICHI("suekichi", 16, true, 0, 120),
        KYO("kyo", 15, false, 0, 300),
        DAIKYO("daikyo", 5, false, 1, 600);

        public final String id;
        public final int weight;
        public final boolean good;
        public final int amplifier;
        public final int seconds;

        Fortune(String id, int weight, boolean good, int amplifier, int seconds) {
            this.id = id;
            this.weight = weight;
            this.good = good;
            this.amplifier = amplifier;
            this.seconds = seconds;
        }

        public String key() {
            return "fortune.logos_dimension." + id;
        }
    }

    private Omikuji() {
    }

    public static void register(IEventBus bus) {
        ATTACHMENTS.register(bus);
    }

    /** 按权重挑一支；roll 落在 [0, 总权重) 内。 */
    public static Fortune pick(int roll) {
        for (Fortune f : Fortune.values()) {
            roll -= f.weight;
            if (roll < 0) return f;
        }
        return Fortune.KICHI;
    }

    public static int totalWeight() {
        int total = 0;
        for (Fortune f : Fortune.values()) total += f.weight;
        return total;
    }

    /** 玩家所在维度的时钟，看到的日夜就是它走出来的，睡觉跳过夜晚也拨它；下界这类没有时钟的维度跟主世界。 */
    private static Holder<WorldClock> clock(Level level) {
        return level.dimensionType().defaultClock().orElseGet(() -> level.registryAccess().getOrThrow(WorldClocks.OVERWORLD));
    }

    private static Identifier clockId(Holder<WorldClock> clock) {
        return clock.unwrapKey().orElseThrow().identifier();
    }

    /** 这个时钟走到第几天：刻数 0 是日出，所以日出换天。 */
    private static long today(Level level, Holder<WorldClock> clock) {
        return Math.floorDiv(level.clockManager().getTotalTicks(clock), DAY_TICKS);
    }

    /** 今天已经抽过了吗。 */
    public static boolean drawnToday(ServerPlayer player) {
        Holder<WorldClock> clock = clock(player.level());
        Long last = player.getData(LAST_DAY).get(clockId(clock));
        return last != null && last == today(player.level(), clock);
    }

    /** 抽一支：记下今天，上效果，返回签纸。 */
    public static ItemStack draw(ServerPlayer player, RandomSource random) {
        Holder<WorldClock> clock = clock(player.level());
        Map<Identifier, Long> days = new HashMap<>(player.getData(LAST_DAY));
        days.put(clockId(clock), today(player.level(), clock));
        player.setData(LAST_DAY, days);
        Fortune fortune = pick(random.nextInt(totalWeight()));
        player.addEffect(new MobEffectInstance(fortune.good ? MobEffects.LUCK : MobEffects.UNLUCK, fortune.seconds * 20, fortune.amplifier));
        ItemStack paper = new ItemStack(FoxWildsBlocks.OMIKUJI.get());
        paper.set(DataComponents.ITEM_NAME, Component.translatable(fortune.key()));
        paper.set(DataComponents.LORE, new ItemLore(List.of(Component.translatable(fortune.key() + ".verse"))));
        return paper;
    }
}
