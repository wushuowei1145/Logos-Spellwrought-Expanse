package dev.logos.dimension.cloudsea;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.luck.Luck;
import dev.logos.dimension.luck.LuckKind;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

/**
 * 抽签：每位玩家每天能在签箱抽一支，日出换天。签运决定原版福运或霉运的等级，再随机带几项运势（{@link LuckKind}），
 * 都管到下一次日出（{@link Luck}）。把今天抽到的凶签系到结签架上会解掉凶运（{@link OmikujiRackBlock}）。
 */
public final class Omikuji {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, LogosDimension.ID);
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, LogosDimension.ID);
    /** 在每个世界时钟上，上次抽签是第几天；各维度的时钟走得不一样，分开记才不会来回换维度多抽。死亡不清除，免得靠重生多抽。 */
    private static final Supplier<AttachmentType<Map<Identifier, Long>>> LAST_DAY = ATTACHMENTS.register("omikuji_days",
        () -> AttachmentType.<Map<Identifier, Long>>builder(() -> Map.of())
            .serialize(Codec.unboundedMap(Identifier.CODEC, Codec.LONG).fieldOf("days"), days -> !days.isEmpty()).copyOnDeath().build());
    /** 签纸记下是吉是凶、哪一天抽的：只有今天的凶签才解得掉今天的凶运。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Slip>> SLIP = COMPONENTS.registerComponentType(
        "omikuji_slip", b -> b.persistent(Slip.CODEC).networkSynchronized(Slip.STREAM_CODEC));

    public record Slip(boolean good, Identifier clock, long day) {
        static final Codec<Slip> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.fieldOf("good").forGetter(Slip::good),
            Identifier.CODEC.fieldOf("clock").forGetter(Slip::clock),
            Codec.LONG.fieldOf("day").forGetter(Slip::day)).apply(i, Slip::new));
        static final StreamCodec<ByteBuf, Slip> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, Slip::good, Identifier.STREAM_CODEC, Slip::clock, ByteBufCodecs.VAR_LONG, Slip::day, Slip::new);
    }

    /** 一种签运：抽中权重；原版福运或霉运的等级；随机带几项运势、每项几级。 */
    public enum Fortune {
        DAIKICHI("daikichi", 8, true, 2, 2, 2),
        CHUKICHI("chukichi", 16, true, 1, 2, 1),
        KICHI("kichi", 20, true, 1, 1, 2),
        SHOKICHI("shokichi", 20, true, 1, 1, 1),
        SUEKICHI("suekichi", 16, true, 1, 0, 0),
        KYO("kyo", 15, false, 1, 1, 1),
        DAIKYO("daikyo", 5, false, 2, 2, 2);

        public final String id;
        public final int weight;
        public final boolean good;
        public final int vanilla;
        public final int kinds;
        public final int level;

        Fortune(String id, int weight, boolean good, int vanilla, int kinds, int level) {
            this.id = id;
            this.weight = weight;
            this.good = good;
            this.vanilla = vanilla;
            this.kinds = kinds;
            this.level = level;
        }

        public String key() {
            return "fortune.logos_dimension." + id;
        }
    }

    private Omikuji() {
    }

    public static void register(IEventBus bus) {
        ATTACHMENTS.register(bus);
        COMPONENTS.register(bus);
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

    /** 今天已经抽过了吗。 */
    public static boolean drawnToday(ServerPlayer player) {
        Holder<WorldClock> clock = Luck.clock(player.level());
        Long last = player.getData(LAST_DAY).get(Luck.clockId(clock));
        return last != null && last == Luck.day(player.level(), clock);
    }

    /** 抽一支：按权重挑签运。 */
    public static ItemStack draw(ServerPlayer player, RandomSource random) {
        return draw(player, pick(random.nextInt(totalWeight())), random);
    }

    /** 记下今天，换上今天的运势，屏幕上报出签运与签文，返回签纸。 */
    public static ItemStack draw(ServerPlayer player, Fortune fortune, RandomSource random) {
        Holder<WorldClock> clock = Luck.clock(player.level());
        Identifier clockId = Luck.clockId(clock);
        long day = Luck.day(player.level(), clock);
        Map<Identifier, Long> days = new HashMap<>(player.getData(LAST_DAY));
        days.put(clockId, day);
        player.setData(LAST_DAY, days);

        int sign = fortune.good ? 1 : -1;
        Map<LuckKind, Integer> levels = new EnumMap<>(LuckKind.class);
        for (LuckKind kind : kinds(fortune, random)) levels.put(kind, sign * fortune.level);
        Luck.grant(player, sign * fortune.vanilla, levels);

        ItemStack paper = new ItemStack(FoxWildsBlocks.OMIKUJI.get());
        paper.set(DataComponents.ITEM_NAME, Component.translatable(fortune.key()));
        paper.set(SLIP.get(), new Slip(fortune.good, clockId, day));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.translatable(fortune.key() + ".verse"));
        levels.forEach((kind, level) -> lore.add(Component.translatable("fortune.logos_dimension.aspect",
            Component.translatable(kind.labelKey()), Component.translatable(verdict(level)))));
        paper.set(DataComponents.LORE, new ItemLore(lore));
        player.sendOverlayMessage(Component.translatable("fortune.logos_dimension.drawn",
            Component.translatable(fortune.key()), Component.translatable(fortune.key() + ".verse")));
        return paper;
    }

    /** 这支签带哪几项运势：不重复地随机挑；凶签只从有凶的一面的里挑。 */
    private static List<LuckKind> kinds(Fortune fortune, RandomSource random) {
        List<LuckKind> pool = new ArrayList<>();
        for (LuckKind kind : LuckKind.values()) if (fortune.good || kind.hasBad) pool.add(kind);
        Collections.shuffle(pool, new Random(random.nextLong()));
        return pool.subList(0, fortune.kinds);
    }

    private static String verdict(int level) {
        return "fortune.logos_dimension.verdict." + (level < 0 ? "minus" : "plus") + Math.abs(level);
    }

    /** 把签纸系上去：今天抽到的凶签才解得掉今天的凶运；吉签、旧签、老存档的签只是系上。返回 true 表示解掉了。 */
    static boolean tie(ServerPlayer player, ItemStack paper) {
        Slip slip = paper.get(SLIP.get());
        return slip != null && !slip.good() && Luck.cleanse(player, slip.clock(), slip.day());
    }
}
